package fr.plexwish.animeserver.media;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test à blanc du remux (ARCHITECTURE §22.5) : chaque fichier « remux nécessaire » passe dans ffmpeg avec chaque
 * commande candidate, vers une **sortie nulle** (`/dev/null` : le MKV est fabriqué puis jeté, rien n'est écrit).
 * Mesure le taux de réussite sur le vrai catalogue avant de fixer la commande de la phase 9.2. Lancé par l'admin,
 * un fichier à la fois, priorité basse, arrêt possible ; reprend là où il s'était arrêté (résultats en base).
 */
@ApplicationScoped
public class RemuxTestService {

    private static final Logger LOG = Logger.getLogger(RemuxTestService.class);

    /** Commandes candidates (validées à la main sur le S24, à confirmer sur le catalogue). */
    public enum Variant {
        GENPTS(List.of("-fflags", "+genpts"), List.of()),
        GENPTS_UNPACK(List.of("-fflags", "+genpts"), List.of("-bsf:v", "mpeg4_unpack_bframes"));

        final List<String> input;
        final List<String> output;

        Variant(List<String> input, List<String> output) {
            this.input = input;
            this.output = output;
        }

        /** mpeg4_unpack_bframes ne s'applique qu'au MPEG-4 ASP (Xvid/DivX). */
        boolean appliesTo(String videoCodec) {
            return this != GENPTS_UNPACK || "mpeg4".equals(videoCodec);
        }
    }

    public record Status(boolean running, Instant startAt, Instant startedAt, String current, long total, long done,
                         long bytesDone, long bytesTotal, java.util.Map<String, long[]> perVariant, Long estimatedSecondsLeft,
                         String lastError) {
    }

    record Item(long mediaFileId, String relativePath, long size, String videoCodec, List<Variant> todo) {
    }

    @Inject
    RemuxRepository repository;
    @Inject
    MediaConfig config;
    @Inject
    MediaProbeService probe;
    @Inject
    RemuxService remux;

    private final AtomicReference<Process> process = new AtomicReference<>();
    private volatile Thread thread;
    private volatile boolean stopRequested;
    private volatile Instant startAt;
    private volatile Instant startedAt;
    private volatile String current;
    private volatile long bytesThisRun;
    private volatile long msThisRun;
    private volatile String lastError;
    /** Un remux demandé par un utilisateur arrive : la commande en cours est arrêtée (sans résultat) et refaite après. */
    private volatile boolean yielded;

    public boolean running() {
        Thread t = thread;
        return t != null && t.isAlive();
    }

    /** Démarre (ou programme : {@code at} dans les 24 h). Refusé si déjà lancé ou si ffmpeg manque. */
    public synchronized boolean start(Instant at) {
        if (running()) {
            return false;
        }
        stopRequested = false;
        startAt = at != null && at.isAfter(Instant.now()) ? at : null;
        startedAt = null;
        bytesThisRun = 0;
        msThisRun = 0;
        lastError = null;
        thread = Thread.ofPlatform().daemon().name("remux-test").start(this::run);
        return true;
    }

    /** Priorité aux remux des utilisateurs : arrête la commande en cours, le test attend que la file soit vide. */
    public void yieldTo() {
        if (!running()) {
            return;
        }
        yielded = true;
        Process p = process.get();
        if (p != null) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    public synchronized void stop() {
        stopRequested = true;
        Process p = process.get();
        if (p != null) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    void onShutdown(@Observes ShutdownEvent e) {
        stop();
    }

    /** Efface les résultats (pour tout refaire, par exemple après un changement de version de ffmpeg). */
    public int reset() throws SQLException {
        if (running()) {
            return -1;
        }
        return repository.deleteTestResults();
    }

    private void run() {
        try {
            while (startAt != null && Instant.now().isBefore(startAt) && !stopRequested) {
                Thread.sleep(Math.min(30_000, Math.max(10, Duration.between(Instant.now(), startAt).toMillis())));
            }
            startAt = null;
            startedAt = Instant.now();
            LOG.info("Test à blanc du remux : démarré");
            while (!stopRequested) {
                while (remux.busy() && !stopRequested) {
                    current = "en pause : remux demandé par un utilisateur";
                    Thread.sleep(2_000);
                }
                yielded = false;
                Optional<Item> next = next();
                if (next.isEmpty()) {
                    break;
                }
                test(next.get());
            }
            LOG.infof("Test à blanc du remux : %s", stopRequested ? "arrêté" : "terminé");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName();
            LOG.warnf(e, "Test à blanc du remux : erreur inattendue");
        } finally {
            current = null;
        }
    }

    /** Prochain fichier « remux nécessaire » qui n'a pas de résultat pour toutes les commandes applicables. */
    Optional<Item> next() throws SQLException {
        Optional<RemuxRepository.TestCandidate> next = repository.nextTestCandidate();
        if (next.isEmpty()) {
            return Optional.empty();
        }
        RemuxRepository.TestCandidate t = next.get();
        List<Variant> todo = new ArrayList<>();
        for (Variant v : Variant.values()) {
            if (v.appliesTo(t.videoCodec()) && !t.variantsDone().contains(v.name())) {
                todo.add(v);
            }
        }
        return Optional.of(new Item(t.mediaFileId(), t.relativePath(), t.size(), t.videoCodec(), todo));
    }

    /** La commande exacte (sans shell) ; sortie MKV vers /dev/null : ffmpeg fabrique le fichier puis le jette. */
    List<String> command(Variant v, Path input) {
        List<String> cmd = new ArrayList<>(ProcessRunner.lowPriorityPrefix(config.lowPriority()));
        cmd.addAll(List.of(config.ffmpegPath(), "-nostdin", "-hide_banner", "-v", "warning"));
        cmd.addAll(v.input);
        cmd.addAll(List.of("-i", "file:" + input, "-map", "0", "-c", "copy"));
        cmd.addAll(v.output);
        cmd.addAll(List.of("-f", "matroska", "-y", "/dev/null"));
        return cmd;
    }

    void test(Item item) throws SQLException, InterruptedException {
        Optional<Path> file = probe.resolve(item.relativePath());
        current = item.relativePath();
        for (Variant v : item.todo()) {
            if (stopRequested) {
                return;
            }
            if (file.isEmpty()) {
                save(item, v, false, null, false, 0, "fichier introuvable");
                continue;
            }
            ProcessRunner.Result r;
            try {
                r = ProcessRunner.run(command(v, file.get()), config.remuxTestTimeout(), 4096, process);
            } catch (IOException e) {
                save(item, v, false, null, false, 0, "ffmpeg introuvable ou non exécutable");
                continue;
            }
            if (stopRequested || yielded) {
                return; // arrêté par l'admin ou cédé à un remux : pas de résultat, le fichier sera refait
            }
            bytesThisRun += item.size();
            msThisRun += r.elapsedMs();
            // Seul le code de sortie compte : un avertissement (« Headers mismatch… ») n'est pas un échec.
            String msg = probe.clean(r.stderrTail(), file.get());
            save(item, v, r.ok(), r.timedOut() ? null : r.exitCode(), r.timedOut(), r.elapsedMs(),
                    r.timedOut() ? "délai dépassé (" + config.remuxTestTimeout().toMinutes() + " min)" : msg);
        }
    }

    private void save(Item item, Variant v, boolean ok, Integer exit, boolean timedOut, long ms, String message) throws SQLException {
        repository.saveTestResult(item.mediaFileId(), v.name(), ok, exit, timedOut, ms, item.size(),
                message == null || message.isBlank() ? null : message);
    }

    public Status status() throws SQLException {
        long total;
        long bytesTotal;
        long done = 0;
        long bytesDone = 0;
        java.util.Map<String, long[]> per = new java.util.LinkedHashMap<>();
        for (Variant v : Variant.values()) {
            per.put(v.name(), new long[]{0, 0});
        }
        RemuxRepository.CountAndBytes needed = repository.remuxNeeded();
        total = needed.count();
        bytesTotal = needed.bytes();
        RemuxRepository.CountAndBytes tested = repository.tested();
        done = tested.count();
        bytesDone = tested.bytes();
        per.putAll(repository.testResultsByVariant());
        Long left = null;
        if (running() && msThisRun > 0 && bytesThisRun > 0) {
            // Débit mesuré pendant ce passage (toutes commandes confondues), appliqué à ce qui reste.
            double msPerByte = (double) msThisRun / bytesThisRun;
            long remainingBytes = Math.max(0, bytesTotal - bytesDone);
            left = (long) (remainingBytes * msPerByte * 1.6 / 1000); // ~1,6 commande par fichier (MPEG-4 : deux)
        }
        return new Status(running(), startAt, startedAt, running() ? current : null, total, done, bytesDone, bytesTotal, per,
                left, lastError);
    }
}
