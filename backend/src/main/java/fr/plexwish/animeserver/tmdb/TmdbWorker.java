package fr.plexwish.animeserver.tmdb;

import fr.plexwish.animeserver.common.BackgroundLoop;
import fr.plexwish.animeserver.library.scan.ScanCompleted;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/** Tâche de fond TMDB (ARCHITECTURE §16.5). Inactive sans clé : le démarrage n'échoue jamais pour autant. */
@ApplicationScoped
public class TmdbWorker extends BackgroundLoop {

    private static final Logger LOG = Logger.getLogger(TmdbWorker.class);

    @Inject
    TmdbService service;
    @Inject
    TmdbConfig config;
    @Inject
    TmdbCredentials credentials;

    public TmdbWorker() {
        super("tmdb-worker");
    }

    void onStart(@Observes StartupEvent event) {
        if (!credentials.configured()) {
            LOG.info("TMDB : pas de clé (à saisir dans Administration > Réglages), synopsis anglais d'AniList uniquement");
            return;
        }
        ensureStarted();
    }

    /** Démarre la tâche si une clé existe (appelé aussi quand l'admin en saisit une). */
    public synchronized void ensureStarted() {
        if (config.workerEnabled() && credentials.configured() && !running()) {
            start();
        } else {
            wake();
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        stop();
    }

    void onScanCompleted(@Observes ScanCompleted event) {
        wake();
    }

    @Override
    protected Outcome step() throws Exception {
        if (!credentials.configured()) {
            return new Idle(config.pollInterval()); // clé retirée dans l'interface
        }
        return switch (service.processNext()) {
            case TmdbService.Done d -> new Worked();
            case TmdbService.Idle i -> new Idle(config.pollInterval());
            case TmdbService.Unavailable u -> new Pause(u.retryAfter());
        };
    }
}
