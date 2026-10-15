package fr.plexwish.animeserver.stream;

import fr.plexwish.animeserver.common.db.Db;
import fr.plexwish.animeserver.common.db.Jdbc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

/** Lectures nécessaires au streaming : fichier d'un épisode, et contrôles d'accès des URL signées. */
@ApplicationScoped
public class StreamRepository {

    /** Fichier lié à l'épisode ({@code fileId} null : aucun fichier). */
    public record EpisodeFile(Long fileId, boolean available, String fileName, long fileSize, String container,
                              OffsetDateTime lastModified) {
    }

    /**
     * Accès par URL signée : {@code userEnabled} faux si l'utilisateur est désactivé ou supprimé ;
     * {@code relativePath} null si la ressource n'est pas (ou plus) disponible.
     */
    public record FileAccess(boolean userEnabled, String relativePath, long fileSize, OffsetDateTime lastModified) {
    }

    @Inject
    Db db;

    /** Vide si l'épisode n'existe pas. */
    public Optional<EpisodeFile> episodeFile(long episodeId) throws SQLException {
        return db.one("""
                SELECT m.id, m.available, m.file_name, m.file_size, m.container, m.last_modified
                FROM episode e LEFT JOIN media_file m ON m.id = e.media_file_id WHERE e.id = ?""",
                rs -> new EpisodeFile(Jdbc.longOrNull(rs, 1), rs.getBoolean(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                        rs.getObject(6, OffsetDateTime.class)),
                episodeId);
    }

    /** Fichier vidéo disponible ({@code fileSize} 0 et {@code lastModified} null s'il ne l'est pas). */
    public FileAccess mediaFileAccess(long userId, long fileId) throws SQLException {
        return db.one("""
                SELECT (SELECT enabled FROM app_user WHERE id = ?), f.relative_path, f.file_size, f.last_modified
                FROM (SELECT 1) one LEFT JOIN media_file f ON f.id = ? AND f.available""",
                rs -> new FileAccess(rs.getBoolean(1), rs.getString(2), rs.getLong(3), rs.getObject(4, OffsetDateTime.class)),
                userId, fileId).orElseThrow();
    }

    /** Sous-titre externe disponible et associé à l'épisode de ce fichier (lui-même disponible). */
    public FileAccess subtitleAccess(long userId, long subId, long fileId) throws SQLException {
        return db.one("""
                SELECT (SELECT enabled FROM app_user WHERE id = ?), x.relative_path
                FROM (SELECT 1) one LEFT JOIN (external_subtitle x JOIN episode e ON e.id = x.episode_id
                    JOIN media_file f ON f.id = e.media_file_id AND f.available)
                  ON x.id = ? AND x.available AND e.media_file_id = ?""",
                rs -> new FileAccess(rs.getBoolean(1), rs.getString(2), 0, null),
                userId, subId, fileId).orElseThrow();
    }
}
