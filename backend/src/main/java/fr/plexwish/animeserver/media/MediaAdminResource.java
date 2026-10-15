package fr.plexwish.animeserver.media;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.common.ApiException;
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

    public record Entry(long mediaFileId, String path, Long animeId, String animeTitle, Integer seasonNumber, Integer episodeNumber,
                        String extension, long fileSize, String status, String error, Double durationSeconds,
                        String video, String audio, String subtitles, String android, String androidReasons,
                        Boolean browserPlayable, String browserReasons, List<TestResult> remuxTest, String remuxStatus,
                        String remuxError) {
    }

    public record EntryPage(long total, int page, int size, List<Entry> items) {
    }

    @Inject
    MediaProbeRepository probes;
    @Inject
    RemuxRepository remuxJobs;
    @Inject
    MediaConfig config;
    @Inject
    MediaProbeService service;
    @Inject
    MediaProbeWorker worker;
    @Inject
    RemuxTestService remuxTest;
    @Inject
    RemuxService remux;
    @Inject
    RemuxCache remuxCache;

    @GET
    @Path("/summary")
    public Summary summary() throws SQLException {
        MediaProbeRepository.ProbeCounts counts = probes.probeCounts();
        Map<String, Long> android = new LinkedHashMap<>();
        for (MediaRules.Android a : MediaRules.Android.values()) {
            android.put(a.name(), 0L);
        }
        android.putAll(probes.androidClassCounts());
        MediaProbeRepository.DurationCounts durations = probes.durationCounts();
        return new Summary(config.probeEnabled(), service.ffprobeVersion(), worker.state(), counts.files(), counts.analyzed(),
                counts.failed(), counts.pending(), android, counts.browserPlayable(), counts.browserNotPlayable(),
                android.get("REMUX"), counts.remuxBytes(), durations.episodes(), durations.withDuration());
    }

    @GET
    @Path("/files")
    public EntryPage files(@QueryParam("filter") String filter, @QueryParam("q") String q,
                           @QueryParam("page") @DefaultValue("0") @Min(0) int page,
                           @QueryParam("size") @DefaultValue("50") @Min(1) @Max(200) int size) throws SQLException {
        if (filter != null && !FILTERS.contains(filter)) {
            throw new ApiException(400, "INVALID_FILTER", "Filtre inconnu : " + String.join(", ", FILTERS));
        }
        String like = q == null || q.isBlank() ? null : "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long total = probes.countFiles(filter, like);
        List<Entry> items = new ArrayList<>();
        for (MediaProbeRepository.FileRow r : probes.files(filter, like, size, (long) page * size)) {
            items.add(entry(r));
        }
        return new EntryPage(total, page, size, items);
    }

    private static Entry entry(MediaProbeRepository.FileRow r) {
        String codec = r.videoCodec();
        String video = null;
        if (codec != null) {
            Integer bits = r.videoBitDepth();
            video = MediaRules.label(codec) + (r.videoProfile() != null ? " " + r.videoProfile() : "")
                    + (bits != null && bits > 8 ? " " + bits + " bits" : "")
                    + (r.width() != null ? " " + r.width() + "×" + r.height() : "");
        }
        java.math.BigDecimal d = r.durationSeconds();
        List<TestResult> tests = List.of();
        try {
            if (r.remuxTestsJson() != null) {
                tests = JSON.readValue(r.remuxTestsJson(), new TypeReference<>() {
                });
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new Entry(r.mediaFileId(), r.path(), r.animeId(), r.animeTitle(), r.seasonNumber(), r.episodeNumber(),
                r.extension(), r.fileSize(), r.status(), r.error(), d == null ? null : d.doubleValue(), video,
                tracks(r.audioJson(), true), tracks(r.subtitlesJson(), false), r.androidClass(), r.androidReasons(),
                r.browserPlayable(), r.browserReasons(), tests, r.remuxStatus(), r.remuxError());
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

    // --- Remux à la demande (§23) -----------------------------------------------------------------------------------

    /** Plafonds de « préparer à l'avance » un animé : nombre de fichiers et part du cache. */
    static final int PREPARE_MAX_FILES = 60;

    public record RemuxQueueEntry(long mediaFileId, String path, String status, int priority, String blocked, Double progress,
                                  Instant requestedAt) {
    }

    public record RemuxSummary(boolean usable, String ffmpegVersion, String cachePath, long maxBytes, long usedBytes,
                               long freeBytes, long ready, long queued, long failed, List<RemuxQueueEntry> queue) {
    }

    public record RemuxJobEntry(long mediaFileId, String path, String status, String variant, Long bytes, Instant requestedAt,
                                Instant finishedAt, Instant lastReadAt, int attempts, Instant nextAttemptAt, String error) {
    }

    @GET
    @Path("/remux")
    public RemuxSummary remuxSummary() throws SQLException {
        RemuxRepository.JobTotals n = remuxJobs.totals();
        List<RemuxQueueEntry> queue = new ArrayList<>();
        for (RemuxRepository.QueueRow q : remuxJobs.queue()) {
            queue.add(new RemuxQueueEntry(q.mediaFileId(), q.path(), q.status(), q.priority(), q.blocked(),
                    remux.progressOf(q.mediaFileId()), q.requestedAt().toInstant()));
        }
        return new RemuxSummary(remux.usable(), remux.ffmpegVersion(), remuxCache.root().toString(), remux.maxBytes(),
                n.readyBytes(), remuxCache.usableSpace(), n.ready(), n.queued(), n.failed(), queue);
    }

    @GET
    @Path("/remux/jobs")
    public List<RemuxJobEntry> remuxJobs(@QueryParam("status") String status) throws SQLException {
        if (status != null && !Set.of("READY", "FAILED", "QUEUED", "RUNNING").contains(status)) {
            throw new ApiException(400, "INVALID_FILTER", "Statut inconnu");
        }
        return remuxJobs.jobs(status).stream()
                .map(j -> new RemuxJobEntry(j.mediaFileId(), j.path(), j.status(), j.variant(), j.bytes(), j.requestedAt(),
                        j.finishedAt(), j.lastReadAt(), j.attempts(), j.nextAttemptAt(), j.error()))
                .toList();
    }

    @POST
    @Path("/remux/jobs/{id}/retry")
    public Map<String, Object> retryRemux(@PathParam("id") long id) throws SQLException {
        if (!remux.retry(id)) {
            throw new ApiException(409, "NOT_FAILED", "Ce remux n'est pas en échec");
        }
        return Map.of("queued", true);
    }

    /** « Préparer à l'avance » les épisodes d'un animé à remuxer (après les demandes des utilisateurs), plafonné. */
    @POST
    @Path("/remux/anime/{animeId}/prepare")
    public Map<String, Object> prepare(@PathParam("animeId") long animeId) throws SQLException {
        List<long[]> files = new ArrayList<>();
        List<OffsetDateTime> modified = new ArrayList<>();
        long total = 0;
        for (RemuxRepository.PrepareCandidate f : remuxJobs.prepareCandidates(animeId)) {
            boolean needs = "OK".equals(f.probeStatus()) ? "REMUX".equals(f.androidClass())
                    : RemuxService.REMUX_EXTENSIONS.contains(f.extension());
            if (needs) {
                files.add(new long[]{f.mediaFileId(), f.size()});
                modified.add(f.modified());
                total += f.size();
            }
        }
        if (files.size() > PREPARE_MAX_FILES || RemuxService.needed(total) > remux.maxBytes() / 2) {
            throw new ApiException(400, "PREPARE_TOO_LARGE", "Trop de fichiers à préparer d'un coup (" + files.size()
                    + " fichiers, " + total / 1_000_000 + " Mo) : " + PREPARE_MAX_FILES
                    + " fichiers et la moitié du cache au plus. Les épisodes seront préparés à la demande.");
        }
        return Map.of("queued", remux.prepare(files, modified), "bytes", total);
    }

    @POST
    @Path("/remux/clear")
    public Map<String, Object> clearRemux(@QueryParam("confirm") @DefaultValue("false") boolean confirm) throws SQLException {
        if (!confirm) {
            throw new ApiException(400, "CONFIRMATION_REQUIRED", "Vider le cache : confirmer avec confirm=true");
        }
        int[] r = remux.clear();
        return Map.of("removed", r[0], "keptInUse", r[1]);
    }
}
