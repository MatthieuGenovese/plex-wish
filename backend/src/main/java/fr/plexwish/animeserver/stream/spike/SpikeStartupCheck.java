package fr.plexwish.animeserver.stream.spike;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.nio.file.Path;

@ApplicationScoped
class SpikeStartupCheck {

    private static final Logger LOG = Logger.getLogger(SpikeStartupCheck.class);

    void onStart(@Observes StartupEvent event, SpikeStreamConfig config) {
        if (!config.enabled()) {
            return;
        }
        DevMediaIndex index = new DevMediaIndex(Path.of(config.mediaDir()));
        LOG.warnf("Spike vidéo ACTIF : /api/dev/* est accessible SANS authentification. Dossier : %s (%d vidéo(s))",
                index.root(), index.list().size());
    }
}
