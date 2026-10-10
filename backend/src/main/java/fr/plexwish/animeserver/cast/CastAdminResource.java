package fr.plexwish.animeserver.cast;

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
    AgroalDataSource dataSource;
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
        long withAniList;
        long disk;
        long avg;
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT coalesce(st.status, 'PENDING'), count(*) FROM anime a LEFT JOIN anime_cast_state st ON st.anime_id = a.id
                    WHERE a.metadata_provider = 'ANILIST' AND a.metadata_provider_id IS NOT NULL GROUP BY 1""");
                 ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString(1), rs.getLong(2));
                }
            }
            withAniList = counts.values().stream().mapToLong(Long::longValue).sum();
            try (PreparedStatement st = c.prepareStatement("SELECT status, count(*) FROM cast_image GROUP BY 1");
                 ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    images.put(rs.getString(1), rs.getLong(2));
                }
            }
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT coalesce(sum(bytes), 0), coalesce(avg(bytes), 0)::bigint
                    FROM (SELECT DISTINCT ON (sha256) bytes FROM cast_image WHERE relative_path IS NOT NULL) x""");
                 ResultSet rs = st.executeQuery()) {
                rs.next();
                disk = rs.getLong(1);
                avg = rs.getLong(2);
            }
            long people = count(c, "SELECT count(*) FROM person");
            long characters = count(c, "SELECT count(*) FROM cast_character");
            long roles = count(c, "SELECT count(*) FROM anime_cast");
            long totalImages = images.values().stream().mapToLong(Long::longValue).sum();
            // Estimation : images connues × taille moyenne, extrapolée aux animés pas encore traités.
            long done = Math.max(1, counts.get("OK"));
            long estimated = withAniList == 0 ? 0 : avg * totalImages * Math.max(withAniList, done) / done;
            return new Summary(config.enabled(), worker.running(), service.store().usable(), config.maxRoles(), withAniList,
                    counts, people, characters, roles, images, disk, estimated, service.metadataBusy(),
                    service.pausedUntil().orElse(null), service.lastUnavailable().orElse(null), worker.nextCheckAt().orElse(null));
        }
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
        String status = """
                CASE WHEN a.metadata_provider IS DISTINCT FROM 'ANILIST' OR a.metadata_provider_id IS NULL THEN 'NO_MATCH'
                     ELSE coalesce(st.status, 'PENDING') END""";
        String where = " WHERE (?::text IS NULL OR lower(unaccent(a.title)) LIKE lower(unaccent(?)) ESCAPE '!')"
                + switch (filter == null ? "" : filter) {
                    case "missing" -> " AND (" + status + ") <> 'OK'";
                    case "failed" -> " AND st.status = 'FAILED'";
                    case "ok" -> " AND st.status = 'OK'";
                    default -> "";
                };
        String like = q == null || q.isBlank() ? null : "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String from = " FROM anime a LEFT JOIN anime_cast_state st ON st.anime_id = a.id";
        try (Connection c = dataSource.getConnection()) {
            long total;
            try (PreparedStatement st = c.prepareStatement("SELECT count(*)" + from + where)) {
                st.setString(1, like);
                st.setString(2, like);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            List<Entry> items = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement("SELECT a.id, a.title, " + status
                    + ", coalesce(st.roles, 0), coalesce(st.seasons, 0), st.fetched_at, st.last_error" + from + where
                    + " ORDER BY lower(a.title), a.id LIMIT ? OFFSET ?")) {
                st.setString(1, like);
                st.setString(2, like);
                st.setInt(3, size);
                st.setLong(4, (long) page * size);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        OffsetDateTime f = rs.getObject(6, OffsetDateTime.class);
                        items.add(new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                                f == null ? null : f.toInstant(), rs.getString(7)));
                    }
                }
            }
            return new EntryPage(total, page, size, items);
        }
    }

    /** Redemander la distribution d'un animé (la tâche de fond s'en charge, après les métadonnées). */
    @POST
    @Path("/anime/{id}/cast/refresh")
    public Map<String, Object> refresh(@PathParam("id") long animeId) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement("SELECT 1 FROM anime WHERE id = ?")) {
            st.setLong(1, animeId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(404, "ANIME_NOT_FOUND", "Animé introuvable");
                }
            }
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

    private static long count(Connection c, String sql) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql); ResultSet rs = st.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
