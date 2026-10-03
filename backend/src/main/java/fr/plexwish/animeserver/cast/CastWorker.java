package fr.plexwish.animeserver.cast;

import fr.plexwish.animeserver.common.BackgroundLoop;
import fr.plexwish.animeserver.library.scan.ScanCompleted;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/** Tâche de fond de la distribution (ARCHITECTURE §19), séparée du scan et des métadonnées. */
@ApplicationScoped
public class CastWorker extends BackgroundLoop {

    private static final Logger LOG = Logger.getLogger(CastWorker.class);

    @Inject
    CastService service;
    @Inject
    CastConfig config;

    public CastWorker() {
        super("cast-worker");
    }

    void onStart(@Observes StartupEvent event) {
        if (!config.enabled()) {
            LOG.info("Distribution : tâche désactivée (CAST_ENABLED=false)");
            return;
        }
        if (!service.store().usable()) {
            LOG.warnf("Distribution : dossier %s non accessible en écriture : images chargées depuis AniList",
                    service.store().root());
        } else {
            try {
                int removed = service.sweep();
                if (removed > 0) {
                    LOG.infof("Distribution : %d fichier(s) temporaire(s) ou orphelin(s) supprimé(s)", removed);
                }
            } catch (Exception e) {
                LOG.warnf("Distribution : ménage impossible (%s)", e.getClass().getSimpleName());
            }
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
            case CastService.Done d -> new Worked();
            // Les métadonnées passent avant : on revient voir dans une minute.
            case CastService.Idle i -> new Idle(i.yielded() ? config.yieldInterval() : config.pollInterval());
            case CastService.Unavailable u -> new Pause(u.retryAfter());
        };
    }
}
