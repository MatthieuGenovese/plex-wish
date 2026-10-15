package fr.plexwish.animeserver.cast;

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

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Administration de la distribution (ARCHITECTURE §19.5) : avancement, espace utilisé, animés sans distribution,
 * relance par animé, « Effacer toute la distribution » (données et images).
 */
@Path("/api/admin")
@RolesAllowed("ADMIN")
@Produces(MediaType.APPLICATION_JSON)
public class CastAdminResource {

    static final Set<String> FILTERS = Set.of("missing", "failed", "ok");

    public record Summary(boolean enabled, boolean running, boolean folderUsable, int maxRoles, long withAniList,
                          Map<String, Long> counts, long people, long characters, long roles,
                          Map<String, Long> images, long diskBytes, long estimatedBytes, boolean waitingForMetadata,
                          Instant pausedUntil, String lastUnavailable, Instant nextCheckAt) {
    }

    /** status : OK, NONE (pas de distribution chez AniList), EXCLUDED (adulte), FAILED, PENDING, NO_MATCH (pas d'appariement AniList). */
    public record Entry(long animeId, String title, String status, int roles, int seasons, Instant fetchedAt, String lastError) {
    }

    public record EntryPage(long total, int page, int size, List<Entry> items) {
    }

    @Inject
    CastRepository repository;
    @Inject
    CastService service;
    @Inject
    CastWorker worker;
    @Inject
    CastConfig config;

    @GET
    @Path("/cast/summary")
    public Summary summary() throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : List.of("OK", "PENDING", "NONE", "FAILED", "EXCLUDED")) {
            counts.put(s, 0L);
        }
        Map<String, Long> images = new LinkedHashMap<>();
        for (String s : List.of("OK", "PENDING", "FAILED")) {
            images.put(s, 0L);
        }
        counts.putAll(repository.stateCounts());
        long withAniList = counts.values().stream().mapToLong(Long::longValue).sum();
        images.putAll(repository.imageCounts());
        long[] diskAndAvg = repository.imageDisk();
        long disk = diskAndAvg[0];
        long avg = diskAndAvg[1];
        long[] totals = repository.totals();
        long people = totals[0];
        long characters = totals[1];
        long roles = totals[2];
        long totalImages = images.values().stream().mapToLong(Long::longValue).sum();
        // Estimation : images connues × taille moyenne, extrapolée aux animés pas encore traités.
        long done = Math.max(1, counts.get("OK"));
        long estimated = withAniList == 0 ? 0 : avg * totalImages * Math.max(withAniList, done) / done;
        return new Summary(config.enabled(), worker.running(), service.store().usable(), config.maxRoles(), withAniList,
                counts, people, characters, roles, images, disk, estimated, service.metadataBusy(),
                service.pausedUntil().orElse(null), service.lastUnavailable().orElse(null), worker.nextCheckAt().orElse(null));
    }

    /** {@code filter=missing} : animés sans distribution (pas d'appariement AniList compris). */
    @GET
    @Path("/cast")
    public EntryPage list(@QueryParam("filter") String filter, @QueryParam("q") String q,
                          @QueryParam("page") @DefaultValue("0") @Min(0) int page,
                          @QueryParam("size") @DefaultValue("50") @Min(1) @Max(200) int size) throws SQLException {
        if (filter != null && !FILTERS.contains(filter)) {
            throw new ApiException(400, "INVALID_FILTER", "Filtre inconnu : " + String.join(", ", FILTERS));
        }
        String like = q == null || q.isBlank() ? null : "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long total = repository.countEntries(filter, like);
        List<Entry> items = repository.entries(filter, like, size, (long) page * size).stream()
                .map(r -> new Entry(r.animeId(), r.title(), r.status(), r.roles(), r.seasons(), r.fetchedAt(), r.lastError()))
                .toList();
        return new EntryPage(total, page, size, items);
    }

    /** Redemander la distribution d'un animé (la tâche de fond s'en charge, après les métadonnées). */
    @POST
    @Path("/anime/{id}/cast/refresh")
    public Map<String, Object> refresh(@PathParam("id") long animeId) throws SQLException {
        if (!repository.animeExists(animeId)) {
            throw new ApiException(404, "ANIME_NOT_FOUND", "Animé introuvable");
        }
        if (!service.requeue(animeId)) {
            throw new ApiException(409, "NO_ANILIST_MATCH",
                    "Pas d'appariement AniList pour cet animé : le corriger d'abord dans l'onglet Métadonnées");
        }
        worker.wake();
        return Map.of("queued", true, "running", worker.running());
    }

    /** Garde-fou : efface toute la distribution, données et images. CAST_ENABLED=false empêche de la reprendre. */
    @POST
    @Path("/cast/purge")
    public Map<String, Object> purge(@QueryParam("confirm") @DefaultValue("false") boolean confirm) throws SQLException {
        if (!confirm) {
            throw new ApiException(400, "CONFIRMATION_REQUIRED", "Effacer toute la distribution : confirmer avec confirm=true");
        }
        return Map.of("purged", service.purge(), "running", worker.running());
    }
}
