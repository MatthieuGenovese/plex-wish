package fr.plexwish.animeserver.progress;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Épisode à regarder ensuite, par animé et par utilisateur (phase Polish, ARCHITECTURE §24) :
 * <ul>
 *     <li>la dernière activité sur l'animé (épisode commencé ou terminé, le plus récent) décide ;</li>
 *     <li>commencé et pas fini → {@code RESUME} cet épisode ;</li>
 *     <li>terminé → le premier épisode suivant pas encore terminé ({@code NEXT}, ou {@code RESUME} s'il est
 *     déjà commencé), sans passer des saisons normales aux Spéciaux (ni l'inverse) ;</li>
 *     <li>rien après → l'animé est fini : absent de « Continuer à regarder », {@code REWATCH} sur la fiche ;</li>
 *     <li>aucune activité → {@code START} (fiche seulement) : premier épisode des saisons normales, sinon des Spéciaux.</li>
 * </ul>
 * Seuls les épisodes visibles (fichier disponible) comptent. Ordre des épisodes : saisons 1, 2… puis Spéciaux,
 * par numéro d'épisode.
 */
@ApplicationScoped
public class UpNextService {

    public enum Kind { RESUME, NEXT, START, REWATCH }

    /** Épisode cible. {@code positionSeconds} = 0 sauf {@code RESUME} ; {@code durationSeconds} = 0 si inconnue. */
    public record Target(Kind kind, long episodeId, int episodeNumber, String episodeTitle, long seasonId, int seasonNumber,
                         long animeId, String animeTitle, int positionSeconds, int durationSeconds, Instant updatedAt) {
        public String seasonLabel() {
            return seasonNumber == 0 ? "Spéciaux" : "Saison " + seasonNumber;
        }
    }

    /** Dernière activité de l'utilisateur sur un animé. */
    private record Activity(long animeId, long episodeId, boolean completed, Instant updatedAt) {
    }

    private static final String VISIBLE_EPISODES = """
            SELECT e.id, e.episode_number, e.title, s.id AS season_id, s.season_number, a.id AS anime_id, a.title AS anime_title,
                   coalesce(e.duration_seconds, 0) AS duration,
                   row_number() OVER (ORDER BY (s.season_number = 0), s.season_number, e.episode_number, e.id) AS rn
            FROM episode e JOIN media_file m ON m.id = e.media_file_id AND m.available
            JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id
            WHERE a.id = ?""";

    @Inject
    AgroalDataSource dataSource;

    /** Une entrée par animé en cours (dernière activité d'abord), au plus {@code limit}. Les animés finis sont absents. */
    public List<Target> continueWatching(long userId, int limit) throws SQLException {
        List<Target> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            for (Activity a : activities(c, userId, null)) {
                if (out.size() >= limit) {
                    break;
                }
                next(c, userId, a).ifPresent(out::add);
            }
        }
        return out;
    }

    /** Épisode proposé sur la fiche d'un animé (jamais vide si l'animé a un épisode visible). */
    public Optional<Target> forAnime(long userId, long animeId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            List<Activity> last = activities(c, userId, animeId);
            if (!last.isEmpty()) {
                Optional<Target> t = next(c, userId, last.get(0));
                if (t.isPresent()) {
                    return t;
                }
                return first(c, animeId, Kind.REWATCH);
            }
            return first(c, animeId, Kind.START);
        }
    }

    /** Dernière activité par animé (épisode commencé ou terminé), la plus récente d'abord. */
    private List<Activity> activities(Connection c, long userId, Long animeId) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("""
                SELECT anime_id, episode_id, completed, updated_at FROM (
                    SELECT DISTINCT ON (s.anime_id) s.anime_id, e.id AS episode_id, p.completed, p.updated_at
                    FROM playback_progress p JOIN episode e ON e.id = p.episode_id
                    JOIN media_file m ON m.id = e.media_file_id AND m.available
                    JOIN season s ON s.id = e.season_id
                    WHERE p.user_id = ? AND (p.completed OR p.position_seconds > 0) AND (?::bigint IS NULL OR s.anime_id = ?)
                    ORDER BY s.anime_id, p.updated_at DESC, e.id DESC) last
                ORDER BY updated_at DESC, anime_id""")) {
            st.setLong(1, userId);
            st.setObject(2, animeId);
            st.setObject(3, animeId);
            List<Activity> list = new ArrayList<>();
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    list.add(new Activity(rs.getLong(1), rs.getLong(2), rs.getBoolean(3), instant(rs, 4)));
                }
            }
            return list;
        }
    }

    /**
     * Pas fini → cet épisode ; fini → le premier épisode suivant non terminé du même groupe (saisons normales ou
     * Spéciaux). Vide si l'animé est fini.
     */
    private Optional<Target> next(Connection c, long userId, Activity a) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("WITH o AS (" + VISIBLE_EPISODES + """
                ), cur AS (SELECT rn, season_number FROM o WHERE id = ?)
                SELECT o.id, o.episode_number, o.title, o.season_id, o.season_number, o.anime_id, o.anime_title, o.duration,
                       p.position_seconds, p.duration_seconds, p.completed, p.updated_at
                FROM o CROSS JOIN cur LEFT JOIN playback_progress p ON p.episode_id = o.id AND p.user_id = ?
                WHERE (o.id = ? AND ?) OR (NOT ? AND o.rn > cur.rn AND (o.season_number = 0) = (cur.season_number = 0)
                                           AND NOT coalesce(p.completed, FALSE))
                ORDER BY o.rn LIMIT 1""")) {
            boolean current = !a.completed();
            st.setLong(1, a.animeId());
            st.setLong(2, a.episodeId());
            st.setLong(3, userId);
            st.setLong(4, a.episodeId());
            st.setBoolean(5, current);
            st.setBoolean(6, current);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                int position = rs.getInt(9);
                boolean started = !rs.wasNull() && position > 0 && !rs.getBoolean(11);
                int duration = started ? rs.getInt(10) : rs.getInt(8);
                return Optional.of(new Target(started ? Kind.RESUME : Kind.NEXT, rs.getLong(1), rs.getInt(2), rs.getString(3),
                        rs.getLong(4), rs.getInt(5), rs.getLong(6), rs.getString(7), started ? position : 0, duration,
                        started ? instant(rs, 12) : a.updatedAt()));
            }
        }
    }

    /** Premier épisode : saisons normales d'abord, sinon Spéciaux. */
    private Optional<Target> first(Connection c, long animeId, Kind kind) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT * FROM (" + VISIBLE_EPISODES + ") o ORDER BY rn LIMIT 1")) {
            st.setLong(1, animeId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Target(kind, rs.getLong("id"), rs.getInt("episode_number"), rs.getString("title"),
                        rs.getLong("season_id"), rs.getInt("season_number"), rs.getLong("anime_id"), rs.getString("anime_title"),
                        0, rs.getInt("duration"), null));
            }
        }
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
