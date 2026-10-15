package fr.plexwish.animeserver.tmdb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.metadata.MetadataProvider.ProviderUnavailableException;
import fr.plexwish.animeserver.tmdb.TmdbClient.Result;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Administration TMDB (ARCHITECTURE §16.6) : état, animés sans synopsis français, appariement manuel verrouillé
 * (identifiant TMDB, 409 + confirmation s'il remplace une fiche), déverrouillage, relance, purge (fin de licence).
 */
@Path("/api/admin")
@RolesAllowed("ADMIN")
@Produces(MediaType.APPLICATION_JSON)
public class TmdbAdminResource {

    static final Set<String> STATUSES = Set.of("PENDING", "MATCHED", "DOUBTFUL", "UNMATCHED", "MANUAL");

    public record Summary(boolean configured, Map<String, Long> counts, long total, long withFrenchSynopsis,
                          long refreshDue, Instant pausedUntil, String lastUnavailable) {
    }

    public record Entry(long animeId, String title, String status, String reason, Double score, boolean locked,
                        String tmdbType, Long tmdbId, String frenchTitle, boolean hasFrenchSynopsis, Instant fetchedAt,
                        String url, List<Map<String, Object>> candidates, String lastError, Instant updatedAt, String updatedBy) {
    }

    public record EntryPage(long total, int page, int size, List<Entry> items) {
    }

    public record Sheet(String type, long tmdbId, String name, String originalName, Integer year, String overview,
                        boolean animation, String url) {
        static Sheet of(Result r) {
            return new Sheet(r.type(), r.id(), r.name(), r.originalName(), r.year(), r.overview(), r.animation(), r.url());
        }
    }

    public record TmdbConflict(int status, String error, String message, long animeId, String animeTitle,
                               Sheet current, Sheet proposed) {
    }

    /** {@code tmdbId} null : « aucune fiche TMDB » (synopsis anglais d'AniList), verrouillé. */
    public record ManualRequest(@Pattern(regexp = "tv|movie") String type, @Min(1) Long tmdbId) {
    }

    @Inject
    TmdbRepository repository;
    @Inject
    TmdbClient client;
    @Inject
    TmdbService service;
    @Inject
    TmdbWorker worker;
    @Inject
    TmdbConfig config;
    @Inject
    TmdbCredentials credentials;
    @Inject
    ObjectMapper json;
    @Inject
    JsonWebToken jwt;
    @Inject
    fr.plexwish.animeserver.poster.PosterService posters;

    @GET
    @Path("/tmdb/summary")
    public Summary summary() throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : List.of("MATCHED", "DOUBTFUL", "UNMATCHED", "MANUAL", "PENDING")) {
            counts.put(s, 0L);
        }
        long total = 0;
        for (var e : repository.statusCounts().entrySet()) {
            counts.put(e.getKey(), e.getValue());
            total += e.getValue();
        }
        long french = repository.countWithFrenchSynopsis();
        long due = repository.countDueForRefresh(config.refreshAfter().toSeconds());
        return new Summary(credentials.configured(), counts, total, french, due, service.pausedUntil().orElse(null),
                service.lastUnavailable().orElse(null));
    }

    /** {@code noFrench=true} : animés sans synopsis français (non appariés, ou fiche TMDB sans traduction). */
    @GET
    @Path("/tmdb")
    public EntryPage list(@QueryParam("status") String status, @QueryParam("q") String q,
                          @QueryParam("noFrench") @DefaultValue("false") boolean noFrench,
                          @QueryParam("page") @DefaultValue("0") @Min(0) int page,
                          @QueryParam("size") @DefaultValue("50") @Min(1) @Max(200) int size) throws SQLException, IOException {
        if (status != null && !STATUSES.contains(status)) {
            throw new ApiException(400, "INVALID_STATUS", "Statut inconnu : " + String.join(", ", STATUSES));
        }
        String like = q == null || q.isBlank() ? null : "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long total = repository.countEntries(status, like, noFrench);
        List<Entry> items = new ArrayList<>();
        for (TmdbRepository.EntryRow r : repository.entries(status, like, noFrench, size, (long) page * size)) {
            items.add(entry(r));
        }
        return new EntryPage(total, page, size, items);
    }

    @GET
    @Path("/anime/{id}/tmdb/preview")
    public Sheet preview(@PathParam("id") long animeId, @QueryParam("type") String type, @QueryParam("tmdbId") Long tmdbId)
            throws SQLException {
        animeTitle(animeId);
        if (!"tv".equals(type) && !"movie".equals(type) || tmdbId == null || tmdbId < 1) {
            throw new ApiException(400, "INVALID_TMDB_ID", "type (tv ou movie) et identifiant TMDB attendus");
        }
        return Sheet.of(fetch(type, tmdbId));
    }

    /** Appariement manuel, verrouillé ; 409 {@code TMDB_CONFLICT} s'il remplace une autre fiche, sauf replace=true. */
    @PUT
    @Path("/anime/{id}/tmdb")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setManual(@PathParam("id") long animeId, @QueryParam("replace") @DefaultValue("false") boolean replace,
                              @Valid ManualRequest request) throws SQLException, IOException {
        String title = animeTitle(animeId);
        boolean none = request == null || request.tmdbId() == null;
        if (!none && request.type() == null) {
            throw new ApiException(400, "INVALID_TMDB_ID", "type (tv ou movie) attendu avec l'identifiant TMDB");
        }
        Result proposed = none ? null : fetch(request.type(), request.tmdbId());
        Sheet current = currentSheet(animeId);
        boolean replacing = current != null && (none || !(current.type().equals(proposed.type()) && current.tmdbId() == proposed.id()));
        if (replacing && !replace) {
            return Response.status(409).type(MediaType.APPLICATION_JSON).entity(new TmdbConflict(409, "TMDB_CONFLICT",
                    "Cet animé a déjà une fiche TMDB : confirmer le remplacement avec replace=true.", animeId, title,
                    current, proposed == null ? null : Sheet.of(proposed))).build();
        }
        repository.saveManual(animeId, proposed == null ? null : proposed.type(), proposed == null ? null : proposed.id(),
                service.fields(proposed), jwt.getName());
        return Response.ok(entry(animeId)).build();
    }

    @DELETE
    @Path("/anime/{id}/tmdb")
    public Response unlock(@PathParam("id") long animeId) throws SQLException {
        animeTitle(animeId);
        if (repository.unlock(animeId, jwt.getName()) == 0) {
            throw new ApiException(404, "NOT_LOCKED", "Pas de correction manuelle TMDB pour cet animé");
        }
        worker.wake();
        return Response.noContent().build();
    }

    @POST
    @Path("/tmdb/requeue")
    public Map<String, Integer> requeue(@QueryParam("status") String status) throws SQLException {
        if (!Set.of("UNMATCHED", "DOUBTFUL", "MATCHED").contains(status)) {
            throw new ApiException(400, "INVALID_STATUS", "status doit valoir UNMATCHED, DOUBTFUL ou MATCHED");
        }
        int n = repository.requeue(status, jwt.getName());
        worker.wake();
        return Map.of("requeued", n);
    }

    /** Conditions de l'API TMDB (§1.D) : en fin de licence, tout effacer. Corrections manuelles comprises. */
    @POST
    @Path("/tmdb/purge")
    public Map<String, Integer> purge(@QueryParam("confirm") @DefaultValue("false") boolean confirm) throws SQLException {
        if (!confirm) {
            throw new ApiException(400, "CONFIRMATION_REQUIRED", "Purge de toutes les données TMDB : confirmer avec confirm=true");
        }
        int purged = repository.deleteAll();
        // Affiches TMDB stockées sur le NAS : effacées aussi (fichiers compris).
        return Map.of("purged", purged, "postersPurged", posters.purgeTmdb());
    }

    // --- Utilitaires --------------------------------------------------------------------------------

    private Entry entry(TmdbRepository.EntryRow r) throws IOException {
        String type = r.tmdbType();
        Long id = r.tmdbId();
        return new Entry(r.animeId(), r.title(), r.status(), r.reason(), r.score() == null ? null : r.score().doubleValue(),
                r.locked(), type, id, r.tmdbTitle(), r.hasFrenchSynopsis(), r.fetchedAt(),
                type == null ? null : "https://www.themoviedb.org/" + type + "/" + id,
                r.candidatesJson() == null ? List.of() : json.readValue(r.candidatesJson(), new TypeReference<>() {
                }), r.lastError(), r.updatedAt(), r.updatedBy());
    }

    private Entry entry(long animeId) throws SQLException, IOException {
        return entry(repository.entry(animeId).orElseThrow());
    }

    private Sheet currentSheet(long animeId) throws SQLException {
        return repository.currentSheet(animeId).map(r -> new Sheet(r.type(), r.id(), r.title(), null, null, r.synopsis(), true,
                "https://www.themoviedb.org/" + r.type() + "/" + r.id())).orElse(null);
    }

    private Result fetch(String type, long id) {
        if (!credentials.configured()) {
            throw new ApiException(503, "TMDB_NOT_CONFIGURED", "TMDB n'est pas configuré (TMDB_READ_TOKEN absent)");
        }
        try {
            return client.details(type, id).orElseThrow(() -> new ApiException(404, "PROVIDER_ENTRY_NOT_FOUND",
                    "Aucune fiche TMDB " + type + "/" + id));
        } catch (ProviderUnavailableException e) {
            throw new ApiException(503, "METADATA_PROVIDER_UNAVAILABLE",
                    "TMDB est indisponible pour le moment, réessayez dans quelques minutes (" + e.getMessage() + ")");
        }
    }

    private String animeTitle(long animeId) throws SQLException {
        return repository.titleOf(animeId).orElseThrow(() -> new ApiException(404, "ANIME_NOT_FOUND", "Animé introuvable"));
    }
}
