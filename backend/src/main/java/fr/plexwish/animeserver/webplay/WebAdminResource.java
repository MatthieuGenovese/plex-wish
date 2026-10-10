package fr.plexwish.animeserver.webplay;

import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.media.MediaConfig;
import io.agroal.api.AgroalDataSource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Administration du lecteur web (10.3, docs/WEB-PLAYER.md §5) : réglages des conversions, cache (place prise, plafond,
 * disque), préparations en cours, en file et en échec, relancer, annuler, « Préparer l'animé pour le navigateur »,
 * nettoyage du cache. Aucun chemin de fichier : épisodes désignés par animé, saison et numéro.
 */
@Path("/api/admin/web")
@RolesAllowed("ADMIN")
@Produces(MediaType.APPLICATION_JSON)
public class WebAdminResource {

    static final Set<String> KINDS = Set.of(WebPrepService.BASE, WebPrepService.CONV);

    /** Un épisode lié au fichier (le premier, s'il y en a plusieurs). */
    public record Label(Long episodeId, Long animeId, String animeTitle, Integer seasonNumber, Integer episodeNumber) {
    }

    public record Running(String kind, long mediaFileId, Label episode, String phase, int priority, Double progress, Double speed,
                          boolean paused) {
    }

    public record Item(String kind, long mediaFileId, Label episode, String status, int priority, String phase, String error,
                       int attempts, Instant requestedAt, Instant nextAttemptAt, boolean blocked) {
    }

    public record Cache(String hostPath, long usedBytes, long capBytes, long freeBytes, long totalBytes, long readyBase,
                        long readyConverted) {
    }

    public record Overview(WebSettings.View settings, String ffmpegVersion, boolean usable, boolean nightOpen, Cache cache,
                           List<Running> running, long queued, List<Item> queue, List<Item> failures, Map<String, Double> speeds) {
    }

    public record SettingsRequest(@NotNull Integer maxHeight, @NotNull Boolean preventive, @NotNull Boolean preventiveVideo) {
    }

    public record PrepareResult(int episodes, int queued) {
    }

    public record CleanupResult(int orphans, int evicted) {
    }

    @Inject
    WebPrepService prep;
    @Inject
    WebSettings webSettings;
    @Inject
    WebNightService night;
    @Inject
    WebCache cache;
    @Inject
    MediaConfig config;
    @Inject
    AgroalDataSource dataSource;
    @Inject
    fr.plexwish.animeserver.setup.AppSettings settings;

