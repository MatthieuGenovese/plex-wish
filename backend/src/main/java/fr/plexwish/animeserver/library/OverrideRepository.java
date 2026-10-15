package fr.plexwish.animeserver.library;

import fr.plexwish.animeserver.common.db.Db;
import fr.plexwish.animeserver.common.db.Jdbc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Corrections manuelles de l'admin ({@code media_file_override}, par chemin relatif) et lectures nécessaires
 * pour les vérifier. Les méthodes qui prennent une {@link Connection} s'exécutent dans la transaction de l'appelant.
 */
@ApplicationScoped
public class OverrideRepository {

    public record OverrideRow(Long mediaFileId, String relativePath, String action, String animeTitle,
                              Integer seasonNumber, Integer episodeNumber, String createdBy, Instant createdAt) {
    }

    /** Fichier disponible lié à l'épisode visé, et sa propre correction ({@code action} null : aucune). */
    public record LinkedRow(long mediaFileId, String relativePath, String action, String animeTitle,
                            int seasonNumber, int episodeNumber) {
    }

    /** Fichier disponible dont une correction EPISODE vise ce numéro (titre à comparer par l'appelant). */
    public record OverrideTarget(long mediaFileId, String relativePath, String animeTitle) {
    }

    @Inject
    Db db;

    /** Chemin relatif du fichier, vide s'il est inconnu. */
    public Optional<String> pathOf(Connection c, long mediaFileId) throws SQLException {
        return Jdbc.one(c, "SELECT relative_path FROM media_file WHERE id = ?", rs -> rs.getString(1), mediaFileId);
    }

    public Optional<String> pathOf(long mediaFileId) throws SQLException {
        return db.withConnection(c -> pathOf(c, mediaFileId));
    }

    /** Fichiers disponibles (autres que {@code path}) actuellement liés à l'épisode de l'animé au titre normalisé. */
    public List<LinkedRow> linkedTo(Connection c, String normalizedTitle, int season, int episode, String path) throws SQLException {
        return Jdbc.list(c, """
                SELECT m.id, m.relative_path, o.action, o.anime_title, o.season_number, o.episode_number
                FROM episode e JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id
                JOIN media_file m ON m.id = e.media_file_id
                LEFT JOIN media_file_override o ON o.relative_path = m.relative_path
                WHERE a.normalized_title = ? AND s.season_number = ? AND e.episode_number = ?
                  AND m.available AND m.relative_path <> ?""",
                rs -> new LinkedRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6)),
                normalizedTitle, season, episode, path);
    }

    /** Fichiers disponibles (autres que {@code path}) dont une correction EPISODE vise cette saison et cet épisode. */
    public List<OverrideTarget> episodeOverridesFor(Connection c, int season, int episode, String path) throws SQLException {
        return Jdbc.list(c, """
                SELECT m.id, o.relative_path, o.anime_title
                FROM media_file_override o JOIN media_file m ON m.relative_path = o.relative_path
                WHERE o.action = 'EPISODE' AND o.season_number = ? AND o.episode_number = ?
                  AND m.available AND o.relative_path <> ?""",
                rs -> new OverrideTarget(rs.getLong(1), rs.getString(2), rs.getString(3)), season, episode, path);
    }

    /** Titre de l'animé tel qu'enregistré, s'il existe. */
    public Optional<String> animeTitle(Connection c, String normalizedTitle) throws SQLException {
        return Jdbc.one(c, "SELECT title FROM anime WHERE normalized_title = ?", rs -> rs.getString(1), normalizedTitle);
    }

    public int delete(Connection c, String relativePath) throws SQLException {
        return Jdbc.update(c, "DELETE FROM media_file_override WHERE relative_path = ?", relativePath);
    }

    public int delete(String relativePath) throws SQLException {
        return db.update("DELETE FROM media_file_override WHERE relative_path = ?", relativePath);
    }

    public void upsert(Connection c, String relativePath, String action, String animeTitle, Integer seasonNumber,
                       Integer episodeNumber, String createdBy) throws SQLException {
        Jdbc.update(c, """
                INSERT INTO media_file_override (relative_path, action, anime_title, season_number, episode_number, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (relative_path) DO UPDATE SET action = EXCLUDED.action, anime_title = EXCLUDED.anime_title,
                    season_number = EXCLUDED.season_number, episode_number = EXCLUDED.episode_number,
                    created_by = EXCLUDED.created_by, created_at = now()""",
                relativePath, action, Jdbc.varchar(animeTitle), seasonNumber, episodeNumber, createdBy);
    }

    /** Toutes les corrections ({@code mediaFileId} null), ou celle du fichier. */
    public List<OverrideRow> list(Long mediaFileId) throws SQLException {
        return db.list("""
                SELECT m.id, o.relative_path, o.action, o.anime_title, o.season_number, o.episode_number, o.created_by, o.created_at
                FROM media_file_override o LEFT JOIN media_file m ON m.relative_path = o.relative_path
                WHERE ?::bigint IS NULL OR m.id = ? ORDER BY o.relative_path""",
                rs -> new OverrideRow((Long) rs.getObject(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        (Integer) rs.getObject(5), (Integer) rs.getObject(6), rs.getString(7), Jdbc.instant(rs, 8)),
                mediaFileId, mediaFileId);
    }
}
