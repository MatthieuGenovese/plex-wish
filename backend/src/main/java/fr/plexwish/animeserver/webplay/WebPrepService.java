package fr.plexwish.animeserver.webplay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.media.MediaConfig;
import fr.plexwish.animeserver.media.MediaProbeService;
import fr.plexwish.animeserver.media.ProcessRunner;
import fr.plexwish.animeserver.media.RemuxTestService;
import fr.plexwish.animeserver.setup.AppSettings;
import fr.plexwish.animeserver.stream.PlaybackActivity;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Préparation des épisodes pour le navigateur (docs/WEB-PLAYER.md §4) : file d'attente en base ({@code web_job}),
 * reprise au démarrage, priorité basse ({@code nice} / {@code ionice}), délai maximal. Deux sortes, deux files :
 * <ul>
 *     <li>{@code BASE} (fil « web-prep ») : analyse des pistes, sous-titres et polices, copie HLS sans ré-encodage si
 *     un navigateur peut décoder la vidéo. Rapide (secondes à une minute).</li>
 *     <li>{@code CONV} (fil « web-convert », 10.3) : copie lisible par tous les navigateurs, vidéo H.264 8 bits
 *     (copiée ou convertie par x264) et son AAC. Une seule à la fois ; celles qui ne sont pas attendues devant un
 *     écran (admin, préventif) sont <b>suspendues</b> pendant les lectures et cèdent la place à une demande urgente.</li>
 * </ul>
 * Priorités : 0 = quelqu'un attend, 1 = demandé par l'admin, 2 = préventif (seulement dans la fenêtre de nuit).
 * Échecs : 3 essais espacés au plus, ensuite l'admin relance. Cache borné : les préparations les moins récemment lues
 * sont effacées pour faire de la place (jamais une préparation lue récemment).
 */
@ApplicationScoped
public class WebPrepService {

    private static final Logger LOG = Logger.getLogger(WebPrepService.class);
    public static final String BASE = "BASE";
    public static final String CONV = "CONV";
    private static final long MB = 1_000_000L;
    /** Plafond par défaut du cache web : 15 % du volume, 200 Go au plus (décision D7). */
    static final long DEFAULT_CAP_MAX = 200_000_000_000L;
    /** Essais automatiques au plus avant que l'admin doive relancer. */
    static final int MAX_ATTEMPTS = 3;
    public static final int PRIORITY_USER = 0;
    public static final int PRIORITY_ADMIN = 1;
    public static final int PRIORITY_NIGHT = 2;

    // --- Réponses à « je veux lire ce fichier dans un navigateur » -----------------------------------------------------

    public sealed interface Decision permits Ready, Preparing, Failed, CacheFull, Unavailable {
    }

    /** Préparation terminée. */
    public record Ready(String key, WebManifest manifest) implements Decision {
    }

    /**
     * En cours ou en file. {@code manifest} : connu dès la fin de l'analyse (null avant) ; {@code playableEarly} : la
     * copie HLS en cours d'écriture est déjà lisible (assez d'avance) ; {@code kind} : BASE ou CONV ; {@code paused} :
     * conversion suspendue (lectures en cours) ; {@code writtenSeconds} : durée déjà écrite de la copie en cours (une
     * reprise au milieu n'est servie que quand la copie l'a dépassée).
     */
    public record Preparing(String phase, int position, Double progress, long estimatedSeconds, int retryAfterSeconds,
                            String key, WebManifest manifest, boolean playableEarly, String kind, boolean paused,
                            double writtenSeconds) implements Decision {
    }

    /** Échec ; {@code manifest} : pistes connues (null si l'analyse elle-même a échoué) ; {@code retryAt} null : l'admin relance. */
    public record Failed(String reason, Instant retryAt, WebManifest manifest) implements Decision {
    }

    public record CacheFull(int retryAfterSeconds, WebManifest manifest) implements Decision {
    }

    public record Unavailable(String reason) implements Decision {
    }

    record Job(long mediaFileId, String kind, String status, String phase, int priority, String key, long sourceSize,
               OffsetDateTime sourceModified, WebManifest manifest, Instant nextAttempt, String blocked, String error, int attempts) {
    }

    /** Échec d'une étape, avec une raison lisible (sans chemin). */
    public static final class StepFailure extends Exception {
        public StepFailure(String reason) {
            super(reason);
        }
    }

    /** Une file : son fil, la préparation en cours et de quoi l'arrêter. */
    static final class Lane {
        final String kind;
        final Object signal = new Object();
        final AtomicReference<Process> process = new AtomicReference<>();
        volatile Thread thread;
        volatile Long current;
        volatile int currentPriority;
        volatile boolean paused;
        volatile boolean pausedEver;
        /** Raison d'un arrêt voulu (place cédée, fin de la nuit, annulation) : le travail repart en file ou disparaît. */
        volatile String abortReason;
        volatile boolean cancel;
        volatile long lastCheck;

