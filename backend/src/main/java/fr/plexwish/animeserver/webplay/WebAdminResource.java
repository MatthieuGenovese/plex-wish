package fr.plexwish.animeserver.webplay;

import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.media.MediaConfig;
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

import java.sql.SQLException;
import java.time.Instant;
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
    WebJobRepository repository;
    @Inject
    fr.plexwish.animeserver.setup.AppSettings settings;

    @GET
    public Overview overview() throws SQLException {
        Map<Long, String> phases = new HashMap<>();
        List<Item> queue = new ArrayList<>();
        List<Item> failures = new ArrayList<>();
        WebJobRepository.Counts counts = repository.counts();
        long queued = counts.queued();
        long readyBase = counts.readyBase();
        long readyConv = counts.readyConv();
        for (WebJobRepository.ItemRow r : repository.activeItems()) {
            Item i = item(r);
            if ("RUNNING".equals(i.status())) {
                phases.put(i.mediaFileId() * 2 + (WebPrepService.CONV.equals(i.kind()) ? 1 : 0), i.phase());
            } else {
                queue.add(i);
            }
        }
        for (WebJobRepository.ItemRow r : repository.failedItems()) {
            failures.add(item(r));
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

    private static Item item(WebJobRepository.ItemRow r) {
        Label l = new Label(r.episodeId(), r.animeId(), r.animeTitle(), r.seasonNumber(), r.episodeNumber());
        return new Item(r.kind(), r.mediaFileId(), l, r.status(), r.priority(), r.phase(), r.error(), r.attempts(),
                r.requestedAt(), r.nextAttemptAt(), r.blocked());
    }

    private Label label(long mediaFileId) throws SQLException {
        return repository.label(mediaFileId)
                .map(e -> new Label(e.episodeId(), e.animeId(), e.animeTitle(), e.seasonNumber(), e.episodeNumber()))
                .orElse(new Label(null, null, null, null, null));
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
        List<Long> episodes = repository.episodesOfAnime(animeId);
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
