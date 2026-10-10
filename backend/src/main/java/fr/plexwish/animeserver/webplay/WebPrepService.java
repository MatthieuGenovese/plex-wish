package fr.plexwish.animeserver.webplay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.media.MediaConfig;
import fr.plexwish.animeserver.media.MediaProbeService;
import fr.plexwish.animeserver.media.ProcessRunner;
import fr.plexwish.animeserver.media.RemuxTestService;
import fr.plexwish.animeserver.setup.AppSettings;
import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Préparation des épisodes pour le navigateur (docs/WEB-PLAYER.md §4) : file d'attente en base ({@code web_job}),
 * **une préparation à la fois**, priorité basse ({@code nice} / {@code ionice}), délai maximal, reprise au démarrage.
 * Étapes : analyse des pistes ({@code PROBE}), puis {@link WebPrepSteps} (sous-titres, polices, copie HLS sans
 * ré-encodage), vérification, publication atomique dans le cache web. Cache borné : les préparations les moins
 * récemment lues sont effacées pour faire de la place (jamais une préparation lue récemment).
 */
@ApplicationScoped
public class WebPrepService {

    private static final Logger LOG = Logger.getLogger(WebPrepService.class);
    public static final String BASE = "BASE";
    private static final long MB = 1_000_000L;
    /** Plafond par défaut du cache web : 15 % du volume, 200 Go au plus (décision D7). */
    static final long DEFAULT_CAP_MAX = 200_000_000_000L;

    // --- Réponses à « je veux lire ce fichier dans un navigateur » -----------------------------------------------------

    public sealed interface Decision permits Ready, Preparing, Failed, CacheFull, Unavailable {
    }

    /** Préparation terminée. */
    public record Ready(String key, WebManifest manifest) implements Decision {
    }

    /**
     * En cours ou en file. {@code manifest} : connu dès la fin de l'analyse (null avant) ; {@code playableEarly} : la
     * copie HLS en cours d'écriture est déjà lisible (premiers segments écrits).
     */
    public record Preparing(String phase, int position, Double progress, long estimatedSeconds, int retryAfterSeconds,
                            String key, WebManifest manifest, boolean playableEarly) implements Decision {
    }

    public record Failed(String reason, Instant retryAt) implements Decision {
    }

    public record CacheFull(int retryAfterSeconds) implements Decision {
    }

    public record Unavailable(String reason) implements Decision {
    }

    record Job(long mediaFileId, String status, String phase, int priority, String key, long sourceSize,
               OffsetDateTime sourceModified, WebManifest manifest, Instant nextAttempt, String blocked, String error, int attempts) {
    }

    /** Échec d'une étape, avec une raison lisible (sans chemin). */
    public static final class StepFailure extends Exception {
        public StepFailure(String reason) {
            super(reason);
        }
    }

    @Inject
    AgroalDataSource dataSource;
    @Inject
    MediaConfig config;
    @Inject
    WebCache cache;
    @Inject
    MediaProbeService probe;
    @Inject
    RemuxTestService remuxTest;
    @Inject
    WebPrepSteps steps;
    @Inject
    AppSettings settings;
    @Inject
    ObjectMapper json;

    private final Object signal = new Object();
    private final Map<Long, Double> progress = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastReadWrite = new ConcurrentHashMap<>();
    private volatile Thread thread;
    private volatile boolean running;
    private volatile Long current;
    /** Débit observé (octets/ms) d'une préparation, pour estimer l'attente ; 40 Mo/s par défaut (disques du NAS). */
    private volatile double bytesPerMs = 40_000;
    private volatile String ffmpegVersion;
    private volatile boolean usable;
    private volatile Instant lastToolCheck = Instant.EPOCH;
    private volatile Long maxBytesOverride;

    void onStart(@Observes StartupEvent e) throws SQLException {
        checkTools();
        if (!usable) {
            LOG.warnf("Préparation web : dossier %s non accessible en écriture : lecteur web limité aux fichiers lisibles tels quels",
                    cache.root());
        }
        recover();
        if (config.webWorkerEnabled()) {
            running = true;
            thread = Thread.ofPlatform().daemon().name("web-prep").start(this::loop);
        }
    }

