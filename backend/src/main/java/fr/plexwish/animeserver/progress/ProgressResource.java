package fr.plexwish.animeserver.progress;

import fr.plexwish.animeserver.common.ApiException;
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

import java.sql.SQLException;
import java.time.Instant;
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

    @Inject
    ProgressRepository progress;
    @Inject
    JsonWebToken jwt;

    @PUT
    @Path("/episodes/{id}/progress")
    @Consumes(MediaType.APPLICATION_JSON)
    public Progress save(@PathParam("id") long episodeId, @Valid @NotNull ProgressRequest request) throws SQLException {
        int duration = request.durationSeconds();
        ProgressRepository.KnownDuration known = progress.visibleDuration(episodeId)
                .orElseThrow(() -> new ApiException(404, "EPISODE_NOT_FOUND", "Épisode introuvable"));
        // Durée analysée (ffprobe, §22) : elle fait foi quand le lecteur annonce une durée incohérente
        // (plus de 5 % et de 10 s d'écart, ex. AVI mal lu), pour la barre et le seuil de 90 %.
        Integer k = known.seconds();
        if (k != null && k > 0 && Math.abs(k - duration) > Math.max(10, k * 0.05)) {
            duration = k;
        }
        int position = Math.min(request.positionSeconds(), duration);
        boolean completed = position > COMPLETED_RATIO * duration; // « au-delà de 90 % »
        Instant updatedAt = progress.upsert(userId(), episodeId, position, duration, completed);
        return new Progress(episodeId, position, duration, completed, updatedAt);
    }

    /** Toute la progression de l'utilisateur (ou d'un animé : {@code ?animeId=}), la plus récente d'abord. */
    @GET
    @Path("/me/progress")
    public List<Progress> mine(@QueryParam("animeId") Long animeId) throws SQLException {
        return progress.mine(userId(), animeId).stream()
                .map(p -> new Progress(p.episodeId(), p.positionSeconds(), p.durationSeconds(), p.completed(), p.updatedAt()))
                .toList();
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
}
