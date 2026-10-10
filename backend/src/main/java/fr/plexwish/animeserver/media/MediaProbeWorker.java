package fr.plexwish.animeserver.media;

import fr.plexwish.animeserver.common.BackgroundLoop;
import fr.plexwish.animeserver.library.scan.ScanCompleted;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Tâche de fond de l'analyse ffprobe (ARCHITECTURE §22.2) : séparée du scan et en pause pendant un scan ou un test à
 * blanc du remux ; un fichier à la fois ; reprend où elle en était (état en base).
 */
@ApplicationScoped
public class MediaProbeWorker extends BackgroundLoop {

    private static final Logger LOG = Logger.getLogger(MediaProbeWorker.class);

    @Inject
    MediaProbeService service;
    @Inject
    MediaConfig config;
    @Inject
    RemuxTestService remuxTest;
    @Inject
    RemuxService remux;
    @Inject
    fr.plexwish.animeserver.webplay.WebPrepService webPrep;

    private volatile boolean syncNeeded = true;
    private volatile String state = "idle";

    public MediaProbeWorker() {
        super("media-probe");
    }

    void onStart(@Observes StartupEvent event) {
        if (!config.probeEnabled()) {
            LOG.info("Analyse média : désactivée (MEDIA_PROBE_ENABLED=false)");
            return;
        }
        String version = service.ffprobeVersion();
        if (version == null) {
            LOG.warnf("Analyse média : ffprobe introuvable (%s) : pas d'analyse", config.ffprobePath());
            return;
        }
        LOG.infof("Analyse média : %s", version);
        start();
    }

    void onStop(@Observes ShutdownEvent event) {
        stop();
    }

    void onScanCompleted(@Observes ScanCompleted event) {
        syncNeeded = true;
        wake();
    }

    /** « scan » / « remux » / « web » / « remux-test » (en pause), « working », « idle ». */
    public String state() {
        return running() ? state : "stopped";
    }

    @Override
    protected Outcome step() throws Exception {
        if (service.scanRunning()) {
            state = "scan";
            return new Idle(Duration.ofMinutes(1));
        }
        if (remux.busy()) {
            state = "remux";
            return new Idle(Duration.ofSeconds(30));
        }
        if (webPrep.busy()) {
            state = "web";
            return new Idle(Duration.ofSeconds(30));
        }
        if (remuxTest.running()) {
            state = "remux-test";
            return new Idle(Duration.ofMinutes(1));
        }
        if (syncNeeded) {
            syncNeeded = false;
            int n = service.syncDurations();
            if (n > 0) {
                LOG.infof("Analyse média : durée de %d épisode(s) mise à jour", n);
            }
        }
        if (service.reclassifyOutdated(500) > 0) {
            state = "working";
            return new Worked();
        }
        var work = service.nextPending();
        if (work.isEmpty()) {
            state = "idle";
            return new Idle(config.pollInterval());
        }
        state = "working";
        service.probe(work.get());
        return new Worked();
    }
}