    void onStop(@Observes ShutdownEvent e) {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /** Cache utilisable, ffmpeg et ffprobe présents (au démarrage ; aussi dans les tests). */
    public void checkTools() {
        usable = cache.usable();
        ffmpegVersion = probe.version(config.ffmpegPath());
        ffprobeOk = probe.version(config.ffprobePath()) != null;
    }

    private volatile boolean ffprobeOk;

    public boolean usable() {
        return usable && ffmpegVersion != null && ffprobeOk;
    }

    /**
     * Démarrage : une préparation interrompue repart en file (ffmpeg ne reprend pas un fichier entamé : son dossier
     * temporaire est effacé) ; préparations prêtes dont le dossier a disparu oubliées ; restes effacés.
     */
    void recover() throws SQLException {
        Set<String> keep = new HashSet<>();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement st = c.prepareStatement("""
                    UPDATE web_job SET status = 'QUEUED', phase = NULL, started_at = NULL, manifest = NULL
                    WHERE status = 'RUNNING'""")) {
                st.executeUpdate();
            }
            List<Long> lost = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement("SELECT media_file_id, cache_key FROM web_job WHERE status = 'READY'");
                 ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    if (cache.exists(rs.getString(2))) {
                        keep.add(rs.getString(2));
                    } else {
                        lost.add(rs.getLong(1));
                    }
                }
            }
            for (long id : lost) {
                delete(c, id);
            }
        }
        int removed = cache.sweep(keep);
        if (removed > 0) {
            LOG.infof("Préparation web : %d dossier(s) temporaire(s) ou orphelin(s) effacé(s) du cache", removed);
        }
    }

    /** Une préparation est en cours ou attend : l'analyse de fond se met en pause (disque). */
    public boolean busy() {
        if (current != null) {
            return true;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT 1 FROM web_job WHERE status = 'QUEUED' AND blocked IS NULL"
                     + " AND (next_attempt_at IS NULL OR next_attempt_at <= now()) LIMIT 1");
             ResultSet rs = st.executeQuery()) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    /** Tests seulement : plafond du cache en octets. */
    void maxBytesForTests(Long bytes) {
        maxBytesOverride = bytes;
    }

    /** Réglage de l'installation (Administration > Réglages), sinon WEB_CACHE_MAX_GB, sinon 15 % du volume (200 Go au plus). */
    public long maxBytes() {
        Long o = maxBytesOverride;
        if (o != null) {
            return o;
        }
        Optional<Double> set = settings.getDouble(fr.plexwish.animeserver.setup.InstallationSettings.WEB_CAP_GB);
        if (set.isPresent()) {
            return (long) (set.get() * 1e9);
        }
        if (config.webCacheMaxGb().isPresent()) {
            return (long) (config.webCacheMaxGb().get() * 1e9);
        }
        return defaultMaxBytes(cache.totalSpace());
    }

    public static long defaultMaxBytes(long totalBytes) {
        if (totalBytes <= 0) {
            return 50_000_000_000L;
        }
        return Math.min(DEFAULT_CAP_MAX, (long) (totalBytes * 0.15));
    }

    // --- Demande d'un utilisateur -------------------------------------------------------------------------------------

    /** Lecture demandée : préparation prête, en cours (mise en file si besoin), en échec, cache plein ou indisponible. */
    public Decision request(long mediaFileId, long sourceSize, OffsetDateTime sourceModified) throws SQLException {
        if (!usable() && lastToolCheck.isBefore(Instant.now().minusSeconds(60))) {
            lastToolCheck = Instant.now();
            checkTools();
        }
        if (!usable()) {
            return new Unavailable(ffmpegVersion == null ? "outil vidéo absent du serveur" : "dossier du cache web inaccessible");
        }
        String key = WebCache.key(BASE, mediaFileId, sourceSize, sourceModified);
        Optional<Job> job = job(mediaFileId);
        if (job.isPresent() && !job.get().key().equals(key)) {
            // Source modifiée : l'ancienne préparation ne vaut plus rien.
            cache.delete(job.get().key());
            delete(mediaFileId);
            job = Optional.empty();
        }
        if (job.isPresent()) {
            Job j = job.get();
            switch (j.status()) {
                case "READY" -> {
                    if (cache.exists(key) && j.manifest() != null) {
                        return new Ready(key, j.manifest());
                    }
                    delete(mediaFileId); // dossier disparu du cache : on refait
                }
                case "FAILED" -> {
                    if (j.nextAttempt() != null && j.nextAttempt().isAfter(Instant.now())) {
                        return new Failed(j.error(), j.nextAttempt());
                    }
                    requeue(mediaFileId, 0, false);
                }
                default -> {
                    if (j.priority() > 0) {
                        requeue(mediaFileId, 0, false);
                    }
                }
            }
        }
        if (job(mediaFileId).isEmpty()) {
            insert(mediaFileId, key, sourceSize, sourceModified, 0);
        }
        remuxTest.yieldTo();
        synchronized (signal) {
            signal.notifyAll();
        }
        Job j = job(mediaFileId).orElseThrow();
        if ("READY".equals(j.status()) && j.manifest() != null) {
            return new Ready(key, j.manifest()); // terminé entre-temps (fichier sans rien à extraire)
        }
        if ("CACHE_FULL".equals(j.blocked())) {
            return new CacheFull(60);
        }
        return preparing(j);
    }

    /** Position, progression et attente estimée (préparations avant elle + le reste de la sienne). */
    Preparing preparing(Job self) throws SQLException {
        long aheadBytes = 0;
        int position = 0;
        Double p = progress.get(self.mediaFileId());
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT j.media_file_id, j.status, j.source_size FROM web_job j, web_job me
                     WHERE me.media_file_id = ? AND me.kind = 'BASE' AND j.kind = 'BASE' AND j.media_file_id <> me.media_file_id
                       AND j.status IN ('QUEUED', 'RUNNING') AND j.blocked IS NULL
                       AND (j.status = 'RUNNING' OR (j.next_attempt_at IS NULL OR j.next_attempt_at <= now()))
                       AND (j.status = 'RUNNING' OR j.priority < me.priority OR (j.priority = me.priority AND j.requested_at < me.requested_at)
                            OR (j.priority = me.priority AND j.requested_at = me.requested_at AND j.media_file_id < me.media_file_id))""")) {
            st.setLong(1, self.mediaFileId());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    position++;
                    double done = "RUNNING".equals(rs.getString(2)) ? progress.getOrDefault(rs.getLong(1), 0.0) : 0;
                    aheadBytes += (long) (rs.getLong(3) * (1 - done));
                }
            }
        }
        boolean run = "RUNNING".equals(self.status());
        if (run) {
            position = 0;
            aheadBytes = 0;
        }
        long own = (long) (self.sourceSize() * (1 - (p == null ? 0 : p)));
        long seconds = Math.round((aheadBytes + own) / bytesPerMs / 1000) + 2L * (position + 1);
        int retry = (int) Math.max(1, Math.min(10, seconds / 5));
        boolean early = run && "HLS".equals(self.phase()) && self.manifest() != null
                && steps.playableEarly(cache.partDir(self.key()), self.manifest());
        return new Preparing(run ? self.phase() : "QUEUED", position, run ? (p == null ? 0.0 : p) : null, seconds, retry,
                self.key(), self.manifest(), early);
    }

    /**
     * Préparation servie pour {@code /api/stream/{id}/web/{clé}/…} : prête, ou en cours avec sa copie HLS déjà lisible.
     * Marque la lecture (au plus une écriture par minute) : protège la préparation de la purge.
     */
    public Optional<Job> served(long mediaFileId, String key) throws SQLException {
        Optional<Job> j = job(mediaFileId);
        if (j.isEmpty() || !j.get().key().equals(key) || !("READY".equals(j.get().status()) || "RUNNING".equals(j.get().status()))) {
            return Optional.empty();
        }
        touch(mediaFileId, key);
        return j;
    }

    private void touch(long mediaFileId, String key) throws SQLException {
        Instant last = lastReadWrite.get(key);
        if (last != null && last.isAfter(Instant.now().minusSeconds(60))) {
            return;
        }
        lastReadWrite.put(key, Instant.now());
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("UPDATE web_job SET last_read_at = now() WHERE media_file_id = ? AND kind = 'BASE'")) {
            st.setLong(1, mediaFileId);
            st.executeUpdate();
        }
    }

    // --- Base -----------------------------------------------------------------------------------------------------------

    Optional<Job> job(long mediaFileId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT media_file_id, status, phase, priority, cache_key, source_size, source_modified, manifest,
                            next_attempt_at, blocked, error, attempts FROM web_job WHERE media_file_id = ? AND kind = 'BASE'""")) {
            st.setLong(1, mediaFileId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                OffsetDateTime na = rs.getObject(9, OffsetDateTime.class);
                return Optional.of(new Job(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
                        rs.getLong(6), rs.getObject(7, OffsetDateTime.class), manifest(rs.getString(8)),
                        na == null ? null : na.toInstant(), rs.getString(10), rs.getString(11), rs.getInt(12)));
            }
        }
    }

    private WebManifest manifest(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return json.readValue(raw, WebManifest.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String manifestJson(WebManifest m) {
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void insert(long id, String key, long size, OffsetDateTime modified, int priority) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     INSERT INTO web_job (media_file_id, kind, status, priority, cache_key, source_size, source_modified)
                     VALUES (?, 'BASE', 'QUEUED', ?, ?, ?, ?) ON CONFLICT DO NOTHING""")) {
            st.setLong(1, id);
            st.setInt(2, priority);
            st.setString(3, key);
            st.setLong(4, size);
            st.setObject(5, modified);
            st.executeUpdate();
        }
    }

    void requeue(long id, int priority, boolean resetAttempts) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("UPDATE web_job SET status = 'QUEUED', priority = LEAST(priority, ?),"
                     + " next_attempt_at = NULL, blocked = NULL" + (resetAttempts ? ", attempts = 0, error = NULL" : "")
                     + " WHERE media_file_id = ? AND kind = 'BASE' AND status IN ('QUEUED', 'FAILED')")) {
            st.setInt(1, priority);
            st.setLong(2, id);
            st.executeUpdate();
        }
    }

    private void delete(long id) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            delete(c, id);
        }
    }

    private static void delete(Connection c, long id) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("DELETE FROM web_job WHERE media_file_id = ? AND kind = 'BASE'")) {
            st.setLong(1, id);
            st.executeUpdate();
        }
    }

    private void phase(long id, String phase, WebManifest m) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("UPDATE web_job SET phase = ?, manifest = coalesce(?::jsonb, manifest)"
                     + " WHERE media_file_id = ? AND kind = 'BASE'")) {
            st.setString(1, phase);
            st.setString(2, m == null ? null : manifestJson(m));
            st.setLong(3, id);
            st.executeUpdate();
        }
    }

    // --- Exécution ------------------------------------------------------------------------------------------------------

    private void loop() {
        while (running) {
            try {
                Optional<Job> next = usable() ? claim() : Optional.empty();
                if (next.isPresent()) {
                    process(next.get());
                } else {
                    synchronized (signal) {
                        signal.wait(30_000);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOG.warnf(e, "Préparation web : erreur inattendue");
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Prochaine préparation : demandes des utilisateurs d'abord, dans l'ordre d'arrivée. */
    Optional<Job> claim() throws SQLException {
        Long id = null;
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE web_job SET status = 'RUNNING', phase = 'PROBE', started_at = now(), blocked = NULL
                     WHERE (media_file_id, kind) = (SELECT media_file_id, kind FROM web_job WHERE status = 'QUEUED'
                         AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                         ORDER BY priority, requested_at, media_file_id LIMIT 1 FOR UPDATE SKIP LOCKED)
                     RETURNING media_file_id""");
             ResultSet rs = st.executeQuery()) {
            if (rs.next()) {
                id = rs.getLong(1);
            }
        }
        return id == null ? Optional.empty() : job(id);
    }

    /** Traite une préparation tout de suite (tests ; en temps normal, le fil « web-prep »). */
    public boolean processNext() throws SQLException, InterruptedException {
        Optional<Job> j = claim();
        if (j.isEmpty()) {
            return false;
        }
        process(j.get());
        return true;
    }

    void process(Job job) throws SQLException, InterruptedException {
        current = job.mediaFileId();
        progress.put(job.mediaFileId(), 0.0);
        long id = job.mediaFileId();
        try {
            Optional<String[]> src = source(id);
            Optional<Path> file = src.flatMap(s -> probe.resolve(s[0]));
            if (file.isEmpty()) {
                fail(job, "fichier source introuvable", true);
                return;
            }
            WebManifest m;
            try {
                m = analyse(file.get(), src.get()[1]);
            } catch (StepFailure e) {
                fail(job, e.getMessage(), false);
                return;
            }
            phase(id, "PROBE", m);
            long needed = m.wantsHls() ? job.sourceSize() + job.sourceSize() / 20 + 16 * MB : 64 * MB;
            switch (makeRoom(needed, job.key())) {
                case TOO_BIG -> {
                    fail(job, "fichier trop gros pour le cache web (" + job.sourceSize() / MB + " Mo, cache de " + maxBytes() / MB + " Mo)", true);
                    return;
                }
                case FULL -> {
                    block(job);
                    return;
                }
                case OK -> {
                }
            }
            Path part;
            try {
                part = cache.prepare(job.key());
            } catch (IOException e) {
                fail(job, "écriture impossible dans le cache web", false);
                return;
            }
            long start = System.currentTimeMillis();
            WebManifest done;
            try {
                done = steps.run(file.get(), part, m, (phase, value) -> {
                    progress.put(id, value);
                    if (phase != null) {
                        try {
                            phase(id, phase, null);
                        } catch (SQLException e) {
                            LOG.debugf("Préparation web : étape non enregistrée (%s)", e.getMessage());
                        }
                    }
                });
            } catch (StepFailure e) {
                cache.delete(job.key());
                fail(job, e.getMessage(), false);
                return;
            }
            long bytes;
            try {
                bytes = WebCache.treeSize(part);
                cache.publish(job.key());
            } catch (IOException e) {
                cache.delete(job.key());
                fail(job, "publication impossible dans le cache web", false);
                return;
            }
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > 1000 && done.hls()) {
                bytesPerMs = 0.7 * bytesPerMs + 0.3 * ((double) job.sourceSize() / elapsed);
            }
            try (Connection c = dataSource.getConnection();
                 PreparedStatement st = c.prepareStatement("""
                         UPDATE web_job SET status = 'READY', phase = NULL, manifest = ?::jsonb, bytes = ?, finished_at = now(),
                             error = NULL, blocked = NULL, next_attempt_at = NULL WHERE media_file_id = ? AND kind = 'BASE'""")) {
                st.setString(1, manifestJson(done));
                st.setLong(2, bytes);
                st.setLong(3, id);
                st.executeUpdate();
            }
            LOG.infof("Préparation web : fichier %d prêt (%s%d Mo, %d ms)", id, done.hls() ? "copie HLS, " : "", bytes / MB, elapsed);
        } finally {
            progress.remove(id);
            current = null;
        }
    }

    /** Analyse des pistes (en-têtes seulement : une fraction de seconde), plan de préparation. */
    WebManifest analyse(Path file, String extension) throws StepFailure, InterruptedException {
        List<String> cmd = List.of(config.ffprobePath(), "-v", "error", "-hide_banner", "-show_format", "-show_streams",
                "-of", "json", "-i", "file:" + file);
        try {
            ProcessRunner.Result r = ProcessRunner.run(cmd, config.probeTimeout(), 4 * 1024 * 1024, null);
            if (!r.ok()) {
                throw new StepFailure(r.timedOut() ? "analyse trop longue" : "fichier illisible par ffprobe");
            }
            return WebManifest.plan(r.stdout(), extension);
        } catch (IOException e) {
            throw new StepFailure("ffprobe introuvable ou non exécutable");
        } catch (IllegalArgumentException e) {
            throw new StepFailure("fichier illisible par ffprobe");
        }
    }

    enum Room { OK, FULL, TOO_BIG }

    /** Fait de la place : préparations prêtes les moins récemment lues d'abord, jamais une lue récemment ni celle-ci. */
    synchronized Room makeRoom(long needed, String exceptKey) throws SQLException {
        long max = maxBytes();
        long reserve = (long) (config.webCacheReserveGb() * 1e9);
        if (needed > max) {
            return Room.TOO_BIG;
        }
        List<Object[]> candidates = new ArrayList<>();
        long used;
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement st = c.prepareStatement("SELECT coalesce(sum(bytes), 0) FROM web_job WHERE status = 'READY'");
                 ResultSet rs = st.executeQuery()) {
                rs.next();
                used = rs.getLong(1);
            }
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT media_file_id, cache_key, coalesce(bytes, 0) FROM web_job
                    WHERE status = 'READY' AND cache_key <> ?
                      AND (last_read_at IS NULL OR last_read_at < now() - make_interval(secs => ?))
                    ORDER BY coalesce(last_read_at, finished_at) NULLS FIRST, media_file_id""")) {
                st.setString(1, exceptKey);
                st.setLong(2, config.webInUse().toSeconds());
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        candidates.add(new Object[]{rs.getLong(1), rs.getString(2), rs.getLong(3)});
                    }
                }
            }
        }
        long free = cache.usableSpace();
        int i = 0;
        while ((used + needed > max || (free >= 0 && free - reserve < needed)) && i < candidates.size()) {
            Object[] victim = candidates.get(i++);
            cache.delete((String) victim[1]);
            delete((Long) victim[0]);
            used -= (Long) victim[2];
            free = cache.usableSpace();
            LOG.infof("Préparation web : fichier %d effacé du cache (le moins récemment lu)", (Long) victim[0]);
        }
        return used + needed <= max && (free < 0 || free - reserve >= needed) ? Room.OK : Room.FULL;
    }

    /** {chemin relatif, extension} du fichier source, s'il est disponible. */
    private Optional<String[]> source(long id) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT relative_path, container FROM media_file WHERE id = ? AND available")) {
            st.setLong(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(new String[]{rs.getString(1), rs.getString(2)}) : Optional.empty();
            }
        }
    }

    /** Échec : nouvel essai automatique espacé (10 min, 20, 40… 24 h au plus), jamais en boucle. */
    private void fail(Job job, String reason, boolean permanent) throws SQLException {
        LOG.warnf("Préparation web impossible pour le fichier %d : %s", job.mediaFileId(), reason);
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE web_job SET status = 'FAILED', phase = NULL, attempts = attempts + 1, finished_at = now(), error = ?,
                         blocked = NULL, next_attempt_at = CASE WHEN ? THEN now() + interval '1 day'
                             ELSE now() + make_interval(mins => LEAST(1440, 10 * power(2, attempts)::int)) END
                     WHERE media_file_id = ? AND kind = 'BASE'""")) {
            st.setString(1, reason == null ? "échec" : reason.length() > 600 ? reason.substring(0, 600) : reason);
            st.setBoolean(2, permanent);
            st.setLong(3, job.mediaFileId());
            st.executeUpdate();
        }
    }

    private void block(Job job) throws SQLException {
        LOG.infof("Préparation web : cache plein (préparations en cours de lecture), fichier %d en attente", job.mediaFileId());
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE web_job SET status = 'QUEUED', phase = NULL, blocked = 'CACHE_FULL', started_at = NULL,
                         next_attempt_at = now() + interval '1 minute' WHERE media_file_id = ? AND kind = 'BASE'""")) {
            st.setLong(1, job.mediaFileId());
            st.executeUpdate();
        }
    }

    // --- Administration (10.3 complétera) -----------------------------------------------------------------------------

    public String ffmpegVersion() {
        return ffmpegVersion;
    }

    /** Taille des préparations prêtes (octets). */
    public long usedBytes() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT coalesce(sum(bytes), 0) FROM web_job WHERE status = 'READY'");
             ResultSet rs = st.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Pour les tests : vrai si le fichier temporaire d'une préparation existe encore. */
    boolean partExists(String key) {
        return Files.isDirectory(cache.partDir(key));
    }
}