        Lane(String kind) {
            this.kind = kind;
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
    WebSettings webSettings;
    @Inject
    PlaybackActivity playback;
    @Inject
    ObjectMapper json;

    private final Lane baseLane = new Lane(BASE);
    private final Lane convLane = new Lane(CONV);
    private final Map<Long, Double> progress = new ConcurrentHashMap<>();
    /** Vitesse annoncée par ffmpeg pour la conversion en cours (fois le temps réel). */
    private final Map<Long, Double> speed = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastReadWrite = new ConcurrentHashMap<>();
    private volatile boolean running;
    /** Débit observé (octets/ms) d'une préparation BASE, pour estimer l'attente ; 40 Mo/s par défaut (disques du NAS). */
    private volatile double bytesPerMs = 40_000;
    private volatile String ffmpegVersion;
    private volatile boolean usable;
    private volatile boolean ffprobeOk;
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
            baseLane.thread = Thread.ofPlatform().daemon().name("web-prep").start(() -> loop(baseLane));
            convLane.thread = Thread.ofPlatform().daemon().name("web-convert").start(() -> loop(convLane));
        }
    }

    void onStop(@Observes ShutdownEvent e) {
        running = false;
        for (Lane l : List.of(baseLane, convLane)) {
            Thread t = l.thread;
            if (t != null) {
                t.interrupt();
            }
        }
    }

