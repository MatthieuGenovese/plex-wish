package fr.plexwish.animeserver.progress;

import fr.plexwish.animeserver.common.db.Db;
import fr.plexwish.animeserver.common.db.Jdbc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Progression de lecture ({@code playback_progress}) et ordre des épisodes visibles d'un animé (fichier
 * disponible), pour « continuer à regarder » et « épisode suivant ».
 */
@ApplicationScoped
public class ProgressRepository {

    /** Durée analysée d'un épisode visible ({@code null} si inconnue). */
    public record KnownDuration(Integer seconds) {
    }

    public record ProgressRow(long episodeId, int positionSeconds, int durationSeconds, boolean completed, Instant updatedAt) {
    }

    /** Dernière activité de l'utilisateur sur un animé. */
    public record Activity(long animeId, long episodeId, boolean completed, Instant updatedAt) {
    }

    /** Épisode visible ; {@code durationSeconds} = 0 si inconnue. */
    public record VisibleEpisode(long id, int episodeNumber, String title, long seasonId, int seasonNumber,
                                 long animeId, String animeTitle, int durationSeconds) {
    }

    /** Épisode candidat et progression de l'utilisateur sur lui ({@code positionSeconds} null : jamais commencé). */
    public record Candidate(VisibleEpisode episode, Integer positionSeconds, int progressDurationSeconds, boolean completed,
                            Instant updatedAt) {
    }

    private static final String VISIBLE = """
            FROM episode e JOIN media_file m ON m.id = e.media_file_id AND m.available
            JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id""";

    private static final String VISIBLE_EPISODES = """
            SELECT e.id, e.episode_number, e.title, s.id AS season_id, s.season_number, a.id AS anime_id, a.title AS anime_title,
                   coalesce(e.duration_seconds, 0) AS duration,
                   row_number() OVER (ORDER BY (s.season_number = 0), s.season_number, e.episode_number, e.id) AS rn
            FROM episode e JOIN media_file m ON m.id = e.media_file_id AND m.available
            JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id
            WHERE a.id = ?""";

    @Inject
    Db db;

    /** Vide si l'épisode n'existe pas ou n'est pas visible. */
    public Optional<KnownDuration> visibleDuration(long episodeId) throws SQLException {
        return db.one("SELECT e.duration_seconds " + VISIBLE + " WHERE e.id = ?",
                rs -> new KnownDuration(Jdbc.integer(rs, 1)), episodeId);
    }

    /** Crée ou remplace la progression ; renvoie l'heure d'enregistrement. */
    public Instant upsert(long userId, long episodeId, int position, int duration, boolean completed) throws SQLException {
        return db.one("""
                INSERT INTO playback_progress (user_id, episode_id, position_seconds, duration_seconds, completed, updated_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (user_id, episode_id) DO UPDATE SET position_seconds = EXCLUDED.position_seconds,
                    duration_seconds = EXCLUDED.duration_seconds, completed = EXCLUDED.completed, updated_at = now()
                RETURNING updated_at""", rs -> Jdbc.instant(rs, 1), userId, episodeId, position, duration, completed).orElseThrow();
    }

    /** Progression de l'utilisateur sur les épisodes visibles (d'un animé si {@code animeId}), la plus récente d'abord. */
    public List<ProgressRow> mine(long userId, Long animeId) throws SQLException {
        return db.list("SELECT p.episode_id, p.position_seconds, p.duration_seconds,"
                        + " p.completed, p.updated_at FROM playback_progress p JOIN LATERAL (SELECT a.id AS anime_id " + VISIBLE
                        + " WHERE e.id = p.episode_id) v ON TRUE"
                        + " WHERE p.user_id = ? AND (?::bigint IS NULL OR v.anime_id = ?) ORDER BY p.updated_at DESC, p.episode_id",
                rs -> new ProgressRow(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getBoolean(4), Jdbc.instant(rs, 5)),
                userId, animeId, animeId);
    }