    @GET
    public Overview overview() throws SQLException {
        Map<Long, String> phases = new HashMap<>();
        List<Item> queue = new ArrayList<>();
        List<Item> failures = new ArrayList<>();
        long queued;
        long readyBase;
        long readyConv;
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement st = c.prepareStatement("""
                     SELECT count(*) FILTER (WHERE status = 'QUEUED'), count(*) FILTER (WHERE status = 'READY' AND kind = 'BASE'),
                            count(*) FILTER (WHERE status = 'READY' AND kind = 'CONV') FROM web_job""");
                 ResultSet rs = st.executeQuery()) {
                rs.next();
                queued = rs.getLong(1);
                readyBase = rs.getLong(2);
                readyConv = rs.getLong(3);
            }
            try (PreparedStatement st = c.prepareStatement(ITEMS + " WHERE j.status IN ('QUEUED', 'RUNNING')"
                    + " ORDER BY j.status DESC, j.priority, j.requested_at LIMIT 30");
                 ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    Item i = item(rs);
                    if ("RUNNING".equals(i.status())) {
                        phases.put(i.mediaFileId() * 2 + (WebPrepService.CONV.equals(i.kind()) ? 1 : 0), i.phase());
                    } else {
                        queue.add(i);
                    }
                }
            }
            try (PreparedStatement st = c.prepareStatement(ITEMS + " WHERE j.status = 'FAILED' ORDER BY j.finished_at DESC LIMIT 50");
                 ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    failures.add(item(rs));
                }
            }
        }
        List<Running> running = new ArrayList<>();
        for (WebPrepService.Running r : prep.running()) {
            running.add(new Running(r.kind(), r.mediaFileId(), label(r.mediaFileId()),
                    phases.get(r.mediaFileId() * 2 + (WebPrepService.CONV.equals(r.kind()) ? 1 : 0)), r.priority(), r.progress(),
                    r.speed(), r.paused()));
        }
        Map<String, Double> speeds = new HashMap<>();
        for (String k : List.of("audio", "video720", "video1080", "videosd")) {
            settings.getDouble("web.speed." + k).ifPresent(v -> speeds.put(k, v));
        }
        Cache view = new Cache(config.webCacheHostPath().orElse(null), prep.usedBytes(), prep.maxBytes(), cache.usableSpace(),
                cache.totalSpace(), readyBase, readyConv);
        return new Overview(webSettings.view(), prep.ffmpegVersion(), prep.usable(), webSettings.nightOpen(), view, running, queued,
                queue, failures, speeds);
    }

    private static final String ITEMS = """
            SELECT j.kind, j.media_file_id, j.status, j.priority, j.phase, j.error, j.attempts, j.requested_at, j.next_attempt_at,
                   j.blocked IS NOT NULL, e.id, a.id, a.title, s.season_number, e.episode_number
            FROM web_job j
            LEFT JOIN LATERAL (SELECT id, season_id, episode_number FROM episode WHERE media_file_id = j.media_file_id
                               ORDER BY id LIMIT 1) e ON true
            LEFT JOIN season s ON s.id = e.season_id LEFT JOIN anime a ON a.id = s.anime_id""";

    private static Item item(ResultSet rs) throws SQLException {
        OffsetDateTime req = rs.getObject(8, OffsetDateTime.class);
        OffsetDateTime next = rs.getObject(9, OffsetDateTime.class);
        Label l = new Label((Long) rs.getObject(11), (Long) rs.getObject(12), rs.getString(13), (Integer) rs.getObject(14),
                (Integer) rs.getObject(15));
        return new Item(rs.getString(1), rs.getLong(2), l, rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6),
                rs.getInt(7), req == null ? null : req.toInstant(), next == null ? null : next.toInstant(), rs.getBoolean(10));
    }

    private Label label(long mediaFileId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT e.id, a.id, a.title, s.season_number, e.episode_number FROM episode e
                     JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id
                     WHERE e.media_file_id = ? ORDER BY e.id LIMIT 1""")) {
            st.setLong(1, mediaFileId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? new Label(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getInt(5))
                        : new Label(null, null, null, null, null);
            }
        }
    }

    @PUT
    @Path("/settings")
    public WebSettings.View saveSettings(@Valid @NotNull SettingsRequest request) {
        if (request.maxHeight() != 720 && request.maxHeight() != 1080) {
            throw new ApiException(400, "INVALID_HEIGHT", "Hauteur maximale : 720 ou 1080");
        }
        return webSettings.save(request.maxHeight(), request.preventive(), request.preventiveVideo());
    }

    @POST
    @Path("/jobs/{fileId}/{kind}/retry")
    public void retry(@PathParam("fileId") long fileId, @PathParam("kind") String kind) throws SQLException {
        if (!KINDS.contains(kind) || !prep.retry(fileId, kind)) {
            throw new ApiException(404, "WEB_JOB_NOT_FOUND", "Aucune préparation à relancer");
        }
    }

    @DELETE
    @Path("/jobs/{fileId}/{kind}")
    public void cancel(@PathParam("fileId") long fileId, @PathParam("kind") String kind) throws SQLException {
        if (!KINDS.contains(kind) || !prep.cancel(fileId, kind)) {
            throw new ApiException(404, "WEB_JOB_NOT_FOUND", "Aucune préparation à annuler (une préparation de base en cours va au bout)");
        }
    }

    /** « Préparer l'animé pour le navigateur » : tous ses épisodes, préparation de base puis conversion si elle sert. */
    @POST
    @Path("/anime/{animeId}/prepare")
    public PrepareResult prepareAnime(@PathParam("animeId") long animeId) throws SQLException {
        List<Long> episodes = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT e.id FROM episode e JOIN season s ON s.id = e.season_id WHERE s.anime_id = ? AND e.media_file_id IS NOT NULL
                     ORDER BY s.season_number = 0, s.season_number, e.episode_number""")) {
            st.setLong(1, animeId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    episodes.add(rs.getLong(1));
                }
            }
        }
        if (episodes.isEmpty()) {
            throw new ApiException(404, "ANIME_NOT_FOUND", "Animé introuvable ou sans épisode disponible");
        }
        int queued = 0;
        for (long ep : episodes) {
            if (night.prepareEpisode(ep, WebPrepService.PRIORITY_ADMIN)) {
                queued++;
            }
        }
        return new PrepareResult(episodes.size(), queued);
    }

    /** Nettoyage immédiat (sinon chaque nuit) : préparations orphelines, puis les moins lues jusqu'à 85 % du plafond. */
    @POST
    @Path("/cleanup")
    public CleanupResult cleanup() throws SQLException {
        return new CleanupResult(night.removeOrphans(), night.trim());
    }
}
