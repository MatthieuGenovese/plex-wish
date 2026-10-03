package fr.plexwish.animeserver.common;

import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Fil de fond qui traite un élément à la fois (métadonnées AniList, TMDB, affiches) : dort quand il n'y a rien
 * à faire, se met en pause quand la source est indisponible, et peut être réveillé (fin de scan, action admin).
 * Tout l'état est en base : après un redémarrage, le fil reprend où il en était.
 */
public abstract class BackgroundLoop {

    /** Résultat d'un pas. */
    public sealed interface Outcome permits Worked, Idle, Pause {
    }

    /** Un élément traité : on enchaîne. */
    public record Worked() implements Outcome {
    }

    /** Rien à faire : attente (interrompue par un réveil). */
    public record Idle(Duration delay) implements Outcome {
    }

    /** Source indisponible : attente non interrompue. */
    public record Pause(Duration delay) implements Outcome {
    }

    private final Logger log;
    private final String name;
    private final Object signal = new Object();
    private volatile boolean running;
    private volatile boolean wakeRequested;
    private Thread thread;

    protected BackgroundLoop(String name) {
        this.name = name;
        this.log = Logger.getLogger(getClass());
    }

    /** Un pas de travail. Une exception est journalisée et suivie d'une minute de pause. */
    protected abstract Outcome step() throws Exception;

    protected void start() {
        running = true;
        thread = Thread.ofPlatform().daemon().name(name).start(this::loop);
    }

    protected void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    public boolean running() {
        return running;
    }

    public void wake() {
        synchronized (signal) {
            wakeRequested = true;
            signal.notifyAll();
        }
    }

    private void loop() {
        log.infof("%s : tâche de fond démarrée", name);
        while (running) {
            try {
                switch (step()) {
                    case Worked w -> {
                    }
                    case Idle i -> sleep(i.delay(), true);
                    case Pause p -> sleep(p.delay(), false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warnf(e, "%s : erreur inattendue, nouvel essai dans 1 min", name);
                try {
                    sleep(Duration.ofMinutes(1), false);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void sleep(Duration d, boolean wakeable) throws InterruptedException {
        synchronized (signal) {
            if (wakeable && wakeRequested) {
                wakeRequested = false;
                return;
            }
            long end = System.nanoTime() + d.toNanos();
            long left;
            while (running && (left = end - System.nanoTime()) > 0) {
                signal.wait(Math.max(1, left / 1_000_000));
                if (wakeable && wakeRequested) {
                    wakeRequested = false;
                    return;
                }
            }
        }
    }
}