    /** Dernière activité par animé (épisode commencé ou terminé), la plus récente d'abord. */
    public List<Activity> activities(long userId, Long animeId) throws SQLException {
        return db.list("""
                SELECT anime_id, episode_id, completed, updated_at FROM (
                    SELECT DISTINCT ON (s.anime_id) s.anime_id, e.id AS episode_id, p.completed, p.updated_at
                    FROM playback_progress p JOIN episode e ON e.id = p.episode_id
                    JOIN media_file m ON m.id = e.media_file_id AND m.available
                    JOIN season s ON s.id = e.season_id
                    WHERE p.user_id = ? AND (p.completed OR p.position_seconds > 0) AND (?::bigint IS NULL OR s.anime_id = ?)
                    ORDER BY s.anime_id, p.updated_at DESC, e.id DESC) last
                ORDER BY updated_at DESC, anime_id""",
                rs -> new Activity(rs.getLong(1), rs.getLong(2), rs.getBoolean(3), Jdbc.instant(rs, 4)),
                userId, animeId, animeId);
    }

    /**
     * {@code current} → l'épisode {@code episodeId} lui-même ; sinon le premier épisode suivant non terminé du même
     * groupe (saisons normales ou Spéciaux). Vide s'il n'y en a pas.
     */
    public Optional<Candidate> currentOrNextUnfinished(long animeId, long episodeId, long userId, boolean current) throws SQLException {
        return db.one("WITH o AS (" + VISIBLE_EPISODES + """
                ), cur AS (SELECT rn, season_number FROM o WHERE id = ?)
                SELECT o.id, o.episode_number, o.title, o.season_id, o.season_number, o.anime_id, o.anime_title, o.duration,
                       p.position_seconds, p.duration_seconds, p.completed, p.updated_at
                FROM o CROSS JOIN cur LEFT JOIN playback_progress p ON p.episode_id = o.id AND p.user_id = ?
                WHERE (o.id = ? AND ?) OR (NOT ? AND o.rn > cur.rn AND (o.season_number = 0) = (cur.season_number = 0)
                                           AND NOT coalesce(p.completed, FALSE))
                ORDER BY o.rn LIMIT 1""",
                rs -> new Candidate(episode(rs), Jdbc.integer(rs, 9), rs.getInt(10), rs.getBoolean(11), Jdbc.instant(rs, 12)),
                animeId, episodeId, userId, episodeId, current, current);
    }

    public Optional<Long> animeIdOfEpisode(long episodeId) throws SQLException {
        return db.one("SELECT s.anime_id FROM episode e JOIN season s ON s.id = e.season_id WHERE e.id = ?",
                rs -> rs.getLong(1), episodeId);
    }

    /** Épisode visible qui suit {@code episodeId} dans le même groupe (saisons normales ou Spéciaux), vu ou non. */
    public Optional<VisibleEpisode> following(long animeId, long episodeId) throws SQLException {
        return db.one("WITH o AS (" + VISIBLE_EPISODES + """
                ), cur AS (SELECT rn, season_number FROM o WHERE id = ?)
                SELECT o.id, o.episode_number, o.title, o.season_id, o.season_number, o.anime_id, o.anime_title, o.duration
                FROM o CROSS JOIN cur WHERE o.rn > cur.rn AND (o.season_number = 0) = (cur.season_number = 0)
                ORDER BY o.rn LIMIT 1""", ProgressRepository::episode, animeId, episodeId);
    }

    /** Premier épisode visible : saisons normales d'abord, sinon Spéciaux. */
    public Optional<VisibleEpisode> first(long animeId) throws SQLException {
        return db.one("SELECT * FROM (" + VISIBLE_EPISODES + ") o ORDER BY rn LIMIT 1",
                rs -> new VisibleEpisode(rs.getLong("id"), rs.getInt("episode_number"), rs.getString("title"),
                        rs.getLong("season_id"), rs.getInt("season_number"), rs.getLong("anime_id"), rs.getString("anime_title"),
                        rs.getInt("duration")),
                animeId);
    }

    private static VisibleEpisode episode(ResultSet rs) throws SQLException {
        return new VisibleEpisode(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getLong(4), rs.getInt(5),
                rs.getLong(6), rs.getString(7), rs.getInt(8));
    }
}
