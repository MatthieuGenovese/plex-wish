package fr.plexwish.animeserver.library.subs;

import fr.plexwish.animeserver.common.db.Db;
import fr.plexwish.animeserver.common.db.Jdbc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sous-titres externes ({@code external_subtitle}) et corrections de l'admin ({@code external_subtitle_override}).
 * Les méthodes qui prennent une {@link Connection} s'exécutent dans la transaction du scan.
 */
@ApplicationScoped
public class ExternalSubtitleRepository {

    /** Sous-titre disponible d'un fichier vidéo. */
    public record SubtitleFile(long id, String relativePath, long size, OffsetDateTime modified, boolean forced, String language) {
    }

    /** Ce que le scan sait déjà d'un sous-titre (pour ne relire le fichier que s'il a changé). */
    public record KnownSubtitle(String relativePath, long size, OffsetDateTime modified, Long cueSize, OffsetDateTime cueModified,
                                BigDecimal lastCue) {
    }

    /** Correction de l'admin ({@code episodeId} null : détaché). */
    public record SubtitleOverride(String relativePath, Long episodeId) {
    }

    public record VideoEpisode(String relativePath, long episodeId) {
    }

    public record VideoName(String fileName, long episodeId) {
    }

    public record EpisodeKey(String normalizedTitle, int seasonNumber, int episodeNumber, long episodeId) {
    }

    /** Ligne écrite par le scan pour un sous-titre vu. */
    public record ScannedSubtitle(String relativePath, String fileName, long size, OffsetDateTime modified, Long episodeId,
                                  String match, boolean forced, String language, BigDecimal lastCue) {
    }

    /** Sous-titre dont le dernier texte arrive après la fin de la vidéo. */
    public record DurationMismatch(String relativePath, double lastCueSeconds, double videoSeconds, String animeTitle,
                                   int seasonNumber, int episodeNumber) {
    }

    /** Ligne de l'administration : sous-titre, correction, épisode associé et durées. */
    public record AdminRow(long id, String relativePath, boolean available, String match, boolean manual, boolean forced,
                           String language, Long episodeId, String animeTitle, int seasonNumber, int episodeNumber,
                           BigDecimal lastCueSeconds, BigDecimal videoDurationSeconds, boolean tooLong) {
    }

    public record AdminSummary(long total, long linked, long unmatched, long manual, long tooLong) {
    }

    /** Filtres de la liste d'administration. */
    public enum AdminFilter { ALL, UNMATCHED, MANUAL, DURATION }

    private static final String ADMIN_SELECT = """
            SELECT x.id, x.relative_path, x.available, x.match, (o.relative_path IS NOT NULL), x.forced, x.language,
                   e.id, a.title, s.season_number, e.episode_number, x.last_cue_seconds, p.duration_seconds,
                   (x.last_cue_seconds IS NOT NULL AND p.duration_seconds IS NOT NULL
                    AND x.last_cue_seconds > p.duration_seconds + greatest(60, p.duration_seconds * 0.05))
            FROM external_subtitle x
            LEFT JOIN external_subtitle_override o ON o.relative_path = x.relative_path
            LEFT JOIN episode e ON e.id = x.episode_id
            LEFT JOIN season s ON s.id = e.season_id
            LEFT JOIN anime a ON a.id = s.anime_id
            LEFT JOIN media_probe p ON p.media_file_id = e.media_file_id
            """;

    @Inject
    Db db;

    // --- Lecture --------------------------------------------------------------------------------

    /** Sous-titres disponibles associés à l'épisode de ce fichier vidéo, non forcés d'abord. */
    public List<SubtitleFile> forFile(long mediaFileId) throws SQLException {
        return db.list("""
                SELECT x.id, x.relative_path, x.file_size, x.last_modified, x.forced, x.language
                FROM external_subtitle x JOIN episode e ON e.id = x.episode_id
                WHERE e.media_file_id = ? AND x.available ORDER BY x.forced, x.relative_path""",
                rs -> new SubtitleFile(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getObject(4, OffsetDateTime.class),
                        rs.getBoolean(5), rs.getString(6)),
                mediaFileId);
    }

    // --- Recherche d'épisode (correction de l'admin) ----------------------------------------------

    /** Vidéos disponibles liées à un épisode, sous le dossier {@code dir} (sous-dossiers compris). */
    public List<VideoName> videosUnder(String dir) throws SQLException {
        return db.list("""
                SELECT m.file_name, e.id FROM media_file m JOIN episode e ON e.media_file_id = m.id
                WHERE m.available AND m.relative_path LIKE ? ESCAPE '\\'""",
                rs -> new VideoName(rs.getString(1), rs.getLong(2)), like(dir) + "/%");
    }

