package fr.plexwish.animeserver.poster;

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
import java.text.Normalizer;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Administration des affiches (ARCHITECTURE §17.5) : affiches locales, distantes, absentes, en échec ; espace disque
 * utilisé et estimé ; retéléchargement d'une affiche.
 */
@Path("/api/admin")
@RolesAllowed("ADMIN")
@Produces(MediaType.APPLICATION_JSON)
public class PosterAdminResource {

    static final Set<String> FILTERS = Set.of("local", "remote", "missing", "failed");

    public record Summary(boolean enabled, boolean folderUsable, String folder, long total, long local, long localTmdb,
                          long localAniList, long remote, long missing, long failed, long diskBytes, long averageBytes,
                          long estimatedBytes, long freeBytes, Instant pausedUntil, String lastUnavailable,
                          Instant nextCheckAt) {
    }

    /** state : LOCAL (fichier sur le NAS), REMOTE (chargée depuis sa source), MISSING (aucune affiche). */
    public record Entry(long animeId, String title, String state, String provider, String sourceUrl, String posterUrl,
                        boolean failed, String lastError, Long bytes, Instant fetchedAt) {
    }

    public record EntryPage(long total, int page, int size, List<Entry> items) {
    }

    @Inject
    PosterService service;
    @Inject
    PosterStore store;
    @Inject
    PosterConfig config;
    @Inject
    PosterWorker worker;

    private boolean local(PosterService.State s) {
        return s.relative() != null && s.localAllowed() && store.existing(s.relative()).isPresent();
    }

    private String state(PosterService.State s) {
        return local(s) ? "LOCAL" : s.src() != null ? "REMOTE" : "MISSING";
    }

    @GET
    @Path("/posters/summary")
    public Summary summary() throws SQLException {
        List<PosterService.State> states = service.states();
        long local = 0, tmdb = 0, anilist = 0, remote = 0, missing = 0, failed = 0, withSource = 0;
        long disk = 0;
        Set<String> counted = new HashSet<>();
        for (PosterService.State s : states) {
            if (s.src() != null) {
                withSource++;
            }
            if (s.failed()) {
                failed++;
            }
            switch (state(s)) {
                case "LOCAL" -> {
                    local++;
                    if ("TMDB".equals(s.localProvider())) tmdb++; else anilist++;
                    if (counted.add(s.relative()) && s.bytes() != null) {
                        disk += s.bytes();
                    }
                }
                case "REMOTE" -> remote++;
                default -> missing++;
            }
        }
        long average = counted.isEmpty() ? 0 : disk / counted.size();
        return new Summary(config.enabled(), store.usable(), store.root().toString(), states.size(), local, tmdb, anilist,
                remote, missing, failed, disk, average, average * withSource, store.usableSpace(),
                service.pausedUntil().orElse(null), service.lastUnavailable().orElse(null), worker.nextCheckAt().orElse(null));
    }

    @GET
    @Path("/posters")
    public EntryPage list(@QueryParam("filter") String filter, @QueryParam("q") String q,
                          @QueryParam("page") @DefaultValue("0") @Min(0) int page,
                          @QueryParam("size") @DefaultValue("50") @Min(1) @Max(200) int size) throws SQLException {
        if (filter != null && !FILTERS.contains(filter)) {
            throw new ApiException(400, "INVALID_FILTER", "Filtre inconnu : " + String.join(", ", FILTERS));
        }
        String needle = q == null || q.isBlank() ? null : fold(q.trim());
        List<PosterService.State> matching = service.states().stream()
                .filter(s -> needle == null || fold(s.title()).contains(needle))
                .filter(s -> filter == null || ("failed".equals(filter) ? s.failed() : state(s).equalsIgnoreCase(filter)))
                .toList();
        List<PosterService.State> slice = matching.stream().skip((long) page * size).limit(size).toList();
        Map<Long, PosterService.Urls> urls = service.urls(slice.stream().map(PosterService.State::animeId).toList());
        List<Entry> items = slice.stream().map(s -> {
            PosterService.Urls u = urls.get(s.animeId());
            boolean isLocal = local(s);
            return new Entry(s.animeId(), s.title(), state(s), isLocal ? s.localProvider() : s.provider(), s.src(),
                    u == null ? null : u.small(), s.failed(), s.lastError(), isLocal ? s.bytes() : null,
                    isLocal ? s.fetchedAt() : null);
        }).toList();
        return new EntryPage(matching.size(), page, size, items);
    }

    /** Retélécharger l'affiche d'un animé (oublie les échecs ; l'affiche actuelle reste servie en attendant). */
    @POST
    @Path("/anime/{id}/poster/redownload")
    public Map<String, Object> redownload(@PathParam("id") long animeId) throws SQLException {
        PosterService.State s = service.states().stream().filter(x -> x.animeId() == animeId).findFirst()
                .orElseThrow(() -> new ApiException(404, "ANIME_NOT_FOUND", "Animé introuvable"));
        if (s.src() == null) {
            throw new ApiException(409, "NO_POSTER_SOURCE", "Aucune affiche connue pour cet animé (ni TMDB ni AniList)");
        }
        service.requeue(animeId);
        worker.wake();
        return Map.of("queued", true, "running", worker.running());
    }

    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
