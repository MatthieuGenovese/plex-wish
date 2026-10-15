package fr.plexwish.animeserver.progress;

import fr.plexwish.animeserver.progress.ProgressRepository.Activity;
import fr.plexwish.animeserver.progress.ProgressRepository.VisibleEpisode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.SQLException;
import java.time.Instant;
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

    @Inject
    ProgressRepository progress;

    /** Une entrée par animé en cours (dernière activité d'abord), au plus {@code limit}. Les animés finis sont absents. */
    public List<Target> continueWatching(long userId, int limit) throws SQLException {
        List<Target> out = new ArrayList<>();
        for (Activity a : progress.activities(userId, null)) {
            if (out.size() >= limit) {
                break;
            }
            next(userId, a).ifPresent(out::add);
        }
        return out;
    }

    /** Épisode proposé sur la fiche d'un animé (jamais vide si l'animé a un épisode visible). */
    public Optional<Target> forAnime(long userId, long animeId) throws SQLException {
        List<Activity> last = progress.activities(userId, animeId);
        if (!last.isEmpty()) {
            Optional<Target> t = next(userId, last.get(0));
            if (t.isPresent()) {
                return t;
            }
            return first(animeId, Kind.REWATCH);
        }
        return first(animeId, Kind.START);
    }

    /**
     * Pas fini → cet épisode ; fini → le premier épisode suivant non terminé du même groupe (saisons normales ou
     * Spéciaux). Vide si l'animé est fini.
     */
    private Optional<Target> next(long userId, Activity a) throws SQLException {
        return progress.currentOrNextUnfinished(a.animeId(), a.episodeId(), userId, !a.completed()).map(c -> {
            Integer position = c.positionSeconds();
            boolean started = position != null && position > 0 && !c.completed();
            int duration = started ? c.progressDurationSeconds() : c.episode().durationSeconds();
            return target(started ? Kind.RESUME : Kind.NEXT, c.episode(), started ? position : 0, duration,
                    started ? c.updatedAt() : a.updatedAt());
        });
    }

    /**
     * Épisode qui suit {@code episodeId} dans l'ordre de l'animé, dans le même groupe (saisons normales ou Spéciaux),
     * qu'il soit vu ou non (lecteur web : « Épisode suivant »). Vide pour le dernier.
     */
    public Optional<Target> following(long episodeId) throws SQLException {
        Optional<Long> animeId = progress.animeIdOfEpisode(episodeId);
        if (animeId.isEmpty()) {
            return Optional.empty();
        }
        return progress.following(animeId.get(), episodeId)
                .map(e -> target(Kind.NEXT, e, 0, e.durationSeconds(), null));
    }

    /** Premier épisode : saisons normales d'abord, sinon Spéciaux. */
    private Optional<Target> first(long animeId, Kind kind) throws SQLException {
        return progress.first(animeId).map(e -> target(kind, e, 0, e.durationSeconds(), null));
    }

    private static Target target(Kind kind, VisibleEpisode e, int position, int duration, Instant updatedAt) {
        return new Target(kind, e.id(), e.episodeNumber(), e.title(), e.seasonId(), e.seasonNumber(), e.animeId(),
                e.animeTitle(), position, duration, updatedAt);
    }
}
