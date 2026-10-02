package fr.plexwish.animeserver.metadata;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Espacement minimal entre deux appels au fournisseur, et pause imposée par le fournisseur (429 Retry-After,
 * quota épuisé). Un seul appel à la fois : la tâche de fond et une correction manuelle de l'admin partagent
 * le même limiteur.
 */
public class RateLimiter {

    /** Attente (remplaçable dans les tests). */
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private final Duration minInterval;
    private final Clock clock;
    private final Sleeper sleeper;
    private Instant nextAllowed = Instant.MIN;

    public RateLimiter(Duration minInterval, Clock clock, Sleeper sleeper) {
        this.minInterval = minInterval;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** Attend le moment autorisé puis réserve le créneau suivant. */
    public synchronized void acquire() throws InterruptedException {
        Instant now = clock.instant();
        if (now.isBefore(nextAllowed)) {
            sleeper.sleep(Duration.between(now, nextAllowed));
            now = clock.instant().isAfter(nextAllowed) ? clock.instant() : nextAllowed;
        }
        nextAllowed = now.plus(minInterval);
    }

    /** Le fournisseur demande d'attendre : aucun appel avant {@code until}. */
    public synchronized void pauseUntil(Instant until) {
        if (until.isAfter(nextAllowed)) {
            nextAllowed = until;
        }
    }

    /** Fin de la pause en cours (pour l'affichage admin), ou null. */
    public synchronized Instant pausedUntil() {
        return nextAllowed.isAfter(clock.instant().plus(minInterval)) ? nextAllowed : null;
    }
}
