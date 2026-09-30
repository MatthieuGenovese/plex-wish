package fr.plexwish.animeserver.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.library.scan.ScanService;
import io.agroal.api.AgroalDataSource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
 * Administration de la bibliothèque : scan, rapport (résumé + liste filtrable), corrections manuelles.
 * Le rapport montre les chemins relatifs (l'admin doit savoir de quel fichier il s'agit) ; les corrections
 * désignent toujours un fichier par son id en base, jamais par un chemin fourni par le client.
 */
@Path("/api/admin/library")
@RolesAllowed("ADMIN")
@Produces(MediaType.APPLICATION_JSON)
public class LibraryAdminResource {

    public static final Set<String> CATEGORIES = Set.of(
            "UNRESOLVED", "DUPLICATE", "MULTI_EPISODE", "DECIMAL_EPISODE", "SEASON_MISMATCH", "MISSING", "UNREADABLE");

    public record ScanStarted(long scanId) {
    }

    public record ScanReport(long id, String status, Instant startedAt, Instant finishedAt, String triggeredBy,
                             String failureReason, JsonNode stats, Map<String, Long> issueCounts) {
    }

    public record IssueDto(long id, Long mediaFileId, String category, String animeTitle, String relativePath, String detail) {
    }

    public record IssuePage(long scanId, long total, int page, int size, List<IssueDto> items) {
    }

    /** EPISODE : animeTitle, seasonNumber, episodeNumber obligatoires. EXTRA / IGNORE : aucun champ. */
    public record OverrideRequest(
            @NotNull @Pattern(regexp = "EPISODE|EXTRA|IGNORE") String action,
            @Size(min = 1, max = 300) String animeTitle,
            @Min(0) @Max(99) Integer seasonNumber,
            @Min(0) @Max(9999) Integer episodeNumber) {
    }

    public record OverrideDto(Long mediaFileId, String relativePath, String action, String animeTitle,
                              Integer seasonNumber, Integer episodeNumber, String createdBy, Instant createdAt) {
    }

    @Inject
    ScanService scans;
    @Inject
    AgroalDataSource dataSource;
    @Inject
    ObjectMapper json;
    @Inject
    JsonWebToken jwt;

    /** Lance un scan en tâche de fond : 202 + id ; 409 si un scan tourne déjà. */
    @POST
    @Path("/scan")
    public Response scan() {
        return Response.accepted(new ScanStarted(scans.start(jwt.getName()))).build();
    }

