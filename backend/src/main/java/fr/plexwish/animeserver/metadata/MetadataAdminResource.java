package fr.plexwish.animeserver.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.metadata.MetadataProvider.Candidate;
import fr.plexwish.animeserver.metadata.MetadataProvider.ProviderUnavailableException;
import io.agroal.api.AgroalDataSource;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
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
    AgroalDataSource dataSource;
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
        long total;
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT coalesce(m.status, 'PENDING'), count(*) FROM anime a
                     LEFT JOIN anime_metadata_match m ON m.anime_id = a.id GROUP BY 1""");
             ResultSet rs = st.executeQuery()) {
            total = 0;
            while (rs.next()) {
                counts.put(rs.getString(1), rs.getLong(2));
                total += rs.getLong(2);
            }
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
        String where = " WHERE (?::text IS NULL OR coalesce(m.status, 'PENDING') = ?)"
                + " AND (?::text IS NULL OR lower(unaccent(a.title)) LIKE lower(unaccent(?)) ESCAPE '!')";
        String like = q == null || q.isBlank() ? null
                : "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        try (Connection c = dataSource.getConnection()) {
            long total;
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT count(*) FROM anime a LEFT JOIN anime_metadata_match m ON m.anime_id = a.id" + where)) {
                bind(st, status, like);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            List<Entry> items = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT a.id, a.title, coalesce(m.status, 'PENDING'), m.reason, m.score, coalesce(m.locked, FALSE),
                           m.provider_id, a.year, a.poster_url, a.metadata_url, m.candidates::text, m.last_error,
                           m.updated_at, m.updated_by, a.alternative_title
                    FROM anime a LEFT JOIN anime_metadata_match m ON m.anime_id = a.id""" + where
                    + " ORDER BY lower(a.title), a.id LIMIT ? OFFSET ?")) {
                bind(st, status, like);
                st.setInt(5, size);
                st.setLong(6, (long) page * size);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        List<Map<String, Object>> candidates = candidates(rs.getString(11));
                        String providerId = rs.getString(7);
                        items.add(new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getObject(5) == null ? null : rs.getBigDecimal(5).doubleValue(), rs.getBoolean(6),
                                providerId, matchedTitle(providerId, candidates, rs.getString(15), rs.getString(2)),
                                (Integer) rs.getObject(8), rs.getString(9), rs.getString(10), candidates,
                                rs.getString(12), instant(rs, 13), rs.getString(14)));
                    }
                }
            }
            return new EntryPage(total, page, size, items);
        }
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
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                Sheet current = currentSheet(c, animeId);
                List<AnimeRef> others = providerId == null ? List.of() : othersUsing(c, animeId, providerId);
                boolean replacing = current != null && !current.providerId().equals(providerId);
                if ((replacing || !others.isEmpty()) && !replace) {
                    c.rollback();
                    return Response.status(409).type(MediaType.APPLICATION_JSON).entity(new MetadataConflict(409,
                            "METADATA_CONFLICT", replacing
                            ? "Cet animé a déjà une fiche : confirmer le remplacement avec replace=true."
                            : "Cette fiche est déjà utilisée par un autre animé : confirmer avec replace=true.",
                            new AnimeRef(animeId, title), current, proposed == null ? null : Sheet.of(proposed), others)).build();
                }
                try (PreparedStatement st = c.prepareStatement("""
                        INSERT INTO anime_metadata_match (anime_id, status, provider, provider_id, score, reason, candidates,
                                                          locked, attempts, next_attempt_at, last_error, updated_at, updated_by)
                        VALUES (?, 'MANUAL', ?, ?, NULL, NULL, ?::jsonb, TRUE, 0, NULL, NULL, now(), ?)
                        ON CONFLICT (anime_id) DO UPDATE SET status = 'MANUAL', provider = EXCLUDED.provider,
                            provider_id = EXCLUDED.provider_id, score = NULL, reason = NULL,
                            candidates = coalesce(EXCLUDED.candidates, anime_metadata_match.candidates), locked = TRUE,
                            attempts = 0, next_attempt_at = NULL, last_error = NULL, updated_at = now(),
                            updated_by = EXCLUDED.updated_by""")) {
                    st.setLong(1, animeId);
                    st.setString(2, provider.id());
                    st.setString(3, providerId);
                    st.setString(4, proposed == null ? null : withChosen(c, animeId, proposed));
                    st.setString(5, jwt.getName());
                    st.executeUpdate();
                }
                service.applyToAnime(c, animeId, title, proposed);
                boolean changed = current == null || !current.providerId().equals(providerId);
                if (changed) {
                    // Une fiche TMDB trouvée automatiquement l'a été avec les titres de l'ancienne fiche : on la
                    // refait (synopsis français, affiche). Une fiche TMDB choisie à la main (verrouillée) est gardée.
                    try (PreparedStatement st = c.prepareStatement("DELETE FROM anime_tmdb WHERE anime_id = ? AND NOT locked")) {
                        st.setLong(1, animeId);
                        st.executeUpdate();
                    }
                }
                c.commit();
                if (changed) {
                    tmdbWorker.wake();
                }
                // Nouvelle affiche à télécharger : sans attendre le prochain passage de la tâche.
                posterWorker.wake();
            } catch (SQLException | RuntimeException | IOException e) {
                c.rollback();
                throw e;
            }
        }
        return Response.ok(entry(animeId)).build();
    }

    /** Retire le verrou : la tâche automatique refait l'appariement. */
    @DELETE
    @Path("/anime/{id}/metadata")
    public Response unlock(@PathParam("id") long animeId) throws SQLException {
        animeTitle(animeId);
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE anime_metadata_match SET locked = FALSE, status = 'PENDING', attempts = 0, next_attempt_at = NULL,
                         updated_at = now(), updated_by = ? WHERE anime_id = ? AND locked""")) {
            st.setString(1, jwt.getName());
            st.setLong(2, animeId);
            if (st.executeUpdate() == 0) {
                throw new ApiException(404, "NOT_LOCKED", "Pas de correction manuelle pour cet animé");
            }
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
        int n;
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE anime_metadata_match SET status = 'PENDING', attempts = 0, next_attempt_at = NULL,
                         updated_at = now(), updated_by = ? WHERE status = ? AND NOT locked""")) {
            st.setString(1, jwt.getName());
            st.setString(2, status);
            n = st.executeUpdate();
        }
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
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT title FROM anime WHERE id = ?")) {
            st.setLong(1, animeId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(404, "ANIME_NOT_FOUND", "Animé introuvable");
                }
                return rs.getString(1);
            }
        }
    }

    /** Fiche actuellement appliquée (null si aucune). */
    private Sheet currentSheet(Connection c, long animeId) throws SQLException, IOException {
        try (PreparedStatement st = c.prepareStatement("""
                SELECT a.metadata_provider_id, a.year, a.poster_url, a.metadata_url, a.synopsis, m.candidates::text,
                       a.alternative_title, a.title
                FROM anime a LEFT JOIN anime_metadata_match m ON m.anime_id = a.id WHERE a.id = ? FOR UPDATE OF a""")) {
            st.setLong(1, animeId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next() || rs.getString(1) == null) {
                    return null;
                }
                String id = rs.getString(1);
                List<Map<String, Object>> candidates = candidates(rs.getString(6));
                Map<String, Object> known = candidates.stream().filter(m -> id.equals(m.get("providerId"))).findFirst().orElse(Map.of());
                return new Sheet(id, matchedTitle(id, candidates, rs.getString(7), rs.getString(8)), (String) known.get("romaji"),
                        (Integer) rs.getObject(2), (String) known.get("format"), (Integer) known.get("episodes"), rs.getString(5),
                        rs.getString(3), rs.getString(4));
            }
        }
    }

    private static List<AnimeRef> othersUsing(Connection c, long animeId, String providerId) throws SQLException {
        List<AnimeRef> list = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement(
                "SELECT id, title FROM anime WHERE metadata_provider_id = ? AND id <> ? ORDER BY lower(title)")) {
            st.setString(1, providerId);
            st.setLong(2, animeId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    list.add(new AnimeRef(rs.getLong(1), rs.getString(2)));
                }
            }
        }
        return list;
    }

    /** Candidats gardés, avec la fiche choisie en tête (pour retrouver son titre plus tard). */
    private String withChosen(Connection c, long animeId, Candidate chosen) throws SQLException, IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(MetadataService.candidateMap(chosen));
        try (PreparedStatement st = c.prepareStatement("SELECT candidates::text FROM anime_metadata_match WHERE anime_id = ?")) {
            st.setLong(1, animeId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    for (Map<String, Object> m : candidates(rs.getString(1))) {
                        if (!chosen.providerId().equals(m.get("providerId"))) {
                            list.add(m);
                        }
                    }
                }
            }
        }
        return json.writeValueAsString(list);
    }

    private Entry entry(long animeId) throws SQLException, IOException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT a.id, a.title, coalesce(m.status, 'PENDING'), m.reason, m.score, coalesce(m.locked, FALSE),
                            m.provider_id, a.year, a.poster_url, a.metadata_url, m.candidates::text, m.last_error,
                            m.updated_at, m.updated_by, a.alternative_title
                     FROM anime a LEFT JOIN anime_metadata_match m ON m.anime_id = a.id WHERE a.id = ?""")) {
            st.setLong(1, animeId);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                List<Map<String, Object>> candidates = candidates(rs.getString(11));
                return new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5) == null ? null : rs.getBigDecimal(5).doubleValue(), rs.getBoolean(6), rs.getString(7),
                        matchedTitle(rs.getString(7), candidates, rs.getString(15), rs.getString(2)), (Integer) rs.getObject(8),
                        rs.getString(9), rs.getString(10), candidates, rs.getString(12), instant(rs, 13), rs.getString(14));
            }
        }
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

    private static void bind(PreparedStatement st, String status, String like) throws SQLException {
        st.setString(1, status);
        st.setString(2, status);
        st.setString(3, like);
        st.setString(4, like);
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
