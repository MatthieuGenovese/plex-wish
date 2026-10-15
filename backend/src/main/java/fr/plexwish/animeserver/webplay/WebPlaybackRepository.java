package fr.plexwish.animeserver.webplay;

import fr.plexwish.animeserver.common.db.Db;
import fr.plexwish.animeserver.common.db.Jdbc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Lectures du lecteur web : épisode et son fichier, reprise, état des préparations, contrôles d'accès. */
@ApplicationScoped
public class WebPlaybackRepository {

    /** Épisode et son fichier ({@code fileId} null : aucun fichier lié). */
    record EpisodeFile(Long fileId, boolean available, String fileName, long size, OffsetDateTime modified, long animeId,
                       String animeTitle, int seasonNumber, int episodeNumber, String episodeTitle, Integer durationSeconds) {
    }

    record Progress(int positionSeconds, int durationSeconds, boolean completed) {
    }

    /** Préparation d'un épisode, encore valable pour son fichier ({@code manifestJson} : plan ou résultat). */
    record JobState(long episodeId, String kind, String status, int priority, String manifestJson) {
    }

    /** Utilisateur actif (faux : désactivé ou supprimé) et fichier disponible. */
    record Access(boolean userEnabled, boolean fileAvailable) {
    }

    @Inject
    Db db;

    /** Vide si l'épisode n'existe pas. */
    Optional<EpisodeFile> episodeFile(long episodeId) throws SQLException {
        return db.one("""
                SELECT m.id, m.available, m.file_name, m.file_size, m.last_modified, a.id, a.title, s.season_number,
                       e.episode_number, e.title, e.duration_seconds
                FROM episode e JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id
                LEFT JOIN media_file m ON m.id = e.media_file_id WHERE e.id = ?""",
                rs -> new EpisodeFile(Jdbc.longOrNull(rs, 1), rs.getBoolean(2), rs.getString(3), rs.getLong(4),
                        rs.getObject(5, OffsetDateTime.class), rs.getLong(6), rs.getString(7), rs.getInt(8), rs.getInt(9),
                        rs.getString(10), (Integer) rs.getObject(11)),
                episodeId);
    }

    Optional<Progress> progress(long userId, long episodeId) throws SQLException {
        return db.one("SELECT position_seconds, duration_seconds, completed FROM playback_progress WHERE user_id = ? AND episode_id = ?",
                rs -> new Progress(rs.getInt(1), rs.getInt(2), rs.getBoolean(3)), userId, episodeId);
    }

    /** Préparations des épisodes (fichier disponible et inchangé depuis la demande). */
    List<JobState> jobStates(Long[] episodeIds) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT e.id, j.kind, j.status, j.priority, j.manifest FROM episode e
                    JOIN media_file f ON f.id = e.media_file_id AND f.available
                    JOIN web_job j ON j.media_file_id = f.id AND j.source_size = f.file_size
                        AND j.source_modified IS NOT DISTINCT FROM f.last_modified
                    WHERE e.id = ANY (?)""")) {
                st.setArray(1, c.createArrayOf("bigint", episodeIds));
                List<JobState> out = new ArrayList<>();
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        out.add(new JobState(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)));
                    }
                }
                return out;
            }
        });
    }

    Access access(long userId, long fileId) throws SQLException {
        return db.one("""
                SELECT (SELECT enabled FROM app_user WHERE id = ?), (SELECT available FROM media_file WHERE id = ?)""",
                rs -> new Access(rs.getBoolean(1), rs.getBoolean(2)), userId, fileId).orElseThrow();
    }
}