    /** Dernier scan (ou celui demandé) : statut, compteurs par catégorie, nombre de problèmes par catégorie. */
    @GET
    @Path("/scan-report")
    public ScanReport report(@QueryParam("scanId") Long scanId) throws SQLException, IOException {
        try (Connection c = dataSource.getConnection()) {
            long id = scanId != null ? scanId : latestScanId(c);
            ScanReport report = null;
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT id, status, started_at, finished_at, triggered_by, failure_reason, stats::text
                    FROM scan_run WHERE id = ?""")) {
                st.setLong(1, id);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw new ApiException(404, "SCAN_NOT_FOUND", "Scan introuvable");
                    }
                    String stats = rs.getString(7);
                    report = new ScanReport(rs.getLong(1), rs.getString(2), instant(rs, 3), instant(rs, 4), rs.getString(5),
                            rs.getString(6), stats == null ? null : json.readTree(stats), new LinkedHashMap<>());
                }
            }
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT category, count(*) FROM scan_issue WHERE scan_run_id = ? GROUP BY category ORDER BY category")) {
                st.setLong(1, id);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        report.issueCounts().put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
            return report;
        }
    }

    /** Liste filtrable des fichiers signalés (par catégorie et par animé), paginée. */
    @GET
    @Path("/issues")
    public IssuePage issues(@QueryParam("scanId") Long scanId,
                            @QueryParam("category") String category,
                            @QueryParam("anime") String anime,
                            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
                            @QueryParam("size") @DefaultValue("100") @Min(1) @Max(500) int size) throws SQLException {
        if (category != null && !CATEGORIES.contains(category)) {
            throw new ApiException(400, "INVALID_CATEGORY", "Catégorie inconnue : " + String.join(", ", CATEGORIES));
        }
        try (Connection c = dataSource.getConnection()) {
            long id = scanId != null ? scanId : latestScanId(c);
            String where = " WHERE scan_run_id = ? AND (?::text IS NULL OR category = ?)"
                    + " AND (?::text IS NULL OR anime_title ILIKE '%' || ? || '%')";
            long total;
            try (PreparedStatement st = c.prepareStatement("SELECT count(*) FROM scan_issue" + where)) {
                bindFilters(st, id, category, anime);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            List<IssueDto> items = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT id, media_file_id, category, anime_title, relative_path, detail FROM scan_issue" + where
                            + " ORDER BY category, relative_path LIMIT ? OFFSET ?")) {
                bindFilters(st, id, category, anime);
                st.setInt(6, size);
                st.setLong(7, (long) page * size);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        items.add(new IssueDto(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6)));
                    }
                }
            }
            return new IssuePage(id, total, page, size, items);
        }
    }

    /** Correction manuelle d'un fichier, appliquée au prochain scan et jamais écrasée par un scan (§7.7). */
    @PUT
    @Path("/files/{id}/override")
    @Consumes(MediaType.APPLICATION_JSON)
    public OverrideDto setOverride(@PathParam("id") long mediaFileId, @Valid @NotNull OverrideRequest request) throws SQLException {
        boolean episode = "EPISODE".equals(request.action());
        if (episode && (request.animeTitle() == null || request.animeTitle().isBlank()
                || request.seasonNumber() == null || request.episodeNumber() == null)) {
            throw new ApiException(400, "INCOMPLETE_OVERRIDE", "EPISODE demande animeTitle, seasonNumber et episodeNumber");
        }
        try (Connection c = dataSource.getConnection()) {
            String path = pathOf(c, mediaFileId);
            try (PreparedStatement st = c.prepareStatement("""
                    INSERT INTO media_file_override (relative_path, action, anime_title, season_number, episode_number, created_by)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (relative_path) DO UPDATE SET action = EXCLUDED.action, anime_title = EXCLUDED.anime_title,
                        season_number = EXCLUDED.season_number, episode_number = EXCLUDED.episode_number,
                        created_by = EXCLUDED.created_by, created_at = now()""")) {
                st.setString(1, path);
                st.setString(2, request.action());
                st.setString(3, episode ? request.animeTitle().trim() : null);
                st.setObject(4, episode ? request.seasonNumber() : null);
                st.setObject(5, episode ? request.episodeNumber() : null);
                st.setString(6, jwt.getName());
                st.executeUpdate();
            }
            return overrides(c, mediaFileId).get(0);
        }
    }

    @DELETE
    @Path("/files/{id}/override")
    public Response deleteOverride(@PathParam("id") long mediaFileId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("DELETE FROM media_file_override WHERE relative_path = ?")) {
            st.setString(1, pathOf(c, mediaFileId));
            if (st.executeUpdate() == 0) {
                throw new ApiException(404, "OVERRIDE_NOT_FOUND", "Aucune correction pour ce fichier");
            }
        }
        return Response.noContent().build();
    }

    @GET
    @Path("/overrides")
    public List<OverrideDto> listOverrides() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return overrides(c, null);
        }
    }

    private List<OverrideDto> overrides(Connection c, Long mediaFileId) throws SQLException {
        List<OverrideDto> list = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement("""
                SELECT m.id, o.relative_path, o.action, o.anime_title, o.season_number, o.episode_number, o.created_by, o.created_at
                FROM media_file_override o LEFT JOIN media_file m ON m.relative_path = o.relative_path
                WHERE ?::bigint IS NULL OR m.id = ? ORDER BY o.relative_path""")) {
            st.setObject(1, mediaFileId);
            st.setObject(2, mediaFileId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    list.add(new OverrideDto((Long) rs.getObject(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            (Integer) rs.getObject(5), (Integer) rs.getObject(6), rs.getString(7), instant(rs, 8)));
                }
            }
        }
        return list;
    }

    private static String pathOf(Connection c, long mediaFileId) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT relative_path FROM media_file WHERE id = ?")) {
            st.setLong(1, mediaFileId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(404, "FILE_NOT_FOUND", "Fichier introuvable");
                }
                return rs.getString(1);
            }
        }
    }

    private static long latestScanId(Connection c) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT max(id) FROM scan_run"); ResultSet rs = st.executeQuery()) {
            rs.next();
            long id = rs.getLong(1);
            if (rs.wasNull()) {
                throw new ApiException(404, "NO_SCAN", "Aucun scan n'a encore été lancé");
            }
            return id;
        }
    }

    private static void bindFilters(PreparedStatement st, long scanId, String category, String anime) throws SQLException {
        st.setLong(1, scanId);
        st.setString(2, category);
        st.setString(3, category);
        String like = anime == null || anime.isBlank() ? null
                : anime.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        st.setString(4, like);
        st.setString(5, like);
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
