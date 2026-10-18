package fr.plexwish.animeserver.live;

import fr.plexwish.animeserver.media.MediaConfig;
import fr.plexwish.animeserver.media.MediaProbeService;
import fr.plexwish.animeserver.media.ProcessRunner;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Index des images clés (docs/LIVE-REMUX.md §4) : relevé par ffprobe à la première lecture (tout le fichier est lu),
 * gardé en base, refait si le fichier change. Le relevé tourne en tâche de fond ; en attendant, l'API répond
 * « préparation ». Un échec est retenu 10 minutes (pas de nouvel essai à chaque requête du lecteur).
 */
@ApplicationScoped
public class KeyframeIndexService {

    private static final Logger LOG = Logger.getLogger(KeyframeIndexService.class);
    static final Duration FAILURE_MEMORY = Duration.ofMinutes(10);
    /** Sortie de ffprobe : ~30 octets par paquet vidéo, 180 000 paquets pour 2 h à 25 i/s. */
    static final int MAX_OUTPUT = 32 * 1024 * 1024;

    /** État de l'index d'un fichier. */
    public sealed interface Status permits Ready, Computing, Failed {
    }

    public record Ready(LivePlan plan) implements Status {
    }

    public record Computing() implements Status {
    }

    public record Failed(String reason) implements Status {
    }

    /** Sortie de ffprobe lue : instants des images clés et durée (ms). */
    record Parsed(int[] timesMs, int durationMs) {
    }

    private record Failure(String reason, Instant at, long size, java.time.OffsetDateTime modified) {
    }

    @Inject
    LiveRepository repository;
    @Inject
    MediaProbeService probe;
    @Inject
    MediaConfig config;

    private final Map<Long, Boolean> computing = new ConcurrentHashMap<>();
    private final Map<Long, Failure> failures = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "live-keyframes");
        t.setDaemon(true);
        return t;
    });

    void onStop(@Observes ShutdownEvent event) {
        executor.shutdownNow();
    }

    /** Index à jour, relevé en cours (lancé si besoin), ou échec récent. */
    public Status status(LiveRepository.Source source) throws SQLException {
        Optional<LiveRepository.StoredKeyframes> stored = repository.keyframes(source.mediaFileId());
        if (stored.isPresent() && upToDate(stored.get(), source)) {
            return new Ready(LivePlan.of(stored.get().timesMs(), stored.get().durationMs()));
        }
        Failure f = failures.get(source.mediaFileId());
        if (f != null && f.at().isAfter(Instant.now().minus(FAILURE_MEMORY)) && f.size() == source.fileSize()
                && Objects.equals(f.modified(), source.lastModified())) {
            return new Failed(f.reason());
        }
        if (computing.putIfAbsent(source.mediaFileId(), Boolean.TRUE) == null) {
            executor.submit(() -> {
                try {
                    compute(source);
                } finally {
                    computing.remove(source.mediaFileId());
                }
            });
        }
        return new Computing();
    }

    private static boolean upToDate(LiveRepository.StoredKeyframes k, LiveRepository.Source s) {
        return k.probedSize() == s.fileSize() && Objects.equals(k.probedModified(), s.lastModified()) && k.timesMs().length > 0;
    }

    /** Relevé (bloquant) ; résultat enregistré en base, ou échec retenu. */
    void compute(LiveRepository.Source source) {
        long id = source.mediaFileId();
        try {
            Optional<Path> file = source.relativePath() == null ? Optional.empty() : probe.resolve(source.relativePath());
            if (file.isEmpty()) {
                fail(source, "fichier introuvable");
                return;
            }
            List<String> cmd = List.of(config.ffprobePath(), "-v", "error", "-hide_banner", "-select_streams", "v:0",
                    "-show_entries", "packet=pts_time,dts_time,flags:format=duration", "-of", "csv=p=0",
                    "-i", "file:" + file.get());
            long start = System.nanoTime();
            ProcessRunner.Result r = ProcessRunner.run(cmd, config.liveIndexTimeout(), MAX_OUTPUT, null);
            if (!r.ok()) {
                fail(source, r.timedOut() ? "délai dépassé" : "ffprobe a échoué (code " + r.exitCode() + ")");
                return;
            }
            Parsed p = parse(r.stdout());
            if (p.timesMs().length == 0) {
                fail(source, "aucune image clé");
                return;
            }
            repository.saveKeyframes(id, source.fileSize(), source.lastModified(), p.timesMs(), p.durationMs());
            failures.remove(id);
            LOG.infof("Lecture à la volée : %d images clés relevées pour le fichier %d (%d ms)", p.timesMs().length, id,
                    (System.nanoTime() - start) / 1_000_000);
        } catch (IOException e) {
            fail(source, "ffprobe introuvable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (SQLException | RuntimeException e) {
            LOG.errorf(e, "Lecture à la volée : relevé des images clés impossible pour le fichier %d", id);
            fail(source, "erreur interne");
        }
    }

    private void fail(LiveRepository.Source s, String reason) {
        LOG.infof("Lecture à la volée : pas d'index pour le fichier %d (%s)", s.mediaFileId(), reason);
        failures.put(s.mediaFileId(), new Failure(reason, Instant.now(), s.fileSize(), s.lastModified()));
    }

    /**
     * Lignes {@code pts,dts,drapeaux} des paquets vidéo (pts « N/A » dans un AVI : dts), puis une ligne {@code durée}.
     * Seules les images clés (drapeau K) sont gardées, triées, sans doublon.
     */
    static Parsed parse(String csv) {
        int[] times = new int[1024];
        int n = 0;
        int duration = 0;
        for (String line : csv.split("\n")) {
            String[] f = line.strip().split(",");
            if (f.length == 1 && !f[0].isEmpty()) {
                Double d = seconds(f[0]);
                if (d != null) {
                    duration = (int) Math.round(d * 1000);
                }
                continue;
            }
            if (f.length < 3 || !f[2].contains("K")) {
                continue;
            }
            Double t = seconds(f[0]);
            if (t == null) {
                t = seconds(f[1]);
            }
            if (t == null) {
                continue;
            }
            if (n == times.length) {
                times = Arrays.copyOf(times, n * 2);
            }
            times[n++] = (int) Math.round(t * 1000);
        }
        int[] sorted = Arrays.stream(Arrays.copyOf(times, n)).sorted().distinct().toArray();
        if (duration == 0 && sorted.length > 0) {
            duration = sorted[sorted.length - 1];
        }
        return new Parsed(sorted, duration);
    }

    private static Double seconds(String s) {
        if (s == null || s.isBlank() || s.equals("N/A")) {
            return null;
        }
        try {
            double d = Double.parseDouble(s);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
