package fr.plexwish.animeserver.tmdb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.metadata.MetadataProvider.ProviderUnavailableException;
import fr.plexwish.animeserver.tmdb.TmdbClient.Result;
import io.agroal.api.AgroalDataSource;
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
    AgroalDataSource dataSource;
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
        long french;
        long due;
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement st = c.prepareStatement("SELECT coalesce(t.status, 'PENDING'), count(*) FROM anime a"
                    + " LEFT JOIN anime_tmdb t ON t.anime_id = a.id GROUP BY 1"); ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString(1), rs.getLong(2));
                    total += rs.getLong(2);
                }
            }
            french = count(c, "SELECT count(*) FROM anime_tmdb WHERE synopsis IS NOT NULL");
            due = count(c, "SELECT count(*) FROM anime_tmdb WHERE tmdb_id IS NOT NULL AND (fetched_at IS NULL"
                    + " OR fetched_at < now() - make_interval(secs => " + config.refreshAfter().toSeconds() + "))");
        }
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
        String where = " WHERE (?::text IS NULL OR coalesce(t.status, 'PENDING') = ?)"
                + " AND (?::text IS NULL OR lower(unaccent(a.title)) LIKE lower(unaccent(?)) ESCAPE '!')"
                + (noFrench ? " AND t.synopsis IS NULL" : "");
        String like = q == null || q.isBlank() ? null : "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        try (Connection c = dataSource.getConnection()) {
            long total;
            try (PreparedStatement st = c.prepareStatement("SELECT count(*) FROM anime a LEFT JOIN anime_tmdb t ON t.anime_id = a.id" + where)) {
                bind(st, status, like);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            List<Entry> items = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement(SELECT + where + " ORDER BY lower(a.title), a.id LIMIT ? OFFSET ?")) {
                bind(st, status, like);
                st.setInt(5, size);
                st.setLong(6, (long) page * size);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        items.add(entry(rs));
                    }
                }
            }
            return new EntryPage(total, page, size, items);
        }
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
        try (Connection c = dataSource.getConnection()) {
            Sheet current = currentSheet(c, animeId);
            boolean replacing = current != null && (none || !(current.type().equals(proposed.type()) && current.tmdbId() == proposed.id()));
            if (replacing && !replace) {
                return Response.status(409).type(MediaType.APPLICATION_JSON).entity(new TmdbConflict(409, "TMDB_CONFLICT",
                        "Cet animé a déjà une fiche TMDB : confirmer le remplacement avec replace=true.", animeId, title,
                        current, proposed == null ? null : Sheet.of(proposed))).build();
            }
            try (PreparedStatement st = c.prepareStatement("""
                    INSERT INTO anime_tmdb (anime_id, status, tmdb_type, tmdb_id, score, reason, candidates, locked, attempts,
                                            next_attempt_at, last_error, language, title, synopsis, poster_path, fetched_at,
                                            updated_at, updated_by)
                    VALUES (?, 'MANUAL', ?, ?, NULL, NULL, NULL, TRUE, 0, NULL, NULL, ?, ?, ?, ?, ?, now(), ?)
                    ON CONFLICT (anime_id) DO UPDATE SET status = 'MANUAL', tmdb_type = EXCLUDED.tmdb_type,
                        tmdb_id = EXCLUDED.tmdb_id, score = NULL, reason = NULL, locked = TRUE, attempts = 0,
                        next_attempt_at = NULL, last_error = NULL, language = EXCLUDED.language, title = EXCLUDED.title,
                        synopsis = EXCLUDED.synopsis, poster_path = EXCLUDED.poster_path, fetched_at = EXCLUDED.fetched_at,
                        updated_at = now(), updated_by = EXCLUDED.updated_by""")) {
                st.setLong(1, animeId);
                st.setString(2, proposed == null ? null : proposed.type());
                st.setObject(3, proposed == null ? null : proposed.id());
                service.bindFields(st, 4, proposed);
                st.setString(9, jwt.getName());
                st.executeUpdate();
            }
        }
        return Response.ok(entry(animeId)).build();
    }

    @DELETE
    @Path("/anime/{id}/tmdb")
    public Response unlock(@PathParam("id") long animeId) throws SQLException {
        animeTitle(animeId);
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("UPDATE anime_tmdb SET locked = FALSE, status = 'PENDING', attempts = 0,"
                     + " next_attempt_at = NULL, updated_at = now(), updated_by = ? WHERE anime_id = ? AND locked")) {
            st.setString(1, jwt.getName());
            st.setLong(2, animeId);
            if (st.executeUpdate() == 0) {
                throw new ApiException(404, "NOT_LOCKED", "Pas de correction manuelle TMDB pour cet animé");
            }
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
        int n;
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("UPDATE anime_tmdb SET status = 'PENDING', attempts = 0, next_attempt_at = NULL,"
                     + " updated_at = now(), updated_by = ? WHERE status = ? AND NOT locked")) {
            st.setString(1, jwt.getName());
            st.setString(2, status);
            n = st.executeUpdate();
        }
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
        int purged;
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement("DELETE FROM anime_tmdb")) {
            purged = st.executeUpdate();
        }
        // Affiches TMDB stockées sur le NAS : effacées aussi (fichiers compris).
        return Map.of("purged", purged, "postersPurged", posters.purgeTmdb());
    }

    // --- Utilitaires --------------------------------------------------------------------------------

    private static final String SELECT = """
            SELECT a.id, a.title, coalesce(t.status, 'PENDING'), t.reason, t.score, coalesce(t.locked, FALSE), t.tmdb_type,
                   t.tmdb_id, t.title, t.synopsis IS NOT NULL, t.fetched_at, t.candidates::text, t.last_error, t.updated_at,
                   t.updated_by
            FROM anime a LEFT JOIN anime_tmdb t ON t.anime_id = a.id""";

    private Entry entry(ResultSet rs) throws SQLException, IOException {
        String type = rs.getString(7);
        Long id = (Long) rs.getObject(8);
        return new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5) == null ? null : rs.getBigDecimal(5).doubleValue(), rs.getBoolean(6), type, id,
                rs.getString(9), rs.getBoolean(10), instant(rs, 11),
                type == null ? null : "https://www.themoviedb.org/" + type + "/" + id,
                rs.getString(12) == null ? List.of() : json.readValue(rs.getString(12), new TypeReference<>() {
                }), rs.getString(13), instant(rs, 14), rs.getString(15));
    }

    private Entry entry(long animeId) throws SQLException, IOException {
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(SELECT + " WHERE a.id = ?")) {
            st.setLong(1, animeId);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return entry(rs);
            }
        }
    }

    private Sheet currentSheet(Connection c, long animeId) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
                "SELECT tmdb_type, tmdb_id, title, synopsis FROM anime_tmdb WHERE anime_id = ? AND tmdb_id IS NOT NULL")) {
            st.setLong(1, animeId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String type = rs.getString(1);
                long id = rs.getLong(2);
                return new Sheet(type, id, rs.getString(3), null, null, rs.getString(4), true,
                        "https://www.themoviedb.org/" + type + "/" + id);
            }
        }
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
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement("SELECT title FROM anime WHERE id = ?")) {
            st.setLong(1, animeId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(404, "ANIME_NOT_FOUND", "Animé introuvable");
                }
                return rs.getString(1);
            }
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql); ResultSet rs = st.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
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
