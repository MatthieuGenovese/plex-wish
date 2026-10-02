package fr.plexwish.animeserver.metadata;

import fr.plexwish.animeserver.library.scan.ScanCompleted;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Tâche de fond des métadonnées : un fil dédié, séparé du scan. Elle traite les animés à apparier un par un
 * (le fournisseur impose l'espacement des appels), se met en pause si le fournisseur est indisponible ou limite
 * le débit, et dort quand il n'y a rien à faire ; la fin d'un scan ou une action de l'admin la réveille.
 * Reprise après un arrêt : tout l'état est en base, elle repart sur ce qui reste à faire.
 */
@ApplicationScoped
public class MetadataWorker {

    private static final Logger LOG = Logger.getLogger(MetadataWorker.class);

    @Inject
    MetadataService service;
    @Inject
    MetadataConfig config;

    private final Object signal = new Object();
    private volatile boolean running;
    private volatile boolean wakeRequested;
    private Thread thread;

    void onStart(@Observes StartupEvent event) {
        if (!config.enabled()) {
            LOG.info("Métadonnées : tâche de fond désactivée (anime.metadata.enabled=false)");
            return;
        }
        running = true;
        thread = Thread.ofPlatform().daemon().name("metadata-worker").start(this::loop);
    }

    void onStop(@Observes ShutdownEvent event) {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    void onScanCompleted(@Observes ScanCompleted event) {
        wake();
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** Réveille la tâche (nouveaux animés, relance demandée par l'admin). */
    public void wake() {
        synchronized (signal) {
            wakeRequested = true;
            signal.notifyAll();
        }
    }

    private void loop() {
        LOG.info("Métadonnées : tâche de fond démarrée");
        int done = 0;
        while (running) {
            try {
                MetadataService.Step step = service.processNext();
                switch (step) {
                    case MetadataService.Done d -> {
                        if (++done % 50 == 0) {
                            LOG.infof("Métadonnées : %d animés traités depuis le démarrage", done);
                        }
                    }
                    case MetadataService.Idle i -> sleep(config.pollInterval(), true);
                    case MetadataService.Unavailable u -> sleep(u.retryAfter(), false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Base indisponible ou bug : on ne tue pas le fil, on réessaie plus tard.
                LOG.warn("Métadonnées : erreur inattendue, nouvel essai dans 1 min", e);
                try {
                    sleep(Duration.ofMinutes(1), false);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Attend {@code d} ; si {@code wakeable}, un réveil (scan, admin) interrompt l'attente. */
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