    public Optional<Long> episodeId(String normalizedTitle, int season, int episode) throws SQLException {
        return db.one("""
                SELECT e.id FROM episode e JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id
                WHERE a.normalized_title = ? AND s.season_number = ? AND e.episode_number = ?""",
                rs -> rs.getLong(1), normalizedTitle, season, episode);
    }

    /** Plus grand numéro d'épisode de la saison (null si aucun). */
    public Integer lastEpisode(String normalizedTitle, int season) throws SQLException {
        return db.one("""
                SELECT max(e.episode_number) FROM episode e JOIN season s ON s.id = e.season_id
                JOIN anime a ON a.id = s.anime_id WHERE a.normalized_title = ? AND s.season_number = ?""",
                rs -> (Integer) rs.getObject(1), normalizedTitle, season).orElse(null);
    }

    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // --- Scan ---------------------------------------------------------------------------------------

    public List<KnownSubtitle> known(Connection c) throws SQLException {
        return Jdbc.list(c, """
                SELECT relative_path, file_size, last_modified, cue_size, cue_modified, last_cue_seconds FROM external_subtitle""",
                rs -> new KnownSubtitle(rs.getString(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class),
                        (Long) rs.getObject(4), rs.getObject(5, OffsetDateTime.class), rs.getBigDecimal(6)));
    }

    public List<SubtitleOverride> overrides(Connection c) throws SQLException {
        return Jdbc.list(c, "SELECT relative_path, episode_id FROM external_subtitle_override",
                rs -> new SubtitleOverride(rs.getString(1), (Long) rs.getObject(2)));
    }

    /** Vidéos disponibles liées à un épisode (chemin relatif, épisode). */
    public List<VideoEpisode> linkedVideos(Connection c) throws SQLException {
        return Jdbc.list(c, """
                SELECT m.relative_path, e.id FROM media_file m JOIN episode e ON e.media_file_id = m.id WHERE m.available""",
                rs -> new VideoEpisode(rs.getString(1), rs.getLong(2)));
    }

    /** Tous les épisodes, par titre normalisé, saison et numéro. */
    public List<EpisodeKey> episodeKeys(Connection c) throws SQLException {
        return Jdbc.list(c, """
                SELECT a.normalized_title, s.season_number, e.episode_number, e.id
                FROM episode e JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id""",
                rs -> new EpisodeKey(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getLong(4)));
    }

    /** Écrit les sous-titres vus par le scan (une requête pour tout le paquet), disponibles. */
    public void upsertScanned(Connection c, List<ScannedSubtitle> rows, OffsetDateTime scanTime) throws SQLException {
        List<Object> params = new ArrayList<>(rows.size() * 12);
        for (ScannedSubtitle r : rows) {
            params.add(r.relativePath());
            params.add(r.fileName());
            params.add(r.size());
            params.add(r.modified());
            params.add(scanTime);
            params.add(r.episodeId());
            params.add(r.match());
            params.add(r.forced());
            params.add(r.language());
            params.add(r.lastCue());
            params.add(r.size());
            params.add(r.modified());
        }
        String sql = "INSERT INTO external_subtitle (relative_path, file_name, file_size, last_modified, last_seen_at, episode_id,"
                + " match, forced, language, last_cue_seconds, cue_size, cue_modified, available) VALUES "
                + Jdbc.values(rows.size(), "?", "?", "?", "?", "?", "?::bigint", "?", "?", "?", "?::numeric", "?", "?", "TRUE")
                + " ON CONFLICT (relative_path) DO UPDATE SET file_name = EXCLUDED.file_name, file_size = EXCLUDED.file_size,"
                + " last_modified = EXCLUDED.last_modified, last_seen_at = EXCLUDED.last_seen_at, episode_id = EXCLUDED.episode_id,"
                + " match = EXCLUDED.match, forced = EXCLUDED.forced, language = EXCLUDED.language,"
                + " last_cue_seconds = EXCLUDED.last_cue_seconds, cue_size = EXCLUDED.cue_size, cue_modified = EXCLUDED.cue_modified,"
                + " available = TRUE";
        Jdbc.update(c, sql, params.toArray());
    }

    /** Sous-titres non vus depuis {@code scanTime} : indisponibles. */
    public void markUnavailable(Connection c, OffsetDateTime scanTime) throws SQLException {
        Jdbc.update(c, "UPDATE external_subtitle SET available = FALSE WHERE available AND last_seen_at < ?", scanTime);
    }

