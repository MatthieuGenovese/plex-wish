package fr.plexwish.animeserver.library.scan;

import fr.plexwish.animeserver.common.db.Db;
import fr.plexwish.animeserver.common.db.Jdbc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tables {@code scan_run} (un enregistrement par scan : statut, statistiques en JSON, échec) et, en lecture,
 * {@code scan_issue} (fichiers signalés par un scan).
 */
@ApplicationScoped
public class ScanRunRepository {

    /** Dernier scan : statut et compteurs principaux ({@code null} si le scan n'a pas fini). */
    public record LatestRun(long id, String status, Long videos, Long episodes) {
    }

    /** Scan ; {@code statsJson} : statistiques en JSON (null si le scan n'a pas fini). */
    public record RunRow(long id, String status, Instant startedAt, Instant finishedAt, String triggeredBy,
                         String failureCode, String failureReason, String statsJson) {
    }

    /** Fichier signalé par un scan. */
    public record IssueRow(long id, Long mediaFileId, String category, String animeTitle, String relativePath, String detail,
                           Integer seasonNumber, Integer episodeNumber, String keptRelativePath,
                           String seasonSource, String keptSeasonSource) {
    }

    private static final String RUN_COLUMNS =
            "id, status, started_at, finished_at, triggered_by, failure_code, failure_reason, stats::text";

    private static final String ISSUE_FILTER = " WHERE scan_run_id = ? AND (?::text IS NULL OR category = ?)"
            + " AND (?::text IS NULL OR anime_title ILIKE '%' || ? || '%')";

    @Inject
    Db db;

    /** Passe en FAILED les scans restés RUNNING (arrêt du conteneur) ; nombre de scans concernés. */
    public int failRunning(String reason) throws SQLException {
        return db.update("UPDATE scan_run SET status = 'FAILED', finished_at = now(), failure_reason = ?, failure_code = 'INTERRUPTED' WHERE status = 'RUNNING'",
                reason);
    }

    /** Nouveau scan RUNNING ; SQLState 23505 si un scan tourne déjà (index unique partiel). */
    public long insertRunning(String triggeredBy) throws SQLException {
        return db.one("INSERT INTO scan_run (status, triggered_by) VALUES ('RUNNING', ?) RETURNING id",
                rs -> rs.getLong(1), triggeredBy).orElseThrow();
    }

    public void finish(long runId, String status, String statsJson, String code, String reason) throws SQLException {
        db.update("UPDATE scan_run SET status = ?, finished_at = now(), stats = ?::jsonb, failure_code = ?, failure_reason = ? WHERE id = ?",
                status, statsJson, code, reason, runId);
    }

    public Optional<LatestRun> latest() throws SQLException {
        return db.one("""
                SELECT id, status, (stats->>'videos')::bigint, (stats->>'episodes')::bigint
                FROM scan_run ORDER BY id DESC LIMIT 1""",
                rs -> new LatestRun(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3), (Long) rs.getObject(4)));
    }

    /** Identifiant du dernier scan, vide s'il n'y en a aucun. */
    public Optional<Long> latestId() throws SQLException {
        return db.one("SELECT max(id) FROM scan_run", rs -> Jdbc.longOrNull(rs, 1));
    }

    public Optional<RunRow> find(long id) throws SQLException {
        return db.one("SELECT %s FROM scan_run WHERE id = ?".formatted(RUN_COLUMNS), ScanRunRepository::run, id);
    }

    public long count() throws SQLException {
        return db.one("SELECT count(*) FROM scan_run", rs -> rs.getLong(1)).orElseThrow();
    }

    /** Scans du plus récent au plus ancien. */
    public List<RunRow> page(int size, long offset) throws SQLException {
        return db.list("SELECT " + RUN_COLUMNS + " FROM scan_run ORDER BY id DESC LIMIT ? OFFSET ?", ScanRunRepository::run,
                size, offset);
    }

    /** Nombre de problèmes par catégorie (ordre alphabétique des catégories). */
    public Map<String, Long> issueCounts(long runId) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        db.list("SELECT category, count(*) FROM scan_issue WHERE scan_run_id = ? GROUP BY category ORDER BY category",
                rs -> counts.put(rs.getString(1), rs.getLong(2)), runId);
        return counts;
    }

    /** Nombre de problèmes par scan puis par catégorie (ordre alphabétique des catégories). */
    public Map<Long, Map<String, Long>> issueCounts(Collection<Long> runIds) throws SQLException {
        Map<Long, Map<String, Long>> counts = new LinkedHashMap<>();
        db.withConnection(c -> {
            try (PreparedStatement st = c.prepareStatement("SELECT scan_run_id, category, count(*) FROM scan_issue"
                    + " WHERE scan_run_id = ANY (?) GROUP BY scan_run_id, category ORDER BY category")) {
                st.setArray(1, c.createArrayOf("bigint", runIds.toArray()));
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        counts.computeIfAbsent(rs.getLong(1), k -> new LinkedHashMap<>()).put(rs.getString(2), rs.getLong(3));
                    }
                }
            }
            return null;
        });
        return counts;
    }

    /**
     * Nombre de fichiers signalés par le scan, filtrés par catégorie et par titre d'animé ({@code animeLike} :
     * motif ILIKE déjà échappé, recherché n'importe où dans le titre). Filtres null : ignorés.
     */
    public long countIssues(long runId, String category, String animeLike) throws SQLException {
        return db.one("SELECT count(*) FROM scan_issue" + ISSUE_FILTER, rs -> rs.getLong(1),
                issueFilter(runId, category, animeLike)).orElseThrow();
    }

    public List<IssueRow> issues(long runId, String category, String animeLike, int size, long offset) throws SQLException {
        Object[] filter = issueFilter(runId, category, animeLike);
        Object[] params = java.util.Arrays.copyOf(filter, filter.length + 2);
        params[filter.length] = size;
        params[filter.length + 1] = offset;
        return db.list("SELECT id, media_file_id, category, anime_title, relative_path, detail, season_number, episode_number,"
                        + " kept_relative_path, season_source, kept_season_source FROM scan_issue" + ISSUE_FILTER
                        + " ORDER BY category, anime_title, season_number, episode_number, relative_path LIMIT ? OFFSET ?",
                rs -> new IssueRow(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), (Integer) rs.getObject(7), (Integer) rs.getObject(8),
                        rs.getString(9), rs.getString(10), rs.getString(11)),
                params);
    }

    private static Object[] issueFilter(long runId, String category, String animeLike) {
        return new Object[]{runId, Jdbc.varchar(category), Jdbc.varchar(category), Jdbc.varchar(animeLike), Jdbc.varchar(animeLike)};
    }

    private static RunRow run(ResultSet rs) throws SQLException {
        return new RunRow(rs.getLong(1), rs.getString(2), Jdbc.instant(rs, 3), Jdbc.instant(rs, 4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8));
    }
}
