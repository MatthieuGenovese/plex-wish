package fr.plexwish.animeserver.media;

import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lance ffprobe / ffmpeg **sans shell** : une liste d'arguments fixe passée telle quelle à {@link ProcessBuilder}
 * (aucune interprétation, aucun caractère spécial possible). Délai maximal (puis arrêt forcé), sorties lues en
 * continu et plafonnées, priorité basse si {@code nice} / {@code ionice} existent. Le chemin du fichier vient de la base.
 */
public final class ProcessRunner {

    private static final Logger LOG = Logger.getLogger(ProcessRunner.class);
    private static final Path NICE = Path.of("/usr/bin/nice");
    private static final Path IONICE = Path.of("/usr/bin/ionice");

    /** {@code stdout} plafonné à {@code maxStdout} octets ; {@code stderrTail} : la fin de la sortie d'erreur. */
    public record Result(int exitCode, boolean timedOut, String stdout, String stderrTail, long elapsedMs) {
        /** Succès = code de sortie 0 (les avertissements écrits sur la sortie d'erreur ne comptent pas). */
        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    private ProcessRunner() {
    }

    /** Préfixe de priorité basse (vide si les outils manquent : la commande reste identique). */
    public static List<String> lowPriorityPrefix(boolean enabled) {
        List<String> p = new ArrayList<>();
        if (enabled && Files.isExecutable(NICE)) {
            p.addAll(List.of(NICE.toString(), "-n", "19"));
        }
        if (enabled && Files.isExecutable(IONICE)) {
            p.addAll(List.of(IONICE.toString(), "-c", "3"));
        }
        return p;
    }

    /**
     * Exécute la commande. {@code current} (facultatif) reçoit le processus, pour pouvoir l'arrêter depuis un autre
     * fil (bouton « Arrêter ») ; il est remis à null à la fin.
     */
    public static Result run(List<String> command, Duration timeout, int maxStdout, AtomicReference<Process> current)
            throws IOException, InterruptedException {
        return run(command, timeout, maxStdout, current, null);
    }

    /** {@code stdoutLines} (facultatif) reçoit chaque ligne de la sortie standard au fil de l'eau (ex. ffmpeg -progress). */
    public static Result run(List<String> command, Duration timeout, int maxStdout, AtomicReference<Process> current,
                             java.util.function.Consumer<String> stdoutLines) throws IOException, InterruptedException {
        long start = System.nanoTime();
        Process p = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null"))).start();
        if (current != null) {
            current.set(p);
        }
        Capture out = new Capture(p.getInputStream(), maxStdout, false, stdoutLines);
        Capture err = new Capture(p.getErrorStream(), 4096, true, null);
        Thread to = Thread.ofVirtual().start(out);
        Thread te = Thread.ofVirtual().start(err);
        boolean finished;
        try {
            finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            throw e;
        } finally {
            if (current != null) {
                current.compareAndSet(p, null);
            }
        }
        if (!finished) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        }
        to.join(2_000);
        te.join(2_000);
        int code = finished ? p.exitValue() : -1;
        return new Result(code, !finished, out.text(), err.text(), (System.nanoTime() - start) / 1_000_000);
    }

    /** Lecture continue d'un flux (évite le blocage du processus), plafonnée ; {@code tail} garde la fin. */
    private static final class Capture implements Runnable {
        private final InputStream in;
        private final int max;
        private final boolean tail;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private volatile boolean truncated;
        private final java.util.function.Consumer<String> lines;
        private final StringBuilder line = new StringBuilder();

        Capture(InputStream in, int max, boolean tail, java.util.function.Consumer<String> lines) {
            this.in = in;
            this.max = max;
            this.tail = tail;
            this.lines = lines;
        }

        private void feed(byte[] b, int n) {
            for (int i = 0; i < n; i++) {
                char c = (char) (b[i] & 0xff);
                if (c == '\n') {
                    try {
                        lines.accept(line.toString().trim());
                    } catch (RuntimeException e) {
                        LOG.debugf("ligne ignorée (%s)", e.getClass().getSimpleName());
                    }
                    line.setLength(0);
                } else if (line.length() < 1000) {
                    line.append(c);
                }
            }
        }

        @Override
        public void run() {
            byte[] b = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(b)) > 0) {
                    if (lines != null) {
                        feed(b, n);
                    }
                    synchronized (buf) {
                        buf.write(b, 0, n);
                        if (buf.size() > max * 2) {
                            byte[] all = buf.toByteArray();
                            buf.reset();
                            truncated = true;
                            buf.write(all, tail ? all.length - max : 0, max);
                        }
                    }
                }
            } catch (IOException e) {
                LOG.debugf("lecture de la sortie interrompue (%s)", e.getClass().getSimpleName());
            }
        }

        String text() {
            synchronized (buf) {
                byte[] all = buf.toByteArray();
                int len = Math.min(all.length, max);
                String s = new String(all, tail ? all.length - len : 0, len, StandardCharsets.UTF_8);
                return truncated && tail ? "…" + s : s;
            }
        }
    }
}
