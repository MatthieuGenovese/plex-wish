package fr.plexwish.animeserver.media;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.common.ApiException;
import io.agroal.api.AgroalDataSource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Onglet admin « Médias » (ARCHITECTURE §22.4) : avancement de l'analyse, répartition par catégorie, liste filtrable,
 * espace estimé pour remuxer les AVI/OGM, test à blanc du remux. Réservé à l'admin : seules ces réponses contiennent
 * des chemins (relatifs à la bibliothèque).
 */
@Path("/api/admin/media")
@RolesAllowed("ADMIN")
@Produces(MediaType.APPLICATION_JSON)
public class MediaAdminResource {

    static final Set<String> FILTERS = Set.of("pending", "failed", "DIRECT", "REMUX", "TRANSCODE", "browser-ko", "browser-ok");
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Summary(boolean enabled, String ffprobeVersion, String workerState, long files, long analyzed, long failed,
                          long pending, Map<String, Long> android, long browserPlayable, long browserNotPlayable,
                          long remuxFiles, long remuxBytes, long episodes, long episodesWithDuration) {
    }

    public record TestResult(String variant, boolean ok, Integer exitCode, boolean timedOut, long elapsedMs, String message) {
    }

    public record Entry(long mediaFileId, String path, String animeTitle, Integer seasonNumber, Integer episodeNumber,
                        String extension, long fileSize, String status, String error, Double durationSeconds,
                        String video, String audio, String subtitles, String android, String androidReasons,
                        Boolean browserPlayable, String browserReasons, List<TestResult> remuxTest) {
    }

    public record EntryPage(long total, int page, int size, List<Entry> items) {
    }

    @Inject
    AgroalDataSource dataSource;
    @Inject
    MediaConfig config;
    @Inject
    MediaProbeService service;
    @Inject
    MediaProbeWorker worker;
    @Inject
    RemuxTestService remuxTest;

    private static final String FILES = " FROM media_file f LEFT JOIN media_probe p ON p.media_file_id = f.id WHERE f.available AND f.kind = 'EPISODE'";
    private static final String PENDING = "(p.media_file_id IS NULL OR p.probed_size <> f.file_size OR p.probed_modified IS DISTINCT FROM f.last_modified)";

