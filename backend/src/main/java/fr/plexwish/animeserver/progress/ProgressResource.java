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

    /**
     * Entrée « continuer à regarder » : de quoi afficher et relancer l'épisode sans autre appel. {@code kind} :
     * {@code RESUME} ou {@code NEXT} ; {@code durationSeconds} = 0 si la durée n'est pas connue.
     */
    public record ContinueWatching(long episodeId, int episodeNumber, String episodeTitle,
                                   long seasonId, int seasonNumber, String seasonLabel,
                                   long animeId, String animeTitle,
                                   int positionSeconds, int durationSeconds, Instant updatedAt, String posterUrl,
                                   String kind) {
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
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement st = c.prepareStatement("SELECT e.duration_seconds " + VISIBLE + " WHERE e.id = ?")) {
                st.setLong(1, episodeId);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw new ApiException(404, "EPISODE_NOT_FOUND", "Épisode introuvable");
                    }
                    // Durée analysée (ffprobe, §22) : elle fait foi quand le lecteur annonce une durée incohérente
                    // (plus de 5 % et de 10 s d'écart, ex. AVI mal lu), pour la barre et le seuil de 90 %.
                    int known = rs.getInt(1);
                    if (!rs.wasNull() && known > 0 && Math.abs(known - duration) > Math.max(10, known * 0.05)) {
                        duration = known;
                    }
                }
            }
            int position = Math.min(request.positionSeconds(), duration);
            boolean completed = position > COMPLETED_RATIO * duration; // « au-delà de 90 % »
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

    /**
     * « Continuer à regarder » : une entrée par animé en cours, de la dernière activité à la plus ancienne
     * (ARCHITECTURE §24). {@code kind} = {@code RESUME} (épisode commencé, à reprendre à {@code positionSeconds})
     * ou {@code NEXT} (épisode suivant d'un épisode terminé, à lire depuis le début). Animés finis : absents.
     */
    @GET
    @Path("/me/continue-watching")
    public List<ContinueWatching> continueWatching(@QueryParam("limit") @DefaultValue("20") @Min(1) @Max(100) int limit)
            throws SQLException {
        List<UpNextService.Target> list = upNext.continueWatching(userId(), limit);
        // Affiche : même résolution que la bibliothèque (fichier sur le NAS, sinon URL distante), ARCHITECTURE §17.
        var urls = posters.urls(list.stream().map(UpNextService.Target::animeId).distinct().toList());
        return list.stream().map(t -> {
            var u = urls.get(t.animeId());
            return new ContinueWatching(t.episodeId(), t.episodeNumber(), t.episodeTitle(), t.seasonId(), t.seasonNumber(),
                    t.seasonLabel(), t.animeId(), t.animeTitle(), t.positionSeconds(), t.durationSeconds(), t.updatedAt(),
                    u == null ? null : u.small(), t.kind().name());
        }).toList();
    }

    @Inject
    UpNextService upNext;

    @Inject
    fr.plexwish.animeserver.poster.PosterService posters;

    private long userId() {
        return Long.parseLong(jwt.getSubject());
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
