package fr.plexwish.animeserver.metadata;

import java.io.UncheckedIOException;
import java.util.Optional;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.metadata.MetadataProvider.Candidate;
import fr.plexwish.animeserver.metadata.MetadataProvider.ProviderUnavailableException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Administration des métadonnées (ARCHITECTURE §15.4) : état de la tâche, liste des appariements (non appariés et
 * douteux d'abord), prévisualisation d'une fiche AniList, appariement manuel verrouillé (409 + confirmation
 * {@code replace=true} s'il remplace une fiche existante ou si la fiche sert déjà à un autre animé),
 * déverrouillage, relance.
 */
@Path("/api/admin")
@RolesAllowed("ADMIN")
@Produces(MediaType.APPLICATION_JSON)
public class MetadataAdminResource {

    static final Set<String> STATUSES = Set.of("PENDING", "MATCHED", "DOUBTFUL", "UNMATCHED", "MANUAL");

    public record Summary(boolean enabled, String provider, Map<String, Long> counts, long total,
                          Instant pausedUntil, String lastUnavailable, long estimatedMinutesLeft, Instant nextCheckAt) {
    }

    public record Entry(long animeId, String title, String status, String reason, Double score, boolean locked,
                        String providerId, String matchedTitle, Integer year, String posterUrl, String metadataUrl,
                        List<Map<String, Object>> candidates, String lastError, Instant updatedAt, String updatedBy) {
    }

    public record EntryPage(long total, int page, int size, List<Entry> items) {
    }

    /** Fiche proposée ou actuelle, pour la confirmation. */
    public record Sheet(String providerId, String title, String romaji, Integer year, String format, Integer episodes,
                        String synopsis, String posterUrl, String siteUrl) {
        static Sheet of(Candidate c) {
            return new Sheet(c.providerId(), c.displayTitle(), c.romaji(), c.year(), c.format(), c.episodes(), c.synopsis(),
                    c.posterUrl(), c.siteUrl());
        }
    }

    public record AnimeRef(long id, String title) {
    }

    /** 409 : la correction remplacerait une fiche, ou la fiche sert déjà à d'autres animés. Rien n'a été modifié. */
    public record MetadataConflict(int status, String error, String message, AnimeRef anime, Sheet current, Sheet proposed,
                                   List<AnimeRef> otherAnime) {
    }

    /** {@code providerId} null : « aucune fiche » (l'animé reste sans métadonnées, verrouillé). */
    public record ManualRequest(@Pattern(regexp = "\\d{1,9}", message = "identifiant AniList numérique") String providerId) {
    }

    @Inject
    MetadataRepository repository;
    @Inject
    fr.plexwish.animeserver.common.db.Db db;
    @Inject
    MetadataProvider provider;
    @Inject
    MetadataService service;
    @Inject
    MetadataWorker worker;
    @Inject
    MetadataConfig config;
    @Inject
    ObjectMapper json;
    @Inject
    JsonWebToken jwt;
    @Inject
    fr.plexwish.animeserver.tmdb.TmdbWorker tmdbWorker;
    @Inject
    fr.plexwish.animeserver.poster.PosterWorker posterWorker;

    @GET
    @Path("/metadata/summary")
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
        long pending = counts.get("PENDING");
        long minutes = (long) Math.ceil(pending * config.minInterval().toMillis() / 60_000.0);
        return new Summary(worker.enabled(), provider.displayName(), counts, total, service.pausedUntil().orElse(null),
                service.lastUnavailable().orElse(null), minutes, worker.nextCheckAt().orElse(null));
    }

    @GET
    @Path("/metadata")
    public EntryPage list(@QueryParam("status") String status, @QueryParam("q") String q,
                          @QueryParam("page") @DefaultValue("0") @Min(0) int page,
                          @QueryParam("size") @DefaultValue("50") @Min(1) @Max(200) int size) throws SQLException, IOException {
        if (status != null && !STATUSES.contains(status)) {
            throw new ApiException(400, "INVALID_STATUS", "Statut inconnu : " + String.join(", ", STATUSES));
        }
        String like = q == null || q.isBlank() ? null
                : "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long total = repository.countEntries(status, like);
        List<Entry> items = new ArrayList<>();
        for (MetadataRepository.EntryRow r : repository.entries(status, like, size, (long) page * size)) {
            items.add(entry(r));
        }
        return new EntryPage(total, page, size, items);
    }

    /** Fiche AniList à partir de son identifiant, avant de l'appliquer. */
    @GET
    @Path("/anime/{id}/metadata/preview")
    public Sheet preview(@PathParam("id") long animeId, @QueryParam("providerId") String providerId) throws SQLException {
        animeTitle(animeId);
        if (providerId == null || !providerId.matches("\\d{1,9}")) {
            throw new ApiException(400, "INVALID_PROVIDER_ID", "Identifiant AniList numérique attendu");
        }
        return Sheet.of(fetch(providerId));
    }

    /**
     * Appariement manuel, verrouillé : jamais écrasé par la tâche automatique ni par un rescan.
     * 409 {@code METADATA_CONFLICT} (rien n'est modifié) s'il remplace une fiche existante différente, ou si la fiche
     * sert déjà à un autre animé ; {@code replace=true} confirme.
     */
    @PUT
    @Path("/anime/{id}/metadata")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setManual(@PathParam("id") long animeId, @QueryParam("replace") @DefaultValue("false") boolean replace,
                              @jakarta.validation.Valid ManualRequest request) throws SQLException, IOException {
        String title = animeTitle(animeId);
        String providerId = request == null ? null : request.providerId();
        Candidate proposed = providerId == null ? null : fetch(providerId);
        record Outcome(MetadataConflict conflict, boolean changed) {
        }
        Outcome outcome;
        try {
            outcome = db.inTransaction(c -> {
                try {
                    Sheet current = currentSheet(c, animeId);
                    List<AnimeRef> others = providerId == null ? List.of() : othersUsing(c, animeId, providerId);
                    boolean replacing = current != null && !current.providerId().equals(providerId);
                    if ((replacing || !others.isEmpty()) && !replace) {
                        return new Outcome(new MetadataConflict(409, "METADATA_CONFLICT", replacing
                                ? "Cet animé a déjà une fiche : confirmer le remplacement avec replace=true."
                                : "Cette fiche est déjà utilisée par un autre animé : confirmer avec replace=true.",
                                new AnimeRef(animeId, title), current, proposed == null ? null : Sheet.of(proposed), others), false);
                    }
                    repository.saveManual(c, animeId, provider.id(), providerId,
                            proposed == null ? null : withChosen(c, animeId, proposed), jwt.getName());
                    service.applyToAnime(c, animeId, title, proposed);
                    boolean changed = current == null || !current.providerId().equals(providerId);
                    if (changed) {
                        // Une fiche TMDB trouvée automatiquement l'a été avec les titres de l'ancienne fiche : on la
                        // refait (synopsis français, affiche). Une fiche TMDB choisie à la main (verrouillée) est gardée.
                        repository.deleteAutomaticTmdb(c, animeId);
                    }
                    return new Outcome(null, changed);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        if (outcome.conflict() != null) {
            return Response.status(409).type(MediaType.APPLICATION_JSON).entity(outcome.conflict()).build();
        }
        if (outcome.changed()) {
            tmdbWorker.wake();
        }
        // Nouvelle affiche à télécharger : sans attendre le prochain passage de la tâche.
        posterWorker.wake();
        return Response.ok(entry(animeId)).build();
    }

    /** Retire le verrou : la tâche automatique refait l'appariement. */
    @DELETE
    @Path("/anime/{id}/metadata")
    public Response unlock(@PathParam("id") long animeId) throws SQLException {
        animeTitle(animeId);
        if (repository.unlock(animeId, jwt.getName()) == 0) {
            throw new ApiException(404, "NOT_LOCKED", "Pas de correction manuelle pour cet animé");
        }
        worker.wake();
        return Response.noContent().build();
    }

    /** Relance l'appariement automatique des animés d'un statut (non appariés ou douteux). Les verrouillés ne bougent pas. */
    @POST
    @Path("/metadata/requeue")
    public Map<String, Integer> requeue(@QueryParam("status") String status) throws SQLException {
        if (!Set.of("UNMATCHED", "DOUBTFUL", "MATCHED").contains(status)) {
            throw new ApiException(400, "INVALID_STATUS", "status doit valoir UNMATCHED, DOUBTFUL ou MATCHED");
        }
        int n = repository.requeue(status, jwt.getName());
        worker.wake();
        return Map.of("requeued", n);
    }

    // --- Utilitaires --------------------------------------------------------------------------------

    private Candidate fetch(String providerId) {
        try {
            return provider.byId(providerId).orElseThrow(() -> new ApiException(404, "PROVIDER_ENTRY_NOT_FOUND",
                    "Aucune fiche " + provider.displayName() + " avec l'identifiant " + providerId));
        } catch (ProviderUnavailableException e) {
            throw new ApiException(503, "METADATA_PROVIDER_UNAVAILABLE",
                    provider.displayName() + " est indisponible pour le moment, réessayez dans quelques minutes (" + e.getMessage() + ")");
        }
    }

    private String animeTitle(long animeId) throws SQLException {
        return repository.titleOf(animeId).orElseThrow(() -> new ApiException(404, "ANIME_NOT_FOUND", "Animé introuvable"));
    }

    /** Fiche actuellement appliquée (null si aucune). */
    private Sheet currentSheet(Connection c, long animeId) throws SQLException, IOException {
        Optional<MetadataRepository.CurrentSheet> row = repository.currentSheetForUpdate(c, animeId);
        if (row.isEmpty()) {
            return null;
        }
        MetadataRepository.CurrentSheet r = row.get();
        String id = r.providerId();
        List<Map<String, Object>> candidates = candidates(r.candidatesJson());
        Map<String, Object> known = candidates.stream().filter(m -> id.equals(m.get("providerId"))).findFirst().orElse(Map.of());
        return new Sheet(id, matchedTitle(id, candidates, r.alternativeTitle(), r.title()), (String) known.get("romaji"),
                r.year(), (String) known.get("format"), (Integer) known.get("episodes"), r.synopsis(),
                r.posterUrl(), r.metadataUrl());
    }

    private List<AnimeRef> othersUsing(Connection c, long animeId, String providerId) throws SQLException {
        return repository.othersUsing(c, animeId, providerId).stream().map(a -> new AnimeRef(a.id(), a.title())).toList();
    }

    /** Candidats gardés, avec la fiche choisie en tête (pour retrouver son titre plus tard). */
    private String withChosen(Connection c, long animeId, Candidate chosen) throws SQLException, IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(MetadataService.candidateMap(chosen));
        Optional<String> kept = repository.candidatesJson(c, animeId);
        if (kept.isPresent()) {
            for (Map<String, Object> m : candidates(kept.get())) {
                if (!chosen.providerId().equals(m.get("providerId"))) {
                    list.add(m);
                }
            }
        }
        return json.writeValueAsString(list);
    }

    private Entry entry(long animeId) throws SQLException, IOException {
        return entry(repository.entry(animeId).orElseThrow());
    }

    private Entry entry(MetadataRepository.EntryRow r) throws IOException {
        List<Map<String, Object>> candidates = candidates(r.candidatesJson());
        return new Entry(r.animeId(), r.title(), r.status(), r.reason(), r.score() == null ? null : r.score().doubleValue(),
                r.locked(), r.providerId(), matchedTitle(r.providerId(), candidates, r.alternativeTitle(), r.title()), r.year(),
                r.posterUrl(), r.metadataUrl(), candidates, r.lastError(), r.updatedAt(), r.updatedBy());
    }

    /** Titre de la fiche appliquée : celui du candidat retenu, sinon le titre alternatif, sinon le nom du dossier. */
    private static String matchedTitle(String providerId, List<Map<String, Object>> candidates, String alternative, String folder) {
        if (providerId == null) {
            return null;
        }
        return candidates.stream().filter(m -> providerId.equals(m.get("providerId"))).map(m -> (String) m.get("title"))
                .findFirst().orElse(alternative != null ? alternative : folder);
    }

    private List<Map<String, Object>> candidates(String raw) throws IOException {
        return raw == null ? List.of() : json.readValue(raw, new TypeReference<>() {
        });
    }
}