    @GET
    @Path("/summary")
    public Summary summary() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            long[] counts;
            try (PreparedStatement st = c.prepareStatement("SELECT count(*), count(*) FILTER (WHERE " + PENDING
                    + "), count(*) FILTER (WHERE NOT " + PENDING + " AND p.status = 'OK'), count(*) FILTER (WHERE NOT "
                    + PENDING + " AND p.status = 'FAILED'), count(*) FILTER (WHERE p.browser_playable AND NOT " + PENDING
                    + "), count(*) FILTER (WHERE NOT p.browser_playable AND NOT " + PENDING
                    + "), coalesce(sum(f.file_size) FILTER (WHERE p.android_class = 'REMUX'), 0)" + FILES);
                 ResultSet rs = st.executeQuery()) {
                rs.next();
                counts = new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)};
            }
            Map<String, Long> android = new LinkedHashMap<>();
            for (MediaRules.Android a : MediaRules.Android.values()) {
                android.put(a.name(), 0L);
            }
            try (PreparedStatement st = c.prepareStatement("SELECT p.android_class, count(*)" + FILES
                    + " AND p.android_class IS NOT NULL AND NOT " + PENDING + " GROUP BY 1");
                 ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    android.put(rs.getString(1), rs.getLong(2));
                }
            }
            long episodes;
            long withDuration;
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT count(*), count(*) FILTER (WHERE e.duration_seconds IS NOT NULL)
                    FROM episode e JOIN media_file f ON f.id = e.media_file_id WHERE f.available""");
                 ResultSet rs = st.executeQuery()) {
                rs.next();
                episodes = rs.getLong(1);
                withDuration = rs.getLong(2);
            }
            return new Summary(config.probeEnabled(), service.ffprobeVersion(), worker.state(), counts[0], counts[2], counts[3],
                    counts[1], android, counts[4], counts[5], android.get("REMUX"), counts[6], episodes, withDuration);
        }
    }

    @GET
    @Path("/files")
    public EntryPage files(@QueryParam("filter") String filter, @QueryParam("q") String q,
                           @QueryParam("page") @DefaultValue("0") @Min(0) int page,
                           @QueryParam("size") @DefaultValue("50") @Min(1) @Max(200) int size) throws SQLException {
        if (filter != null && !FILTERS.contains(filter)) {
            throw new ApiException(400, "INVALID_FILTER", "Filtre inconnu : " + String.join(", ", FILTERS));
        }
        String where = FILES + " AND (?::text IS NULL OR lower(unaccent(f.relative_path)) LIKE lower(unaccent(?)) ESCAPE '!')"
                + switch (filter == null ? "" : filter) {
                    case "pending" -> " AND " + PENDING;
                    case "failed" -> " AND NOT " + PENDING + " AND p.status = 'FAILED'";
                    case "browser-ko" -> " AND NOT " + PENDING + " AND p.browser_playable = FALSE";
                    case "browser-ok" -> " AND NOT " + PENDING + " AND p.browser_playable";
                    case "" -> "";
                    default -> " AND NOT " + PENDING + " AND p.android_class = '" + MediaRules.Android.valueOf(filter).name() + "'";
                };
        String like = q == null || q.isBlank() ? null : "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        try (Connection c = dataSource.getConnection()) {
            long total;
            try (PreparedStatement st = c.prepareStatement("SELECT count(*)" + where)) {
                st.setString(1, like);
                st.setString(2, like);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            List<Entry> items = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT f.id, f.relative_path, a.title, s.season_number, e.episode_number, f.container, f.file_size,
                           CASE WHEN """ + PENDING + """
                     THEN 'PENDING' ELSE p.status END, p.error, p.duration_seconds,
                           p.video_codec, p.video_profile, p.video_bit_depth, p.width, p.height, p.audio::text, p.subtitles::text,
                           p.android_class, p.android_reasons, p.browser_playable, p.browser_reasons,
                           (SELECT json_agg(json_build_object('variant', r.variant, 'ok', r.ok, 'exitCode', r.exit_code,
                                   'timedOut', r.timed_out, 'elapsedMs', r.elapsed_ms, 'message', r.message) ORDER BY r.variant)
                            FROM remux_test_result r WHERE r.media_file_id = f.id)::text
                    """ + where.replace(" FROM media_file f LEFT JOIN media_probe p ON p.media_file_id = f.id",
                    " FROM media_file f LEFT JOIN media_probe p ON p.media_file_id = f.id LEFT JOIN episode e ON e.media_file_id = f.id"
                            + " LEFT JOIN season s ON s.id = e.season_id LEFT JOIN anime a ON a.id = s.anime_id")
                    + " ORDER BY f.relative_path LIMIT ? OFFSET ?")) {
                st.setString(1, like);
                st.setString(2, like);
                st.setInt(3, size);
                st.setLong(4, (long) page * size);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        items.add(entry(rs));
                    }
                }
            }
            return new EntryPage(total, page, size, items);
        }
    }

    private static Entry entry(ResultSet rs) throws SQLException {
        String codec = rs.getString(11);
        String video = null;
        if (codec != null) {
            Integer bits = (Integer) rs.getObject(13);
            video = MediaRules.label(codec) + (rs.getString(12) != null ? " " + rs.getString(12) : "")
                    + (bits != null && bits > 8 ? " " + bits + " bits" : "")
                    + (rs.getObject(14) != null ? " " + rs.getInt(14) + "×" + rs.getInt(15) : "");
        }
        java.math.BigDecimal d = rs.getBigDecimal(10);
        List<TestResult> tests = List.of();
        try {
            if (rs.getString(22) != null) {
                tests = JSON.readValue(rs.getString(22), new TypeReference<>() {
                });
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Boolean browser = (Boolean) rs.getObject(20);
        return new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), (Integer) rs.getObject(4), (Integer) rs.getObject(5),
                rs.getString(6), rs.getLong(7), rs.getString(8), rs.getString(9), d == null ? null : d.doubleValue(), video,
                tracks(rs.getString(16), true), tracks(rs.getString(17), false), rs.getString(18), rs.getString(19), browser,
                rs.getString(21), tests);
    }

    /** Pistes en une ligne : « AAC 2 ch (jpn) · MP3 » / « ASS (fre, par défaut) · PGS (fre) ». */
    private static String tracks(String json, boolean audio) {
        if (json == null) {
            return null;
        }
        try {
            List<Map<String, Object>> list = JSON.readValue(json, new TypeReference<>() {
            });
            if (list.isEmpty()) {
                return "aucune";
            }
            return list.stream().map(t -> {
                List<String> extra = new ArrayList<>();
                if (t.get("language") != null) {
                    extra.add(String.valueOf(t.get("language")));
                }
                if (Boolean.TRUE.equals(t.get("isDefault"))) {
                    extra.add("par défaut");
                }
                if (Boolean.TRUE.equals(t.get("forced"))) {
                    extra.add("forcé");
                }
                String ch = audio && t.get("channels") != null ? " " + t.get("channels") + " ch" : "";
                return MediaRules.label((String) t.get("codec")) + ch + (extra.isEmpty() ? "" : " (" + String.join(", ", extra) + ")");
            }).collect(Collectors.joining(" · "));
        } catch (IOException e) {
            return null;
        }
    }

    /** Refaire l'analyse d'un fichier (au prochain passage de la tâche). */
    @POST
    @Path("/files/{id}/reprobe")
    public Map<String, Object> reprobe(@PathParam("id") long id) throws SQLException {
        service.requeue(id);
        worker.wake();
        return Map.of("queued", true, "running", worker.running());
    }

    @POST
    @Path("/reprobe-failed")
    public Map<String, Object> reprobeFailed() throws SQLException {
        int n = service.requeue(null);
        worker.wake();
        return Map.of("queued", n, "running", worker.running());
    }

    // --- Test à blanc du remux -------------------------------------------------------------------------------------

    @GET
    @Path("/remux-test")
    public RemuxTestService.Status remuxTestStatus() throws SQLException {
        return remuxTest.status();
    }

    /** {@code startAt} (facultatif, ISO-8601) : démarrage différé, dans les 24 h (ex. la nuit). */
    @POST
    @Path("/remux-test/start")
    public RemuxTestService.Status startRemuxTest(@QueryParam("startAt") String startAt) throws SQLException {
        Instant at = null;
        if (startAt != null && !startAt.isBlank()) {
            try {
                at = OffsetDateTime.parse(startAt).toInstant();
            } catch (DateTimeParseException e) {
                try {
                    at = Instant.parse(startAt);
                } catch (DateTimeParseException e2) {
                    throw new ApiException(400, "INVALID_START", "Heure de démarrage invalide");
                }
            }
            if (at.isAfter(Instant.now().plus(Duration.ofHours(24)))) {
                throw new ApiException(400, "INVALID_START", "Démarrage différé : 24 h au plus");
            }
        }
        if (service.version(config.ffmpegPath()) == null) {
            throw new ApiException(409, "FFMPEG_MISSING", "ffmpeg introuvable dans le conteneur");
        }
        if (!remuxTest.start(at)) {
            throw new ApiException(409, "REMUX_TEST_RUNNING", "Le test à blanc est déjà lancé");
        }
        return remuxTest.status();
    }

    @POST
    @Path("/remux-test/stop")
    public RemuxTestService.Status stopRemuxTest() throws SQLException {
        remuxTest.stop();
        return remuxTest.status();
    }

    @POST
    @Path("/remux-test/reset")
    public Map<String, Object> resetRemuxTest(@QueryParam("confirm") @DefaultValue("false") boolean confirm) throws SQLException {
        if (!confirm) {
            throw new ApiException(400, "CONFIRMATION_REQUIRED", "Effacer les résultats : confirmer avec confirm=true");
        }
        int n = remuxTest.reset();
        if (n < 0) {
            throw new ApiException(409, "REMUX_TEST_RUNNING", "Arrêter le test à blanc avant d'effacer ses résultats");
        }
        return Map.of("deleted", n);
    }
}