    /** Sous-titres disponibles dont le dernier texte dépasse la durée analysée de la vidéo de plus que la marge. */
    public List<DurationMismatch> durationMismatches(Connection c, double marginSeconds, double marginRatio) throws SQLException {
        return Jdbc.list(c, """
                SELECT x.relative_path, x.last_cue_seconds, p.duration_seconds, a.title, s.season_number, e.episode_number
                FROM external_subtitle x JOIN episode e ON e.id = x.episode_id JOIN season s ON s.id = e.season_id
                JOIN anime a ON a.id = s.anime_id JOIN media_probe p ON p.media_file_id = e.media_file_id
                WHERE x.available AND x.last_cue_seconds IS NOT NULL AND p.duration_seconds IS NOT NULL
                  AND x.last_cue_seconds > p.duration_seconds + greatest(?, p.duration_seconds * ?)
                ORDER BY x.relative_path""",
                rs -> new DurationMismatch(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getString(4), rs.getInt(5),
                        rs.getInt(6)),
                marginSeconds, marginRatio);
    }

    // --- Administration ---------------------------------------------------------------------------

    public AdminSummary adminSummary() throws SQLException {
        return db.one("""
                SELECT count(*), count(x.episode_id), count(*) FILTER (WHERE x.episode_id IS NULL AND o.relative_path IS NULL),
                       count(o.relative_path),
                       count(*) FILTER (WHERE x.last_cue_seconds > p.duration_seconds + greatest(60, p.duration_seconds * 0.05))
                FROM external_subtitle x
                LEFT JOIN external_subtitle_override o ON o.relative_path = x.relative_path
                LEFT JOIN episode e ON e.id = x.episode_id
                LEFT JOIN media_probe p ON p.media_file_id = e.media_file_id
                WHERE x.available""",
                rs -> new AdminSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5))).orElseThrow();
    }

    private static String adminWhere(AdminFilter filter) {
        return " WHERE x.available AND (?::text IS NULL OR x.relative_path ILIKE ?)" + switch (filter) {
            case UNMATCHED -> " AND x.episode_id IS NULL AND o.relative_path IS NULL";
            case MANUAL -> " AND o.relative_path IS NOT NULL";
            case DURATION -> " AND x.last_cue_seconds > p.duration_seconds + greatest(60, p.duration_seconds * 0.05)";
            case ALL -> "";
        };
    }

    /** {@code pathLike} : motif ILIKE déjà échappé (null : tous). */
    public long adminCount(AdminFilter filter, String pathLike) throws SQLException {
        return db.one("""
                SELECT count(*) FROM external_subtitle x
                LEFT JOIN external_subtitle_override o ON o.relative_path = x.relative_path
                LEFT JOIN episode e ON e.id = x.episode_id
                LEFT JOIN media_probe p ON p.media_file_id = e.media_file_id""" + adminWhere(filter),
                rs -> rs.getLong(1), Jdbc.varchar(pathLike), Jdbc.varchar(pathLike)).orElseThrow();
    }

    public List<AdminRow> adminPage(AdminFilter filter, String pathLike, int size, long offset) throws SQLException {
        return db.list(ADMIN_SELECT + adminWhere(filter) + " ORDER BY x.relative_path LIMIT ? OFFSET ?",
                ExternalSubtitleRepository::adminRow, Jdbc.varchar(pathLike), Jdbc.varchar(pathLike), size, offset);
    }

    public Optional<AdminRow> adminRow(long id) throws SQLException {
        return db.one(ADMIN_SELECT + " WHERE x.id = ?", ExternalSubtitleRepository::adminRow, id);
    }

    /** Chemin relatif du sous-titre, vide s'il est inconnu. */
    public Optional<String> pathOf(long id) throws SQLException {
        return db.one("SELECT relative_path FROM external_subtitle WHERE id = ?", rs -> rs.getString(1), id);
    }

    /** Correction de l'admin ({@code episodeId} null : détaché). */
    public void saveOverride(String path, Long episodeId, String createdBy) throws SQLException {
        db.update("""
                INSERT INTO external_subtitle_override (relative_path, episode_id, created_by) VALUES (?, ?, ?)
                ON CONFLICT (relative_path) DO UPDATE SET episode_id = EXCLUDED.episode_id, created_by = EXCLUDED.created_by,
                    created_at = now()""", path, episodeId, createdBy);
    }

    public void deleteOverride(String path) throws SQLException {
        db.update("DELETE FROM external_subtitle_override WHERE relative_path = ?", path);
    }

    /** Association courante du sous-titre (effet immédiat, sans attendre un scan). */
    public void setEpisode(String path, Long episodeId, String match) throws SQLException {
        db.update("UPDATE external_subtitle SET episode_id = ?, match = ? WHERE relative_path = ?",
                episodeId, Jdbc.varchar(match), path);
    }

    private static AdminRow adminRow(ResultSet rs) throws SQLException {
        return new AdminRow(rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getString(4), rs.getBoolean(5),
                rs.getBoolean(6), rs.getString(7), (Long) rs.getObject(8), rs.getString(9), rs.getInt(10), rs.getInt(11),
                rs.getBigDecimal(12), rs.getBigDecimal(13), rs.getBoolean(14));
    }
}
