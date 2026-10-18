package fr.plexwish.animeserver.live;

import fr.plexwish.animeserver.media.MediaConfig;
import fr.plexwish.animeserver.media.ProbeFacts;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Sessions de lecture à la volée (docs/LIVE-REMUX.md §3) : un ffmpeg par lecture, sortie MP4 fragmentée découpée en
 * segments du plan, horodatages corrigés, une suite de segments par piste dans un dossier temporaire. Relance sur un
 * saut, contre-pression (ffmpeg attend quand assez de segments sont prêts d'avance), fin après un délai sans requête.
 */
@ApplicationScoped
public class LiveSessionService {

    private static final Logger LOG = Logger.getLogger(LiveSessionService.class);
    /** Son copié tel quel dans le MP4 (lisible par Media3) ; les autres sont convertis en AAC. */
    static final Set<String> COPY_AUDIO = Set.of("aac", "mp3", "ac3", "eac3", "opus", "flac");
    static final int MAX_AUDIO_TRACKS = 8;
    /** Segments gardés derrière le dernier demandé (petit retour en arrière sans relancer ffmpeg). */
    static final int KEEP_BEHIND = 3;

    /** Plafond de sessions atteint. */
    public static final class BusyException extends RuntimeException {
        BusyException() {
            super(null, null, false, false);
        }
    }

    /** Lecture impossible (ffmpeg en échec, délai dépassé, ressource inconnue) ; message sans chemin. */
    public static final class LiveException extends RuntimeException {
        LiveException(String message) {
            super(message, null, false, false);
        }
    }

    /** Ce qu'il faut pour lancer ffmpeg : fichier vérifié, plan, codecs (analyse 9.1). */
    public record Source(long mediaFileId, Path file, LivePlan plan, String videoCodec, List<ProbeFacts.Audio> audio) {
    }

    record Key(long userId, long mediaFileId) {
    }

    @Inject
    MediaConfig config;

    private final Map<Key, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-reaper");
        t.setDaemon(true);
        return t;
    });
    /** Tests : plafond imposé (null = réglage). */
    private volatile Integer maxSessionsOverride;

    void maxSessionsForTests(Integer max) {
        maxSessionsOverride = max;
    }

    void onStart(@Observes StartupEvent event) {
        deleteTree(root());
        reaper.scheduleWithFixedDelay(this::closeIdle, 10, 10, TimeUnit.SECONDS);
    }

    void onStop(@Observes ShutdownEvent event) {
        reaper.shutdownNow();
        for (Key k : List.copyOf(sessions.keySet())) {
            close(k);
        }
    }

    Path root() {
        return Path.of(config.remuxCachePath()).resolve("live");
    }

    public int activeSessions() {
        return sessions.size();
    }

    // --- Demandes du lecteur --------------------------------------------------------------------------------------

    /** En-tête de la piste ({@code v}, {@code a0}…), pour {@code EXT-X-MAP}. */
    public byte[] init(long userId, Source source, String track) {
        Session s = session(userId, source);
        synchronized (s) {
            s.touch();
            long deadline = System.nanoTime() + config.liveSegmentWait().toNanos();
            while (s.header == null) {
                if (s.error != null) {
                    throw new LiveException(s.error);
                }
                if (s.process == null || s.runEnded) {
                    s.start(Math.max(0, s.lastRequested));
                }
                s.await(deadline);
            }
            Integer id = s.trackIds.get(track);
            if (id == null) {
                throw new LiveException("piste inconnue");
            }
            return s.inits.computeIfAbsent(track, t -> Fmp4.trackHeader(s.header, id));
        }
    }

    /** Segment {@code index} de la piste : prêt, ou fabriqué (attente, ou relance de ffmpeg s'il est loin). */
    public Path segment(long userId, Source source, String track, int index) {
        Session s = session(userId, source);
        if (index < 0 || index >= s.source.plan().count()) {
            throw new LiveException("segment inconnu");
        }
        synchronized (s) {
            s.touch();
            if (track.equals("v")) {
                s.lastRequested = index;
                s.notifyAll(); // contre-pression : le lecteur a avancé
                s.dropOld();
            }
            long deadline = System.nanoTime() + config.liveSegmentWait().toNanos();
            while (true) {
                Path ready = s.finalPath(track, index);
                if (Files.exists(ready)) {
                    return ready;
                }
                if (s.error != null) {
                    throw new LiveException(s.error);
                }
                if (s.header != null && !s.trackIds.containsKey(track)) {
                    throw new LiveException("piste inconnue");
                }
                boolean coming = s.process != null && !s.runEnded && index >= s.runStart
                        && index <= Math.max(s.current, s.runStart) + config.liveAhead() + 1;
                if (!coming) {
                    s.start(index);
                }
                s.await(deadline);
            }
        }
    }

    private Session session(long userId, Source source) {
        Key key = new Key(userId, source.mediaFileId());
        Session s = sessions.get(key);
        if (s != null) {
            return s;
        }
        synchronized (sessions) {
            s = sessions.get(key);
            if (s != null) {
                return s;
            }
            // Une lecture à la volée par utilisateur : changer d'épisode arrête la précédente.
            for (Key other : List.copyOf(sessions.keySet())) {
                if (other.userId() == userId) {
                    close(other);
                }
            }
            int max = maxSessionsOverride != null ? maxSessionsOverride : config.liveMaxSessions();
            if (sessions.size() >= max) {
                throw new BusyException();
            }
            Path dir = root().resolve(HexFormat.of().formatHex(token()));
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new LiveException("dossier temporaire inaccessible");
            }
            s = new Session(source, dir);
            sessions.put(key, s);
            LOG.infof("Lecture à la volée : début (fichier %d, %d segment(s), %d session(s))", source.mediaFileId(),
                    source.plan().count(), sessions.size());
            return s;
        }
    }

    private byte[] token() {
        byte[] b = new byte[12];
        random.nextBytes(b);
        return b;
    }

    void closeIdle() {
        long limit = System.nanoTime() - config.liveIdle().toNanos();
        for (Map.Entry<Key, Session> e : List.copyOf(sessions.entrySet())) {
            if (e.getValue().lastAccess < limit) {
                close(e.getKey());
            }
        }
    }

    void close(Key key) {
        Session s = sessions.remove(key);
        if (s != null) {
            s.close();
            LOG.infof("Lecture à la volée : fin (fichier %d)", key.mediaFileId());
        }
    }

    /** Tests : ferme tout. */
    void closeAll() {
        for (Key k : List.copyOf(sessions.keySet())) {
            close(k);
        }
    }

    // --- Commande ffmpeg ----------------------------------------------------------------------------------------------

    /** Arguments fixes (aucun shell) ; le chemin vient de la base, l'instant du plan. */
    List<String> command(Source source, Double seek) {
        List<String> cmd = new ArrayList<>(List.of(config.ffmpegPath(), "-nostdin", "-hide_banner", "-v", "error"));
        if (seek != null) {
            cmd.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", seek)));
        }
        cmd.addAll(List.of("-i", "file:" + source.file(), "-map_chapters", "-1", "-map_metadata", "-1", "-map", "0:v:0"));
        int audio = Math.min(MAX_AUDIO_TRACKS, source.audio().size());
        for (int i = 0; i < audio; i++) {
            cmd.addAll(List.of("-map", "0:a:" + i));
        }
        cmd.addAll(List.of("-c:v", "copy"));
        if ("hevc".equals(source.videoCodec())) {
            cmd.addAll(List.of("-tag:v", "hvc1"));
        }
        for (int i = 0; i < audio; i++) {
            String codec = source.audio().get(i).codec();
            if (codec != null && COPY_AUDIO.contains(codec)) {
                cmd.addAll(List.of("-c:a:" + i, "copy"));
            } else {
                cmd.addAll(List.of("-c:a:" + i, "aac", "-b:a:" + i, "160k", "-ac:a:" + i, "2"));
            }
        }
        cmd.addAll(List.of("-f", "mp4", "-movflags", "+frag_keyframe+empty_moov+default_base_moof+separate_moof", "pipe:1"));
        return cmd;
    }

    // --- Session ----------------------------------------------------------------------------------------------------

    final class Session {
        final Source source;
        final Path dir;
        volatile long lastAccess = System.nanoTime();
        // Champs suivants : protégés par le moniteur de la session.
        Process process;
        int runId;
        int runStart;
        int current = -1;
        boolean runEnded;
        boolean closed;
        String error;
        int lastRequested;
        byte[] header;
        Map<Integer, Long> timescales = Map.of();
        int videoTrackId = -1;
        final Map<String, Integer> trackIds = new HashMap<>();
        final Map<Integer, String> trackKeys = new HashMap<>();
        final Map<String, byte[]> inits = new HashMap<>();

        Session(Source source, Path dir) {
            this.source = source;
            this.dir = dir;
        }

        void touch() {
            lastAccess = System.nanoTime();
        }

        void await(long deadline) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new LiveException("segment pas prêt à temps");
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, Math.min(left, TimeUnit.SECONDS.toNanos(1)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LiveException("interrompu");
            }
        }

        Path finalPath(String track, int index) {
            return dir.resolve(track + "-" + index + ".m4s");
        }

        Path partPath(String track, int index) {
            return dir.resolve(track + "-" + index + ".part");
        }

        /** (Re)lance ffmpeg au segment {@code index} ; l'ancien lancement est arrêté, ses morceaux inachevés effacés. */
        void start(int index) {
            stopProcess();
            deleteParts();
            runId++;
            runStart = index;
            current = index - 1;
            runEnded = false;
            error = null;
            List<String> cmd = command(source, source.plan().seekFor(index));
            Process p;
            try {
                p = new ProcessBuilder(cmd).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null"))).start();
            } catch (IOException e) {
                error = "outil de conversion absent du serveur";
                notifyAll();
                return;
            }
            process = p;
            int run = runId;
            StringBuilder stderr = new StringBuilder();
            Thread err = new Thread(() -> drain(p.getErrorStream(), stderr), "live-ffmpeg-err");
            err.setDaemon(true);
            err.start();
            Thread reader = new Thread(() -> read(p, run, index, stderr), "live-ffmpeg");
            reader.setDaemon(true);
            reader.start();
        }

        private void read(Process p, int run, int startSegment, StringBuilder stderr) {
            LivePlan plan = source.plan();
            double shift = plan.start(startSegment) - plan.start(0);
            byte[] ftyp = null;
            byte[] moof = null;
            Map<String, OutputStream> open = new HashMap<>();
            int openSegment = -1;
            try (InputStream in = new BufferedInputStream(p.getInputStream(), 1 << 16)) {
                while (true) {
                    synchronized (this) {
                        while (run == runId && !closed && current - lastRequested >= config.liveAhead()) {
                            TimeUnit.MILLISECONDS.timedWait(this, 500);
                        }
                        if (run != runId || closed) {
                            return;
                        }
                    }
                    Fmp4.Box box = Fmp4.next(in);
                    if (box == null) {
                        break;
                    }
                    switch (box.type()) {
                        case "ftyp" -> ftyp = box.bytes();
                        case "moov" -> onHeader(run, ftyp, box.bytes());
                        case "moof" -> moof = box.bytes();
                        case "mdat" -> {
                            if (moof == null) {
                                continue;
                            }
                            int track = Fmp4.trackId(moof);
                            Long scale = timescales.get(track);
                            String key = trackKeys.get(track);
                            if (scale == null || key == null) {
                                moof = null;
                                continue;
                            }
                            Fmp4.shiftDecodeTime(moof, Math.round(shift * scale));
                            if (track == videoTrackId) {
                                double t = (double) Fmp4.decodeTime(moof) / scale + plan.start(0);
                                int segment = Math.max(startSegment, plan.segmentAt(t));
                                if (segment != openSegment) {
                                    closeAll(open);
                                    if (openSegment >= 0) {
                                        publish(run, openSegment);
                                    }
                                    openSegment = segment;
                                    synchronized (this) {
                                        if (run != runId) {
                                            return;
                                        }
                                        current = segment;
                                        notifyAll();
                                    }
                                }
                            }
                            if (openSegment < 0) {
                                openSegment = startSegment; // son avant la première image : rangé dans le premier segment
                            }
                            OutputStream out = open.get(key);
                            if (out == null) {
                                out = Files.newOutputStream(partPath(key, openSegment));
                                open.put(key, out);
                            }
                            out.write(moof);
                            out.write(box.bytes());
                            moof = null;
                        }
                        default -> {
                        }
                    }
                }
                closeAll(open);
                if (openSegment >= 0) {
                    publish(run, openSegment);
                }
                int code = p.waitFor();
                synchronized (this) {
                    if (run == runId) {
                        runEnded = true;
                        if (code != 0 && !closed) {
                            error = "conversion interrompue (code " + code + ")";
                            LOG.warnf("Lecture à la volée : ffmpeg en échec (fichier %d, code %d) : %s", source.mediaFileId(), code,
                                    tail(stderr));
                        }
                        notifyAll();
                    }
                }
            } catch (IOException | RuntimeException e) {
                closeAll(open);
                synchronized (this) {
                    if (run == runId && !closed) {
                        runEnded = true;
                        error = "flux de conversion illisible";
                        LOG.warnf("Lecture à la volée : sortie de ffmpeg illisible (fichier %d) : %s ; %s", source.mediaFileId(),
                                e.getMessage(), tail(stderr));
                        notifyAll();
                    }
                }
            } catch (InterruptedException e) {
                closeAll(open);
                Thread.currentThread().interrupt();
            }
        }

        private void onHeader(int run, byte[] ftyp, byte[] moov) {
            byte[] h = ftyp == null ? moov : concat(ftyp, moov);
            synchronized (this) {
                if (run != runId) {
                    return;
                }
                if (header == null) {
                    header = h;
                    timescales = Fmp4.timescales(h);
                    int audio = 0;
                    for (Fmp4.Track t : Fmp4.tracks(h)) {
                        String key = null;
                        if (t.handler().equals("vide") && videoTrackId < 0) {
                            videoTrackId = t.id();
                            key = "v";
                        } else if (t.handler().equals("soun")) {
                            key = "a" + audio++;
                        }
                        if (key != null) {
                            trackIds.put(key, t.id());
                            trackKeys.put(t.id(), key);
                        }
                    }
                    if (videoTrackId < 0) {
                        error = "pas de piste vidéo";
                    }
                } else if (!Arrays.equals(header, h)) {
                    // Jamais vu à l'essai (en-tête identique d'un lancement à l'autre) : on ne mélange pas deux en-têtes.
                    error = "en-tête différent après une relance";
                    LOG.warnf("Lecture à la volée : en-tête différent après une relance (fichier %d)", source.mediaFileId());
                }
                notifyAll();
            }
        }

        /** Segment terminé pour toutes les pistes : les morceaux deviennent des segments servis. */
        private void publish(int run, int segment) throws IOException {
            synchronized (this) {
                if (run != runId) {
                    return;
                }
                for (String key : trackIds.keySet()) {
                    Path part = partPath(key, segment);
                    if (Files.exists(part)) {
                        Files.move(part, finalPath(key, segment), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    }
                }
                notifyAll();
            }
        }

        void dropOld() {
            int keepFrom = lastRequested - KEEP_BEHIND;
            if (keepFrom <= 0) {
                return;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.forEach(f -> {
                    String n = f.getFileName().toString();
                    int dash = n.lastIndexOf('-');
                    int dot = n.lastIndexOf('.');
                    if (dash > 0 && dot > dash && n.endsWith(".m4s")) {
                        try {
                            if (Integer.parseInt(n.substring(dash + 1, dot)) < keepFrom) {
                                Files.deleteIfExists(f);
                            }
                        } catch (NumberFormatException | IOException ignored) {
                            // nom inattendu ou fichier déjà parti
                        }
                    }
                });
            } catch (IOException ignored) {
                // dossier supprimé entre-temps
            }
        }

        private void deleteParts() {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.getFileName().toString().endsWith(".part")).forEach(f -> {
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException ignored) {
                        // tant pis : écrasé au prochain passage
                    }
                });
            } catch (IOException ignored) {
                // dossier absent
            }
        }

        private void stopProcess() {
            if (process != null) {
                process.destroyForcibly();
                process = null;
            }
        }

        synchronized void close() {
            closed = true;
            runId++;
            stopProcess();
            notifyAll();
            deleteTree(dir);
        }
    }

    // --- Utilitaires --------------------------------------------------------------------------------------------------

    private static void closeAll(Map<String, OutputStream> open) {
        for (OutputStream o : open.values()) {
            try {
                o.close();
            } catch (IOException ignored) {
                // fichier temporaire : effacé avec la session
            }
        }
        open.clear();
    }

    private static void drain(InputStream in, StringBuilder tail) {
        byte[] buf = new byte[4096];
        try (in) {
            int n;
            while ((n = in.read(buf)) > 0) {
                synchronized (tail) {
                    tail.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                    if (tail.length() > 4000) {
                        tail.delete(0, tail.length() - 2000);
                    }
                }
            }
        } catch (IOException ignored) {
            // processus arrêté
        }
    }

    private static String tail(StringBuilder sb) {
        synchronized (sb) {
            String s = sb.toString().strip();
            return s.length() > 500 ? "…" + s.substring(s.length() - 500) : s;
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // réessayé au prochain démarrage
                }
            });
        } catch (IOException ignored) {
            // idem
        }
    }
}
