package fr.plexwish.animeserver.poster;

import fr.plexwish.animeserver.common.BackgroundLoop;
import fr.plexwish.animeserver.library.scan.ScanCompleted;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Téléchargement des affiches en tâche de fond, séparé du scan (ARCHITECTURE §17). Sans dossier utilisable en
 * écriture, elle ne démarre pas : les affiches restent chargées depuis leur source, rien ne casse.
 */
@ApplicationScoped
public class PosterWorker extends BackgroundLoop {

    private static final Logger LOG = Logger.getLogger(PosterWorker.class);

    @Inject
    PosterService service;
    @Inject
    PosterStore store;
    @Inject
    PosterConfig config;

    public PosterWorker() {
        super("poster-worker");
    }

    void onStart(@Observes StartupEvent event) {
        if (!config.enabled()) {
            LOG.info("Affiches : téléchargement désactivé (POSTERS_ENABLED=false), affiches distantes");
            return;
        }
        if (!store.usable()) {
            LOG.warnf("Affiches : dossier %s absent ou non accessible en écriture (volume, droits PUID/PGID) :"
                    + " affiches chargées depuis leur source", store.root());
            return;
        }
        try {
            int removed = service.sweep();
            if (removed > 0) {
                LOG.infof("Affiches : %d fichier(s) temporaire(s) ou orphelin(s) supprimé(s)", removed);
            }
        } catch (Exception e) {
            LOG.warnf("Affiches : ménage impossible (%s)", e.getClass().getSimpleName());
        }
        start();
    }

    void onStop(@Observes ShutdownEvent event) {
        stop();
    }

    void onScanCompleted(@Observes ScanCompleted event) {
        wake();
    }

    @Override
    protected Outcome step() throws Exception {
        return switch (service.processNext()) {
            case PosterService.Done d -> new Worked();
            case PosterService.Idle i -> new Idle(config.pollInterval());
            case PosterService.Unavailable u -> new Pause(u.retryAfter());
        };
    }
}
