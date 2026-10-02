package fr.plexwish.animeserver.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.library.scan.LibraryScanner;
import fr.plexwish.animeserver.library.scan.ScanService;
import fr.plexwish.animeserver.library.scan.Titles;
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

    /**
     * {@code failureCode} (si FAILED) : MEDIA_ROOT_UNAVAILABLE, MASS_REMOVAL (relancer avec confirmMassRemoval=true
     * si c'est voulu), INTERRUPTED, INTERNAL_ERROR. {@code failureReason} : explication pour l'humain.
     */
    public record ScanReport(long id, String status, Instant startedAt, Instant finishedAt, String triggeredBy,
                             String failureCode, String failureReason, JsonNode stats, Map<String, Long> issueCounts) {
    }

    public record ScanRunPage(long total, int page, int size, List<ScanReport> items) {
    }

    /**
     * Fichier signalé. Pour un doublon : {@code relativePath} = fichier écarté, {@code keptRelativePath} = fichier
     * conservé, et l'origine du numéro de saison de chacun (NAME_SXXEXX, NAME_NXEE, NAME_S, FOLDER,
     * SPECIAL_FOLDER, DEFAULT, OVERRIDE) pour repérer un sous-dossier non reconnu comme saison.
     */
    public record IssueDto(long id, Long mediaFileId, String category, String animeTitle, String relativePath, String detail,
                           Integer seasonNumber, Integer episodeNumber, String keptRelativePath,
                           String seasonSource, String keptSeasonSource) {
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

    /** Fichier qui serait délié par une correction ; {@code viaOverride} : lié (ou à lier) par une autre correction. */
    public record LinkedFile(Long mediaFileId, String relativePath, boolean viaOverride) {
    }

    public record TargetEpisode(String animeTitle, int seasonNumber, int episodeNumber) {
    }

    /** 409 : l'épisode visé est déjà fourni par un autre fichier. Rien n'a été modifié. */
    public record OverrideConflict(int status, String error, String message, TargetEpisode episode,
                                   List<LinkedFile> currentFiles, LinkedFile targetFile) {
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

    /**
     * Lance un scan en tâche de fond : 202 + id ; 409 si un scan tourne déjà.
     * {@code confirmMassRemoval=true} : accepter qu'il rende indisponibles plus de la moitié des fichiers
     * connus (sinon le scan s'arrête en FAILED sans rien modifier).
     */
    @POST
    @Path("/scan")
    public Response scan(@QueryParam("confirmMassRemoval") @DefaultValue("false") boolean confirmMassRemoval) {
        return Response.accepted(new ScanStarted(scans.start(jwt.getName(), confirmMassRemoval))).build();
    }

    /** Dernier scan (ou celui demandé) : statut, compteurs par catégorie, nombre de problèmes par catégorie. */
    @GET
    @Path("/scan-report")
    public ScanReport report(@QueryParam("scanId") Long scanId) throws SQLException, IOException {
        try (Connection c = dataSource.getConnection()) {
            long id = scanId != null ? scanId : latestScanId(c);
            ScanReport report = null;
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT %s FROM scan_run WHERE id = ?""".formatted(RUN_COLUMNS))) {
                st.setLong(1, id);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw new ApiException(404, "SCAN_NOT_FOUND", "Scan introuvable");
                    }
                    report = run(rs);
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

    /** Historique des scans, du plus récent au plus ancien (sans le détail des problèmes). */
    @GET
    @Path("/scans")
    public ScanRunPage history(@QueryParam("page") @DefaultValue("0") @Min(0) int page,
                               @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size) throws SQLException, IOException {
        try (Connection c = dataSource.getConnection()) {
            long total;
            try (PreparedStatement st = c.prepareStatement("SELECT count(*) FROM scan_run"); ResultSet rs = st.executeQuery()) {
                rs.next();
                total = rs.getLong(1);
            }
            List<ScanReport> items = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT " + RUN_COLUMNS + " FROM scan_run ORDER BY id DESC LIMIT ? OFFSET ?")) {
                st.setInt(1, size);
                st.setLong(2, (long) page * size);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        items.add(run(rs));
                    }
                }
            }
            Map<Long, ScanReport> byId = new LinkedHashMap<>();
            items.forEach(r -> byId.put(r.id(), r));
            try (PreparedStatement st = c.prepareStatement("SELECT scan_run_id, category, count(*) FROM scan_issue"
                    + " WHERE scan_run_id = ANY (?) GROUP BY scan_run_id, category ORDER BY category")) {
                st.setArray(1, c.createArrayOf("bigint", byId.keySet().toArray()));
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        byId.get(rs.getLong(1)).issueCounts().put(rs.getString(2), rs.getLong(3));
                    }
                }
            }
            return new ScanRunPage(total, page, size, items);
        }
    }

    private static final String RUN_COLUMNS =
            "id, status, started_at, finished_at, triggered_by, failure_code, failure_reason, stats::text";

    private ScanReport run(ResultSet rs) throws SQLException, IOException {
        String stats = rs.getString(8);
        return new ScanReport(rs.getLong(1), rs.getString(2), instant(rs, 3), instant(rs, 4), rs.getString(5),
                rs.getString(6), rs.getString(7), stats == null ? null : json.readTree(stats), new LinkedHashMap<>());
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
                    "SELECT id, media_file_id, category, anime_title, relative_path, detail, season_number, episode_number,"
                            + " kept_relative_path, season_source, kept_season_source FROM scan_issue" + where
                            + " ORDER BY category, anime_title, season_number, episode_number, relative_path LIMIT ? OFFSET ?")) {
                bindFilters(st, id, category, anime);
                st.setInt(6, size);
                st.setLong(7, (long) page * size);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        items.add(new IssueDto(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6), (Integer) rs.getObject(7), (Integer) rs.getObject(8),
                                rs.getString(9), rs.getString(10), rs.getString(11)));
                    }
                }
            }
            return new IssuePage(id, total, page, size, items);
        }
    }

    /**
     * Correction manuelle d'un fichier, appliquée au prochain scan et jamais écrasée par un scan (§7.7).
     * Si l'épisode visé est déjà fourni par un autre fichier (lien actuel ou autre correction) : 409 avec le détail,
     * sans rien modifier, sauf {@code replace=true}. Le remplacement supprime alors la correction de l'autre
     * fichier s'il y en a une ; l'autre fichier reste disponible et ressort au rapport (doublon ou non résolu).
     */
    @PUT
    @Path("/files/{id}/override")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setOverride(@PathParam("id") long mediaFileId,
                                @QueryParam("replace") @DefaultValue("false") boolean replace,
                                @Valid @NotNull OverrideRequest request) throws SQLException {
        boolean episode = "EPISODE".equals(request.action());
        if (episode && (request.animeTitle() == null || request.animeTitle().isBlank()
                || request.seasonNumber() == null || request.episodeNumber() == null)) {
            throw new ApiException(400, "INCOMPLETE_OVERRIDE", "EPISODE demande animeTitle, seasonNumber et episodeNumber");
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                String path = pathOf(c, mediaFileId);
                String title = episode ? request.animeTitle().trim() : null;
                if (episode) {
                    List<LinkedFile> conflicts = conflicts(c, path, title, request.seasonNumber(), request.episodeNumber());
                    if (!conflicts.isEmpty() && !replace) {
                        c.rollback();
                        TargetEpisode target = new TargetEpisode(displayTitle(c, title), request.seasonNumber(), request.episodeNumber());
                        return Response.status(409).type(MediaType.APPLICATION_JSON).entity(new OverrideConflict(409,
                                "EPISODE_ALREADY_LINKED",
                                "Cet épisode est déjà fourni par un autre fichier. Confirmer le remplacement avec replace=true.",
                                target, conflicts, new LinkedFile(mediaFileId, path, false))).build();
                    }
                    for (LinkedFile other : conflicts) {
                        if (other.viaOverride()) {
                            try (PreparedStatement st = c.prepareStatement("DELETE FROM media_file_override WHERE relative_path = ?")) {
                                st.setString(1, other.relativePath());
                                st.executeUpdate();
                            }
                        }
                    }
                }
                try (PreparedStatement st = c.prepareStatement("""
                        INSERT INTO media_file_override (relative_path, action, anime_title, season_number, episode_number, created_by)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (relative_path) DO UPDATE SET action = EXCLUDED.action, anime_title = EXCLUDED.anime_title,
                            season_number = EXCLUDED.season_number, episode_number = EXCLUDED.episode_number,
                            created_by = EXCLUDED.created_by, created_at = now()""")) {
                    st.setString(1, path);
                    st.setString(2, request.action());
                    st.setString(3, title);
                    st.setObject(4, episode ? request.seasonNumber() : null);
                    st.setObject(5, episode ? request.episodeNumber() : null);
                    st.setString(6, jwt.getName());
                    st.executeUpdate();
                }
                c.commit();
                c.setAutoCommit(true);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
            return Response.ok(overrides(c, mediaFileId).get(0)).build();
        }
    }

    /**
     * Autres fichiers disponibles qui fournissent (ou fourniront au prochain scan) l'épisode visé :
     * le fichier actuellement lié, sauf si sa propre correction l'envoie ailleurs, et les fichiers dont une
     * correction vise ce même épisode.
     */
    private List<LinkedFile> conflicts(Connection c, String path, String title, int season, int episode) throws SQLException {
        String target = LibraryScanner.target(title, season, episode);
        Map<String, LinkedFile> found = new LinkedHashMap<>();
        try (PreparedStatement st = c.prepareStatement("""
                SELECT m.id, m.relative_path, o.action, o.anime_title, o.season_number, o.episode_number
                FROM episode e JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id
                JOIN media_file m ON m.id = e.media_file_id
                LEFT JOIN media_file_override o ON o.relative_path = m.relative_path
                WHERE a.normalized_title = ? AND s.season_number = ? AND e.episode_number = ?
                  AND m.available AND m.relative_path <> ?""")) {
            st.setString(1, Titles.normalize(title));
            st.setInt(2, season);
            st.setInt(3, episode);
            st.setString(4, path);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    String action = rs.getString(3);
                    boolean keeps = action == null || ("EPISODE".equals(action)
                            && target.equals(LibraryScanner.target(rs.getString(4), rs.getInt(5), rs.getInt(6))));
                    if (keeps) {
                        found.put(rs.getString(2), new LinkedFile(rs.getLong(1), rs.getString(2), action != null));
                    }
                }
            }
        }
        try (PreparedStatement st = c.prepareStatement("""
                SELECT m.id, o.relative_path, o.anime_title
                FROM media_file_override o JOIN media_file m ON m.relative_path = o.relative_path
                WHERE o.action = 'EPISODE' AND o.season_number = ? AND o.episode_number = ?
                  AND m.available AND o.relative_path <> ?""")) {
            st.setInt(1, season);
            st.setInt(2, episode);
            st.setString(3, path);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    if (Titles.normalize(rs.getString(3)).equals(Titles.normalize(title))) {
                        found.putIfAbsent(rs.getString(2), new LinkedFile(rs.getLong(1), rs.getString(2), true));
                    }
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /** Titre tel qu'affiché dans la bibliothèque s'il existe déjà (« Show » pour « show »). */
    private static String displayTitle(Connection c, String title) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT title FROM anime WHERE normalized_title = ?")) {
            st.setString(1, Titles.normalize(title));
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : title;
            }
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
