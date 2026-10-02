package fr.plexwish.animeserver.progress;

import fr.plexwish.animeserver.common.ApiException;
import io.agroal.api.AgroalDataSource;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Progression de lecture, propre à chaque utilisateur (ARCHITECTURE §6.3). Le lecteur envoie régulièrement
 * sa position ; un épisode est terminé au-delà de 90 % de sa durée. Seuls les épisodes visibles (fichier
 * disponible) apparaissent dans les listes.
 */
@Path("/api")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class ProgressResource {

    /** Au-delà de cette part de la durée, l'épisode est terminé (génériques de fin, aperçu…). */
    public static final double COMPLETED_RATIO = 0.9;

    /** Position et durée en secondes (une position au-delà de la durée est ramenée à la durée). */
    public record ProgressRequest(@NotNull @Min(0) @Max(86_400) Integer positionSeconds,
                                  @NotNull @Min(1) @Max(86_400) Integer durationSeconds) {
    }

    public record Progress(long episodeId, int positionSeconds, int durationSeconds, boolean completed, Instant updatedAt) {
    }

    /** Entrée « continuer à regarder » : de quoi afficher et relancer l'épisode sans autre appel. */
    public record ContinueWatching(long episodeId, int episodeNumber, String episodeTitle,
                                   long seasonId, int seasonNumber, String seasonLabel,
                                   long animeId, String animeTitle,
                                   int positionSeconds, int durationSeconds, Instant updatedAt) {
    }

    private static final String VISIBLE = """
            FROM episode e JOIN media_file m ON m.id = e.media_file_id AND m.available
            JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id""";

    @Inject
    AgroalDataSource dataSource;
    @Inject
    JsonWebToken jwt;

    @PUT
    @Path("/episodes/{id}/progress")
    @Consumes(MediaType.APPLICATION_JSON)
    public Progress save(@PathParam("id") long episodeId, @Valid @NotNull ProgressRequest request) throws SQLException {
        int duration = request.durationSeconds();
        int position = Math.min(request.positionSeconds(), duration);
        boolean completed = position > COMPLETED_RATIO * duration; // « au-delà de 90 % »
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement st = c.prepareStatement("SELECT 1 " + VISIBLE + " WHERE e.id = ?")) {
                st.setLong(1, episodeId);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw new ApiException(404, "EPISODE_NOT_FOUND", "Épisode introuvable");
                    }
                }
            }
            try (PreparedStatement st = c.prepareStatement("""
                    INSERT INTO playback_progress (user_id, episode_id, position_seconds, duration_seconds, completed, updated_at)
                    VALUES (?, ?, ?, ?, ?, now())
                    ON CONFLICT (user_id, episode_id) DO UPDATE SET position_seconds = EXCLUDED.position_seconds,
                        duration_seconds = EXCLUDED.duration_seconds, completed = EXCLUDED.completed, updated_at = now()
                    RETURNING updated_at""")) {
                st.setLong(1, userId());
                st.setLong(2, episodeId);
                st.setInt(3, position);
                st.setInt(4, duration);
                st.setBoolean(5, completed);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    return new Progress(episodeId, position, duration, completed, instant(rs, 1));
                }
            }
        }
    }

    /** Toute la progression de l'utilisateur (ou d'un animé : {@code ?animeId=}), la plus récente d'abord. */
    @GET
    @Path("/me/progress")
    public List<Progress> mine(@QueryParam("animeId") Long animeId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT p.episode_id, p.position_seconds, p.duration_seconds,"
                     + " p.completed, p.updated_at FROM playback_progress p JOIN LATERAL (SELECT a.id AS anime_id " + VISIBLE
                     + " WHERE e.id = p.episode_id) v ON TRUE"
                     + " WHERE p.user_id = ? AND (?::bigint IS NULL OR v.anime_id = ?) ORDER BY p.updated_at DESC, p.episode_id")) {
            st.setLong(1, userId());
            st.setObject(2, animeId);
            st.setObject(3, animeId);
            List<Progress> list = new ArrayList<>();
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    list.add(new Progress(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getBoolean(4), instant(rs, 5)));
                }
            }
            return list;
        }
    }

    /** Épisodes commencés (position > 0) et non terminés, du plus récemment regardé au plus ancien. */
    @GET
    @Path("/me/continue-watching")
    public List<ContinueWatching> continueWatching(@QueryParam("limit") @DefaultValue("20") @Min(1) @Max(100) int limit)
            throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT e.id, e.episode_number, e.title, s.id, s.season_number, a.id, a.title,
                            p.position_seconds, p.duration_seconds, p.updated_at
                     FROM playback_progress p JOIN episode e ON e.id = p.episode_id
                     JOIN media_file m ON m.id = e.media_file_id AND m.available
                     JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id
                     WHERE p.user_id = ? AND NOT p.completed AND p.position_seconds > 0
                     ORDER BY p.updated_at DESC, e.id LIMIT ?""")) {
            st.setLong(1, userId());
            st.setInt(2, limit);
            List<ContinueWatching> list = new ArrayList<>();
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    int season = rs.getInt(5);
                    list.add(new ContinueWatching(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getLong(4), season,
                            season == 0 ? "Spéciaux" : "Saison " + season, rs.getLong(6), rs.getString(7),
                            rs.getInt(8), rs.getInt(9), instant(rs, 10)));
                }
            }
            return list;
        }
    }

    private long userId() {
        return Long.parseLong(jwt.getSubject());
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