    /** Cache utilisable, ffmpeg et ffprobe présents (au démarrage ; aussi dans les tests). */
    public void checkTools() {
        usable = cache.usable();
        ffmpegVersion = probe.version(config.ffmpegPath());
        ffprobeOk = probe.version(config.ffprobePath()) != null;
    }

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
                    UPDATE web_job SET status = 'QUEUED', phase = NULL, started_at = NULL,
                        manifest = CASE WHEN kind = 'BASE' THEN NULL ELSE manifest END
                    WHERE status = 'RUNNING'""")) {
                st.executeUpdate();
            }
            List<String> lost = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement("SELECT cache_key, status FROM web_job");
                 ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    String k = rs.getString(1);
                    if (!"READY".equals(rs.getString(2))) {
                        continue;
                    }
                    if (cache.exists(k)) {
                        keep.add(k);
                    } else {
                        lost.add(k);
                    }
                }
            }
            for (String k : lost) {
                deleteByKey(c, k);
            }
        }
        int removed = cache.sweep(keep);
        if (removed > 0) {
            LOG.infof("Préparation web : %d dossier(s) temporaire(s) ou orphelin(s) effacé(s) du cache", removed);
        }
    }

    /** Une préparation est en cours ou peut démarrer : l'analyse de fond se met en pause (disque). */
    public boolean busy() {
        if (baseLane.current != null || convLane.current != null) {
            return true;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT 1 FROM web_job WHERE status = 'QUEUED' AND blocked IS NULL"
                     + " AND (next_attempt_at IS NULL OR next_attempt_at <= now()) AND (priority < 2 OR ?) LIMIT 1")) {
            st.setBoolean(1, webSettings.nightOpen());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
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

    // --- Demandes -------------------------------------------------------------------------------------------------------

    /** Lecture demandée : préparation de base prête, en cours (mise en file si besoin), en échec, cache plein ou indisponible. */
    public Decision request(long mediaFileId, long sourceSize, OffsetDateTime sourceModified) throws SQLException {
        return obtain(BASE, WebCache.key(BASE, mediaFileId, sourceSize, sourceModified), mediaFileId, sourceSize, sourceModified,
                null, PRIORITY_USER);
    }

    /**
     * Conversion pour le navigateur (10.3) d'un fichier dont la préparation de base est connue ({@code base} : pistes
     * analysées). Copie à la hauteur maximale réglée : changer le réglage refait les conversions à leur prochaine demande.
     */
    public Decision requestConversion(long mediaFileId, long sourceSize, OffsetDateTime sourceModified, WebManifest base,
                                      int priority) throws SQLException {
        int height = webSettings.maxHeight();
        return obtain(CONV, convKey(mediaFileId, sourceSize, sourceModified, height), mediaFileId, sourceSize, sourceModified,
                base.convertPlan(height), priority);
    }

    static String convKey(long id, long size, OffsetDateTime modified, int height) {
        return WebCache.key(CONV + height, id, size, modified);
    }

    private Decision obtain(String kind, String key, long mediaFileId, long sourceSize, OffsetDateTime sourceModified,
                            WebManifest plan, int priority) throws SQLException {
        if (!usable() && lastToolCheck.isBefore(Instant.now().minusSeconds(60))) {
            lastToolCheck = Instant.now();
            checkTools();
        }
        if (!usable()) {
            return new Unavailable(ffmpegVersion == null ? "outil vidéo absent du serveur" : "dossier du cache web inaccessible");
        }
        Optional<Job> job = job(mediaFileId, kind);
        if (job.isPresent() && !job.get().key().equals(key)) {
            // Source modifiée (ou autre hauteur de conversion) : l'ancienne préparation ne vaut plus rien.
            if ("RUNNING".equals(job.get().status())) {
                lane(kind).abortReason = "source ou réglage changé";
                lane(kind).cancel = true;
                stop(lane(kind), job.get().mediaFileId());
            }
            cache.delete(job.get().key());
            delete(mediaFileId, kind);
            job = Optional.empty();
        }
        if (job.isPresent()) {
            Job j = job.get();
            switch (j.status()) {
                case "READY" -> {
                    if (cache.exists(key) && j.manifest() != null) {
                        return new Ready(key, j.manifest());
                    }
                    delete(mediaFileId, kind); // dossier disparu du cache : on refait
                }
                case "FAILED" -> {
                    if (j.nextAttempt() == null || j.nextAttempt().isAfter(Instant.now())) {
                        return new Failed(j.error(), j.nextAttempt(), j.manifest());
                    }
                    requeue(mediaFileId, kind, priority, false);
                }
                default -> {
                    if (j.priority() > priority) {
                        raise(mediaFileId, kind, priority);
                    }
                }
            }
        }
        if (job(mediaFileId, kind).isEmpty()) {
            insert(mediaFileId, kind, key, sourceSize, sourceModified, priority, plan);
        }
        if (BASE.equals(kind)) {
            remuxTest.yieldTo();
        }
        wake(kind);
        Job j = job(mediaFileId, kind).orElseThrow();
        if ("READY".equals(j.status()) && j.manifest() != null) {
            return new Ready(key, j.manifest()); // terminé entre-temps (fichier sans rien à extraire)
        }
        if ("FAILED".equals(j.status())) {
            return new Failed(j.error(), j.nextAttempt(), j.manifest());
        }
        if ("CACHE_FULL".equals(j.blocked())) {
            return new CacheFull(60, j.manifest());
        }
        return preparing(j);
    }

    /**
     * Mise en file sans attendre de réponse (préventif de nuit, « Préparer l'animé ») : rien si la préparation existe déjà
     * (prête, en cours, ou en échec définitif) ; une demande plus urgente garde sa priorité.
     */
    public void enqueue(String kind, long mediaFileId, long sourceSize, OffsetDateTime sourceModified, WebManifest base, int priority)
            throws SQLException {
        String key;
        WebManifest plan = null;
        if (CONV.equals(kind)) {
            int height = webSettings.maxHeight();
            key = convKey(mediaFileId, sourceSize, sourceModified, height);
            plan = base.convertPlan(height);
        } else {
            key = WebCache.key(BASE, mediaFileId, sourceSize, sourceModified);
        }
        Optional<Job> job = job(mediaFileId, kind);
        if (job.isPresent() && !job.get().key().equals(key) && !"RUNNING".equals(job.get().status())) {
            cache.delete(job.get().key());
            delete(mediaFileId, kind);
            job = Optional.empty();
        }
        if (job.isEmpty()) {
            insert(mediaFileId, kind, key, sourceSize, sourceModified, priority, plan);
        } else if (job.get().priority() > priority && !"READY".equals(job.get().status())) {
            raise(mediaFileId, kind, priority);
        }
        wake(kind);
    }

    private Lane lane(String kind) {
        return CONV.equals(kind) ? convLane : baseLane;
    }

    private void wake(String kind) {
        Lane l = lane(kind);
        synchronized (l.signal) {
            l.signal.notifyAll();
        }
    }

    /** Position, progression et attente estimée (préparations avant elle + le reste de la sienne). */
    Preparing preparing(Job self) throws SQLException {
        int position = 0;
        double aheadSeconds = 0;
        long aheadBytes = 0;
        Double p = progress.get(self.mediaFileId());
        boolean conv = CONV.equals(self.kind());
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT j.media_file_id, j.status, j.source_size, j.manifest FROM web_job j, web_job me
                     WHERE me.media_file_id = ? AND me.kind = ? AND j.kind = me.kind AND j.media_file_id <> me.media_file_id
                       AND j.status IN ('QUEUED', 'RUNNING') AND j.blocked IS NULL
                       AND (j.status = 'RUNNING' OR (j.next_attempt_at IS NULL OR j.next_attempt_at <= now()))
                       AND (j.status = 'RUNNING' OR j.priority < me.priority OR (j.priority = me.priority AND j.requested_at < me.requested_at)
                            OR (j.priority = me.priority AND j.requested_at = me.requested_at AND j.media_file_id < me.media_file_id))""")) {
            st.setLong(1, self.mediaFileId());
            st.setString(2, self.kind());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    position++;
                    double done = "RUNNING".equals(rs.getString(2)) ? progress.getOrDefault(rs.getLong(1), 0.0) : 0;
                    if (conv) {
                        WebManifest m = manifest(rs.getString(4));
                        aheadSeconds += m == null || m.durationSeconds() == null ? 1440 / 2.0
                                : m.durationSeconds() * (1 - done) / expectedSpeed(m, speed.get(rs.getLong(1)));
                    } else {
                        aheadBytes += (long) (rs.getLong(3) * (1 - done));
                    }
                }
            }
        }
        boolean run = "RUNNING".equals(self.status());
        if (run) {
            position = 0;
            aheadBytes = 0;
            aheadSeconds = 0;
        }
        double own = p == null ? 0 : p;
        long seconds;
        boolean early = false;
        if (conv) {
            WebManifest m = self.manifest();
            double duration = m == null || m.durationSeconds() == null ? 1440 : m.durationSeconds();
            Double measured = run ? speed.get(self.mediaFileId()) : null;
            double sp = expectedSpeed(m, measured);
            seconds = Math.round(aheadSeconds + duration * (1 - own) / sp) + 5L * (position + 1);
            if (run && m != null && steps.playableEarly(cache.partDir(self.key()), m)) {
                // Assez d'avance (2 min, ou presque tout) et la préparation va plus vite que la lecture.
                double written = steps.writtenSeconds(cache.partDir(self.key()));
                early = written >= Math.min(120, duration * 0.9) && (sp >= 1.05 || written >= duration * 0.9);
            }
        } else {
            seconds = Math.round((aheadBytes + (long) (self.sourceSize() * (1 - own))) / bytesPerMs / 1000) + 2L * (position + 1);
            early = run && "HLS".equals(self.phase()) && self.manifest() != null
                    && steps.playableEarly(cache.partDir(self.key()), self.manifest());
        }
        int retry = (int) Math.max(1, Math.min(10, seconds / 5));
        boolean paused = run && conv && convLane.paused && Long.valueOf(self.mediaFileId()).equals(convLane.current);
        double written = early ? steps.writtenSeconds(cache.partDir(self.key())) : 0;
        return new Preparing(run ? self.phase() : "QUEUED", position, run ? own : null, seconds, retry, self.key(), self.manifest(),
                early, self.kind(), paused, written);
    }

    /** Vitesse attendue d'une conversion (fois le temps réel) : mesurée, sinon la dernière observée, sinon l'estimation §6. */
    double expectedSpeed(WebManifest plan, Double measured) {
        if (measured != null && measured > 0.05) {
            return measured;
        }
        String key = speedKey(plan);
        return settings.getDouble(key).filter(v -> v > 0.05).orElse(defaultSpeed(plan));
    }

    static double defaultSpeed(WebManifest plan) {
        if (plan == null || WebManifest.AUDIO_ONLY.equals(plan.container())) {
            return 15;
        }
        Integer h = plan.video() == null ? null : plan.video().height();
        return h == null || h > 720 ? 0.9 : h > 480 ? 1.4 : 4;
    }

    static String speedKey(WebManifest plan) {
        if (plan == null || WebManifest.AUDIO_ONLY.equals(plan.container())) {
            return "web.speed.audio";
        }
        Integer h = plan.video() == null ? null : plan.video().height();
        return "web.speed.video" + (h == null || h > 720 ? "1080" : h > 480 ? "720" : "sd");
    }

    /**
     * Préparation servie pour {@code /api/stream/{id}/web/{clé}/…} : prête, ou en cours avec sa copie HLS déjà lisible.
     * Marque la lecture (au plus une écriture par minute) : protège la préparation de la purge.
     */
    public Optional<Job> served(long mediaFileId, String key) throws SQLException {
        Optional<Job> j = jobByKey(key);
        if (j.isEmpty() || j.get().mediaFileId() != mediaFileId || !("READY".equals(j.get().status()) || "RUNNING".equals(j.get().status()))) {
            return Optional.empty();
        }
        touch(key);
        return j;
    }

    private void touch(String key) throws SQLException {
        Instant last = lastReadWrite.get(key);
        if (last != null && last.isAfter(Instant.now().minusSeconds(60))) {
            return;
        }
        lastReadWrite.put(key, Instant.now());
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("UPDATE web_job SET last_read_at = now() WHERE cache_key = ?")) {
            st.setString(1, key);
            st.executeUpdate();
        }
    }

    // --- Base -----------------------------------------------------------------------------------------------------------

    private static final String COLUMNS = """
            media_file_id, kind, status, phase, priority, cache_key, source_size, source_modified, manifest,
            next_attempt_at, blocked, error, attempts""";

    Optional<Job> job(long mediaFileId) throws SQLException {
        return job(mediaFileId, BASE);
    }

    Optional<Job> job(long mediaFileId, String kind) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT " + COLUMNS + " FROM web_job WHERE media_file_id = ? AND kind = ?")) {
            st.setLong(1, mediaFileId);
            st.setString(2, kind);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(row(rs)) : Optional.empty();
            }
        }
    }

    Optional<Job> jobByKey(String key) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT " + COLUMNS + " FROM web_job WHERE cache_key = ?")) {
            st.setString(1, key);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(row(rs)) : Optional.empty();
            }
        }
    }

    private Job row(ResultSet rs) throws SQLException {
        OffsetDateTime na = rs.getObject(10, OffsetDateTime.class);
        return new Job(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6),
                rs.getLong(7), rs.getObject(8, OffsetDateTime.class), manifest(rs.getString(9)),
                na == null ? null : na.toInstant(), rs.getString(11), rs.getString(12), rs.getInt(13));
    }

    WebManifest manifest(String raw) {
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

    private void insert(long id, String kind, String key, long size, OffsetDateTime modified, int priority, WebManifest plan)
            throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     INSERT INTO web_job (media_file_id, kind, status, priority, cache_key, source_size, source_modified, manifest)
                     VALUES (?, ?, 'QUEUED', ?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING""")) {
            st.setLong(1, id);
            st.setString(2, kind);
            st.setInt(3, priority);
            st.setString(4, key);
            st.setLong(5, size);
            st.setObject(6, modified);
            st.setString(7, plan == null ? null : manifestJson(plan));
            st.executeUpdate();
        }
    }

    void requeue(long id, String kind, int priority, boolean resetAttempts) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("UPDATE web_job SET status = 'QUEUED', priority = LEAST(priority, ?),"
                     + " next_attempt_at = NULL, blocked = NULL" + (resetAttempts ? ", attempts = 0, error = NULL" : "")
                     + " WHERE media_file_id = ? AND kind = ? AND status IN ('QUEUED', 'FAILED')")) {
            st.setInt(1, priority);
            st.setLong(2, id);
            st.setString(3, kind);
            st.executeUpdate();
        }
    }

    /** Demande plus urgente : la priorité monte (en file comme en cours : une conversion en cours n'est plus suspendue). */
    private void raise(long id, String kind, int priority) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("UPDATE web_job SET priority = LEAST(priority, ?), blocked = CASE"
                     + " WHEN blocked = 'CACHE_FULL' THEN blocked ELSE NULL END WHERE media_file_id = ? AND kind = ?")) {
            st.setInt(1, priority);
            st.setLong(2, id);
            st.setString(3, kind);
            st.executeUpdate();
        }
        Lane l = lane(kind);
        if (Long.valueOf(id).equals(l.current)) {
            l.currentPriority = Math.min(l.currentPriority, priority);
        }
    }

    private void delete(long id, String kind) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("DELETE FROM web_job WHERE media_file_id = ? AND kind = ?")) {
            st.setLong(1, id);
            st.setString(2, kind);
            st.executeUpdate();
        }
    }

    private static void deleteByKey(Connection c, String key) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("DELETE FROM web_job WHERE cache_key = ?")) {
            st.setString(1, key);
            st.executeUpdate();
        }
    }

    private void phase(Job job, String phase, WebManifest m) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("UPDATE web_job SET phase = ?, manifest = coalesce(?::jsonb, manifest)"
                     + " WHERE media_file_id = ? AND kind = ?")) {
            st.setString(1, phase);
            st.setString(2, m == null ? null : manifestJson(m));
            st.setLong(3, job.mediaFileId());
            st.setString(4, job.kind());
            st.executeUpdate();
        }
    }

    // --- Exécution ------------------------------------------------------------------------------------------------------

    private void loop(Lane lane) {
        while (running) {
            try {
                Optional<Job> next = usable() ? claim(lane.kind) : Optional.empty();
                if (next.isPresent()) {
                    process(next.get());
                } else {
                    synchronized (lane.signal) {
                        lane.signal.wait(30_000);
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

    /** Prochaine préparation de cette sorte : la plus urgente, puis dans l'ordre d'arrivée ; préventif la nuit seulement. */
    Optional<Job> claim(String kind) throws SQLException {
        Long id = null;
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE web_job SET status = 'RUNNING', phase = 'PROBE', started_at = now(), blocked = NULL
                     WHERE (media_file_id, kind) = (SELECT media_file_id, kind FROM web_job WHERE kind = ? AND status = 'QUEUED'
                         AND (next_attempt_at IS NULL OR next_attempt_at <= now()) AND (priority < 2 OR ?)
                         ORDER BY priority, requested_at, media_file_id LIMIT 1 FOR UPDATE SKIP LOCKED)
                     RETURNING media_file_id""")) {
            st.setString(1, kind);
            st.setBoolean(2, webSettings.nightOpen());
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    id = rs.getLong(1);
                }
            }
        }
        return id == null ? Optional.empty() : job(id, kind);
    }

    /** Traite une préparation de base tout de suite (tests ; en temps normal, le fil « web-prep »). */
    public boolean processNext() throws SQLException, InterruptedException {
        return processNext(BASE);
    }

    /** Traite une préparation de cette sorte tout de suite (tests ; en temps normal, son fil). */
    public boolean processNext(String kind) throws SQLException, InterruptedException {
        Optional<Job> j = claim(kind);
        if (j.isEmpty()) {
            return false;
        }
        process(j.get());
        return true;
    }

    void process(Job job) throws SQLException, InterruptedException {
        Lane lane = lane(job.kind());
        lane.current = job.mediaFileId();
        lane.currentPriority = job.priority();
        lane.abortReason = null;
        lane.cancel = false;
        lane.paused = false;
        lane.pausedEver = false;
        lane.lastCheck = 0;
        progress.put(job.mediaFileId(), 0.0);
        try {
            if (CONV.equals(job.kind())) {
                processConv(job, lane);
            } else {
                processBase(job);
            }
        } finally {
            progress.remove(job.mediaFileId());
            speed.remove(job.mediaFileId());
            lane.current = null;
            lane.paused = false;
        }
    }

    private void processBase(Job job) throws SQLException, InterruptedException {
        long id = job.mediaFileId();
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
        phase(job, "PROBE", m);
        long needed = m.wantsHls() ? job.sourceSize() + job.sourceSize() / 20 + 16 * MB : 64 * MB;
        if (!room(job, needed, m.wantsHls())) {
            return;
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
            done = steps.run(file.get(), part, m, (phase, value) -> progress(job, phase, value));
        } catch (StepFailure e) {
            cache.delete(job.key());
            fail(job, e.getMessage(), false);
            return;
        }
        long elapsed = System.currentTimeMillis() - start;
        if (!publish(job, done, elapsed)) {
            return;
        }
        if (elapsed > 1000 && done.hls()) {
            bytesPerMs = 0.7 * bytesPerMs + 0.3 * ((double) job.sourceSize() / elapsed);
        }
        afterBase(job, done);
    }

    /**
     * Préparation de base faite pour l'admin ou le préventif : la conversion suit si elle sert (aucun navigateur ne
     * lirait la vidéo ou le son tels quels) ; le préventif ne convertit la vidéo que si le réglage le permet (D8).
     */
    private void afterBase(Job job, WebManifest done) throws SQLException {
        if (job.priority() < PRIORITY_ADMIN || !done.convertible()) {
            return;
        }
        WebDecision.Result d = WebDecision.decide(done, WebDecision.DEFAULT);
        if (d.mode() != WebDecision.Mode.UNSUPPORTED || !d.convert()) {
            return;
        }
        boolean video = !WebManifest.universalVideo(done.video());
        if (job.priority() >= PRIORITY_NIGHT && video && !webSettings.preventiveVideo()) {
            return;
        }
        enqueue(CONV, job.mediaFileId(), job.sourceSize(), job.sourceModified(), done, job.priority());
    }

    private void processConv(Job job, Lane lane) throws SQLException, InterruptedException {
        long id = job.mediaFileId();
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
        if (!m.convertible()) {
            fail(job, "vidéo que le serveur ne sait pas convertir (" + (m.video() == null ? "aucune" : m.video().codec()) + ")", true);
            return;
        }
        int height = job.manifest() != null && job.manifest().video() != null && job.manifest().video().height() != null
                ? Math.max(job.manifest().video().height(), 240) : webSettings.maxHeight();
        WebManifest plan = m.convertPlan(height);
        phase(job, WebManifest.AUDIO_ONLY.equals(plan.container()) ? "AUDIO" : "CONVERT", plan);
        if (!room(job, convertedBytes(plan, job.sourceSize()), true)) {
            return;
        }
        Path part;
        try {
            part = cache.prepare(job.key());
        } catch (IOException e) {
            fail(job, "écriture impossible dans le cache web", false);
            return;
        }
        long start = System.currentTimeMillis();
        WebPrepSteps.Control control = new WebPrepSteps.Control(lane.process, () -> pauseOrStop(job, lane),
                s -> speed.put(id, s));
        WebManifest done;
        try {
            done = steps.convert(file.get(), part, m, plan, control, (phase, value) -> progress(job, phase, value));
        } catch (StepFailure e) {
            cache.delete(job.key());
            String why = lane.abortReason;
            if (why != null) {
                if (lane.cancel) {
                    delete(id, CONV);
                    LOG.infof("Conversion web du fichier %d arrêtée (%s)", id, why);
                } else {
                    putBack(job);
                    LOG.infof("Conversion web du fichier %d remise en file (%s)", id, why);
                }
                return;
            }
            fail(job, e.getMessage(), false);
            return;
        }
        long elapsed = System.currentTimeMillis() - start;
        if (!publish(job, done, elapsed)) {
            return;
        }
        Double last = speed.get(id);
        if (!lane.pausedEver && last != null && last > 0.05) {
            double prev = settings.getDouble(speedKey(plan)).orElse(defaultSpeed(plan));
            settings.put(speedKey(plan), String.format(Locale.ROOT, "%.3f", 0.6 * prev + 0.4 * last));
        }
    }

    /** Place estimée d'une conversion : vidéo (~0,8 Go/h en 720p, 1,5 Go/h en 1080p) ou source copiée, + AAC. */
    static long convertedBytes(WebManifest plan, long sourceSize) {
        double hours = plan.durationSeconds() == null ? 0.5 : plan.durationSeconds() / 3600;
        long audio = (long) (plan.audio().size() * 80 * MB * hours);
        long video = WebManifest.AUDIO_ONLY.equals(plan.container()) ? sourceSize
                : (long) (hours * (plan.video().height() != null && plan.video().height() > 720 ? 1500 : 800) * MB);
        return video + audio + 32 * MB;
    }

    /**
     * Appelé chaque seconde pendant une conversion : suspendue pendant les lectures si personne ne l'attend (admin,
     * préventif) ; arrêtée et remise en file si une demande plus urgente attend, ou à la fin de la nuit (préventif).
     */
    boolean pauseOrStop(Job job, Lane lane) {
        long now = System.nanoTime();
        if (now - lane.lastCheck > 5_000_000_000L) {
            lane.lastCheck = now;
            try {
                lane.currentPriority = currentPriority(job);
                if (lane.currentPriority >= PRIORITY_NIGHT && !webSettings.nightOpen()) {
                    lane.abortReason = "fin de la fenêtre de nuit";
                } else if (lane.currentPriority >= PRIORITY_ADMIN && urgentWaiting(job.kind(), lane.currentPriority)) {
                    lane.abortReason = "une demande plus urgente passe avant";
                }
            } catch (SQLException e) {
                LOG.debugf("Conversion web : état non relu (%s)", e.getMessage());
            }
            if (lane.abortReason != null) {
                stop(lane, job.mediaFileId());
                return false;
            }
        }
        boolean pause = lane.currentPriority >= PRIORITY_ADMIN && playback.active();
        if (pause != lane.paused) {
            LOG.infof("Conversion web du fichier %d %s", job.mediaFileId(), pause ? "suspendue (lecture en cours)" : "reprise");
        }
        lane.paused = pause;
        if (pause) {
            lane.pausedEver = true;
        }
        return pause;
    }

    private static void stop(Lane lane, long mediaFileId) {
        Process p = lane.process.get();
        if (p != null && Long.valueOf(mediaFileId).equals(lane.current)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    private int currentPriority(Job job) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT priority FROM web_job WHERE media_file_id = ? AND kind = ?")) {
            st.setLong(1, job.mediaFileId());
            st.setString(2, job.kind());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : job.priority();
            }
        }
    }

    private boolean urgentWaiting(String kind, int priority) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT 1 FROM web_job WHERE kind = ? AND status = 'QUEUED' AND priority < ?"
                     + " AND blocked IS NULL AND (next_attempt_at IS NULL OR next_attempt_at <= now()) LIMIT 1")) {
            st.setString(1, kind);
            st.setInt(2, priority);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Arrêt voulu (pas un échec) : la conversion repart en file, son essai n'est pas compté. */
    private void putBack(Job job) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("UPDATE web_job SET status = 'QUEUED', phase = NULL, started_at = NULL"
                     + " WHERE media_file_id = ? AND kind = ? AND status = 'RUNNING'")) {
            st.setLong(1, job.mediaFileId());
            st.setString(2, job.kind());
            st.executeUpdate();
        }
    }

    private void progress(Job job, String phase, double value) {
        progress.put(job.mediaFileId(), value);
        if (phase != null) {
            try {
                phase(job, phase, null);
            } catch (SQLException e) {
                LOG.debugf("Préparation web : étape non enregistrée (%s)", e.getMessage());
            }
        }
    }

    /** Place dans le cache ; sinon échec (trop gros) ou attente (plein). */
    private boolean room(Job job, long needed, boolean copy) throws SQLException {
        switch (makeRoom(needed, job.key(), copy)) {
            case TOO_BIG -> {
                fail(job, "fichier trop gros pour le cache web (" + needed / MB + " Mo, cache de " + maxBytes() / MB + " Mo)", true);
                return false;
            }
            case FULL -> {
                block(job);
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    /** Publication atomique dans le cache, puis READY. */
    private boolean publish(Job job, WebManifest done, long elapsed) throws SQLException {
        long bytes;
        try {
            bytes = WebCache.treeSize(cache.partDir(job.key()));
            cache.publish(job.key());
        } catch (IOException e) {
            cache.delete(job.key());
            fail(job, "publication impossible dans le cache web", false);
            return false;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE web_job SET status = 'READY', phase = NULL, manifest = ?::jsonb, bytes = ?, finished_at = now(),
                         error = NULL, blocked = NULL, next_attempt_at = NULL, elapsed_ms = ? WHERE media_file_id = ? AND kind = ?""")) {
            st.setString(1, manifestJson(done));
            st.setLong(2, bytes);
            st.setLong(3, elapsed);
            st.setLong(4, job.mediaFileId());
            st.setString(5, job.kind());
            st.executeUpdate();
        }
        LOG.infof("Préparation web : fichier %d prêt (%s%d Mo, %d ms)", job.mediaFileId(),
                CONV.equals(job.kind()) ? "conversion, " : done.hls() ? "copie HLS, " : "", bytes / MB, elapsed);
        return true;
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

    /**
     * Fait de la place : préparations prêtes les moins récemment lues d'abord, jamais une lue récemment ni celle-ci.
     * La réserve d'espace libre du disque ne s'applique qu'aux copies HLS (taille de l'épisode) : sous-titres et
     * polices seuls (quelques Mo, lecture de l'original) passent même sur un disque presque plein.
     */
    synchronized Room makeRoom(long needed, String exceptKey, boolean copy) throws SQLException {
        long max = maxBytes();
        long reserve = copy ? (long) (config.webCacheReserveGb() * 1e9) : 0;
        if (needed > max) {
            return Room.TOO_BIG;
        }
        long used = usedBytes();
        long free = cache.usableSpace();
        if (used + needed <= max && (free < 0 || free - reserve >= needed)) {
            return Room.OK;
        }
        used = evict(used, needed, max, reserve, exceptKey);
        free = cache.usableSpace();
        if (used + needed <= max && (free < 0 || free - reserve >= needed)) {
            return Room.OK;
        }
        LOG.infof("Préparation web : pas assez de place (cache %d/%d Mo, disque libre %d Mo, réserve %d Mo, besoin %d Mo)",
                used / MB, max / MB, free / MB, reserve / MB, needed / MB);
        return Room.FULL;
    }

    /** Efface les préparations prêtes les moins récemment lues (jamais une lue récemment) jusqu'à la place voulue. */
    long evict(long used, long needed, long max, long reserve, String exceptKey) throws SQLException {
        List<Object[]> candidates = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT media_file_id, cache_key, coalesce(bytes, 0), kind FROM web_job
                     WHERE status = 'READY' AND cache_key <> ?
                       AND (last_read_at IS NULL OR last_read_at < now() - make_interval(secs => ?))
                     ORDER BY coalesce(last_read_at, finished_at) NULLS FIRST, media_file_id""")) {
            st.setString(1, exceptKey == null ? "" : exceptKey);
            st.setLong(2, config.webInUse().toSeconds());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new Object[]{rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4)});
                }
            }
        }
        long free = cache.usableSpace();
        int i = 0;
        while ((used + needed > max || (free >= 0 && free - reserve < needed)) && i < candidates.size()) {
            Object[] victim = candidates.get(i++);
            cache.delete((String) victim[1]);
            try (Connection c = dataSource.getConnection()) {
                deleteByKey(c, (String) victim[1]);
            }
            used -= (Long) victim[2];
            free = cache.usableSpace();
            LOG.infof("Préparation web : %s du fichier %d effacée du cache (la moins récemment lue)",
                    CONV.equals(victim[3]) ? "conversion" : "préparation", (Long) victim[0]);
        }
        return used;
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

    /**
     * Échec : nouvel essai automatique espacé (10 min, puis 20), {@link #MAX_ATTEMPTS} essais au plus, ensuite l'admin
     * relance ; un échec définitif (source absente, trop gros) attend un jour.
     */
    private void fail(Job job, String reason, boolean permanent) throws SQLException {
        LOG.warnf("Préparation web (%s) impossible pour le fichier %d : %s", job.kind(), job.mediaFileId(), reason);
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE web_job SET status = 'FAILED', phase = NULL, attempts = attempts + 1, finished_at = now(), error = ?,
                         blocked = NULL, next_attempt_at = CASE WHEN ? THEN now() + interval '1 day'
                             WHEN attempts + 1 >= ? THEN NULL
                             ELSE now() + make_interval(mins => LEAST(1440, 10 * power(2, attempts)::int)) END
                     WHERE media_file_id = ? AND kind = ?""")) {
            st.setString(1, reason == null ? "échec" : reason.length() > 600 ? reason.substring(0, 600) : reason);
            st.setBoolean(2, permanent);
            st.setInt(3, MAX_ATTEMPTS);
            st.setLong(4, job.mediaFileId());
            st.setString(5, job.kind());
            st.executeUpdate();
        }
    }

    private void block(Job job) throws SQLException {
        LOG.infof("Préparation web : cache plein (préparations en cours de lecture), fichier %d en attente", job.mediaFileId());
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE web_job SET status = 'QUEUED', phase = NULL, blocked = 'CACHE_FULL', started_at = NULL,
                         next_attempt_at = now() + interval '1 minute' WHERE media_file_id = ? AND kind = ?""")) {
            st.setLong(1, job.mediaFileId());
            st.setString(2, job.kind());
            st.executeUpdate();
        }
    }

    // --- Administration -------------------------------------------------------------------------------------------------

    /** Préparation en cours d'une file, pour l'administration. */
    public record Running(String kind, long mediaFileId, int priority, Double progress, Double speed, boolean paused) {
    }

    public List<Running> running() {
        List<Running> out = new ArrayList<>();
        for (Lane l : List.of(baseLane, convLane)) {
            Long id = l.current;
            if (id != null) {
                out.add(new Running(l.kind, id, l.currentPriority, progress.get(id), speed.get(id), l.paused));
            }
        }
        return out;
    }

    /** Relance par l'admin (échec, ou attente d'un nouvel essai) : essais remis à zéro, priorité « admin » au moins. */
    public boolean retry(long mediaFileId, String kind) throws SQLException {
        int n;
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE web_job SET status = 'QUEUED', attempts = 0, error = NULL, next_attempt_at = NULL, blocked = NULL,
                         priority = LEAST(priority, 1), requested_at = now()
                     WHERE media_file_id = ? AND kind = ? AND status IN ('FAILED', 'QUEUED')""")) {
            st.setLong(1, mediaFileId);
            st.setString(2, kind);
            n = st.executeUpdate();
        }
        wake(kind);
        return n > 0;
    }

    /**
     * Annulation par l'admin : la préparation disparaît (dossier compris) ; une conversion en cours est arrêtée. Une
     * préparation de base en cours (quelques secondes) va au bout. Faux si rien à annuler.
     */
    public boolean cancel(long mediaFileId, String kind) throws SQLException {
        Optional<Job> j = job(mediaFileId, kind);
        if (j.isEmpty()) {
            return false;
        }
        Lane l = lane(kind);
        if ("RUNNING".equals(j.get().status()) && Long.valueOf(mediaFileId).equals(l.current)) {
            if (!CONV.equals(kind)) {
                return false;
            }
            l.abortReason = "annulée par l'admin";
            l.cancel = true;
            stop(l, mediaFileId);
            return true;
        }
        cache.delete(j.get().key());
        delete(mediaFileId, kind);
        return true;
    }

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

    /** Pour les tests et l'admin : conversion en cours suspendue. */
    boolean convPaused() {
        return convLane.paused;
    }

    Long convCurrent() {
        return convLane.current;
    }

    /** Pour les tests : processus ffmpeg de la conversion en cours. */
    Long convPid() {
        Process p = convLane.process.get();
        return p == null ? null : p.pid();
    }

    Double currentSpeed(long mediaFileId) {
        return speed.get(mediaFileId);
    }
}
