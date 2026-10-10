package fr.plexwish.animeserver.metadata;

import fr.plexwish.animeserver.common.BackgroundLoop;
import fr.plexwish.animeserver.library.scan.ScanCompleted;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Tâche de fond des métadonnées AniList, séparée du scan (ARCHITECTURE §15.3) : un animé à la fois, pause si
 * AniList est indisponible ou limite le débit, réveil à la fin de chaque scan ou sur action de l'admin.
 */
@ApplicationScoped
public class MetadataWorker extends BackgroundLoop {

    private static final Logger LOG = Logger.getLogger(MetadataWorker.class);

    @Inject
    MetadataService service;
    @Inject
    MetadataConfig config;
    @Inject
    fr.plexwish.animeserver.cast.CastWorker castWorker;

    /** Vrai si des animés ont été appariés depuis le dernier passage sans travail. */
    private boolean workedSinceIdle;

    public MetadataWorker() {
        super("metadata-worker");
    }

    void onStart(@Observes StartupEvent event) {
        if (!config.enabled()) {
            LOG.info("Métadonnées : tâche de fond désactivée (anime.metadata.enabled=false)");
            return;
        }
        start();
    }

    void onStop(@Observes ShutdownEvent event) {
        stop();
    }

    void onScanCompleted(@Observes ScanCompleted event) {
        wake();
    }

    public boolean enabled() {
        return config.enabled();
    }

    @Override
    protected Outcome step() throws Exception {
        MetadataService.Step step = service.processNext();
        if (step instanceof MetadataService.Idle) {
            step = service.backfillGenres(); // rien à apparier : genres des fiches plus anciennes (§24.6)
        }
        if (step instanceof MetadataService.Done) {
            workedSinceIdle = true;
        } else if (step instanceof MetadataService.Idle && workedSinceIdle) {
            // Métadonnées terminées : la distribution, qui leur cède la place, peut commencer sans attendre son
            // prochain passage (jusqu'à 10 minutes).
            workedSinceIdle = false;
            castWorker.wake();
        }
        return switch (step) {
            case MetadataService.Done d -> new Worked();
            case MetadataService.Idle i -> new Idle(config.pollInterval());
            case MetadataService.Unavailable u -> new Pause(u.retryAfter());
        };
    }
}
