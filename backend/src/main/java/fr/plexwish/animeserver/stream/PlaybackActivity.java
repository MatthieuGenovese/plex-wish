package fr.plexwish.animeserver.stream;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;

/**
 * « Quelqu'un regarde en ce moment » (docs/WEB-PLAYER.md §4.6) : dernière requête de lecture servie (original,
 * copie remux, copie web). Une lecture dans les 60 dernières secondes suspend les conversions qui ne sont pas
 * attendues devant un écran. Rien n'est enregistré : une valeur en mémoire.
 */
@ApplicationScoped
public class PlaybackActivity {

    static final Duration WINDOW = Duration.ofSeconds(60);

    private volatile long lastNanos = Long.MIN_VALUE;
    private volatile Boolean forced;

    /** Une requête de lecture vient d'être servie. */
    public void mark() {
        lastNanos = System.nanoTime();
    }

    public boolean active() {
        Boolean f = forced;
        if (f != null) {
            return f;
        }
        long last = lastNanos;
        return last != Long.MIN_VALUE && System.nanoTime() - last < WINDOW.toNanos();
    }

    /** Tests seulement : force l'état (null = mesure normale). */
    public void forceForTests(Boolean active) {
        forced = active;
    }
}
