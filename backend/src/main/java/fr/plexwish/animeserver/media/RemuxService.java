package fr.plexwish.animeserver.media;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
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

/**
 * Remux à la demande (ARCHITECTURE §23) : AVI / OGM copiés en MKV sans ré-encodage, pour Android. File d'attente en
 * base (une ligne par source), **un seul remux à la fois**, demandes des utilisateurs avant les préparations de
 * l'admin, travail de fond (analyse, test à blanc) en pause tant que la file n'est pas vide. Copie vérifiée par
 * ffprobe avant d'être servie ; cache borné, purge des copies les moins récemment lues (jamais une copie en cours
 * de lecture).
 */
@ApplicationScoped
public class RemuxService {

    private static final Logger LOG = Logger.getLogger(RemuxService.class);
    /** Extensions remuxées quand le fichier n'est pas encore analysé (prudence : jamais l'original illisible). */
    static final Set<String> REMUX_EXTENSIONS = Set.of("avi", "ogm", "ogv");
    private static final long MB = 1_000_000L;

    // --- Réponses à « je veux lire ce fichier » -----------------------------------------------------------------

    public sealed interface Decision permits NotNeeded, Ready, Preparing, Failed, CacheFull, Unavailable {
    }

    /** Fichier lisible tel quel : URL de l'original. */
    public record NotNeeded() implements Decision {
    }

    /** Copie prête : {@code key} pour signer l'URL de la copie. */
    public record Ready(String key) implements Decision {
    }

    /**
     * Préparation en cours : {@code position} 0 = en cours (ou la prochaine), n = n remux à faire avant ;
     * {@code progress} 0..1 si commencé ; secondes estimées avant que la copie soit prête.
     */
    public record Preparing(int position, Double progress, long estimatedSeconds, int retryAfterSeconds) implements Decision {
    }

    public record Failed(String reason, Instant retryAt) implements Decision {
    }

    public record CacheFull(int retryAfterSeconds) implements Decision {
    }

    public record Unavailable(String reason) implements Decision {
    }

    record Job(long mediaFileId, String status, int priority, String key, long sourceSize, OffsetDateTime sourceModified,
               String variant, Instant lastRead, Instant nextAttempt, String blocked, String error, int attempts) {
    }

    @Inject
    AgroalDataSource dataSource;
    @Inject
    MediaConfig config;
    @Inject
    RemuxCache cache;
    @Inject
    MediaProbeService probe;
    @Inject
    RemuxTestService remuxTest;

    private final Object signal = new Object();
    private final Map<Long, Double> progress = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastReadWrite = new ConcurrentHashMap<>();
    private volatile Thread thread;
    private volatile boolean running;
    private volatile Long current;
    /** Débit observé (octets/ms), pour estimer l'attente ; 40 Mo/s par défaut (NAS, disques durs). */
    private volatile double bytesPerMs = 40_000;
    private volatile String ffmpegVersion;
    private volatile boolean usable;
    private volatile Instant lastToolCheck = Instant.EPOCH;

    void onStart(@Observes StartupEvent e) throws SQLException {
        checkTools();
        if (!usable) {
            LOG.warnf("Remux : dossier du cache %s non accessible en écriture : remux indisponible", cache.root());
        }
        if (ffmpegVersion == null) {
            LOG.warnf("Remux : ffmpeg introuvable (%s) : remux indisponible", config.ffmpegPath());
        }
        recover();
        if (config.remuxWorkerEnabled()) {
            running = true;
            thread = Thread.ofPlatform().daemon().name("remux").start(this::loop);
        }
    }

    /** Cache utilisable et ffmpeg présent (au démarrage ; aussi dans les tests). */
    void checkTools() {
        usable = cache.usable();
        ffmpegVersion = probe.version(config.ffmpegPath());
    }

    void onStop(@Observes ShutdownEvent e) {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * Démarrage : un remux interrompu repart en file ; copies prêtes dont le fichier a disparu oubliées ;
     * fichiers temporaires et copies inconnues effacés.
     */
    void recover() throws SQLException {
        Set<String> keep = new HashSet<>();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement st = c.prepareStatement("UPDATE remux_job SET status = 'QUEUED', started_at = NULL WHERE status = 'RUNNING'")) {
                st.executeUpdate();
            }
            List<Long> lost = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement("SELECT media_file_id, cache_key FROM remux_job WHERE status = 'READY'");
                 ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    if (cache.existing(rs.getString(2)).isPresent()) {
                        keep.add(rs.getString(2));
                    } else {
                        lost.add(rs.getLong(1));
                    }
                }
            }
            for (long id : lost) {
                try (PreparedStatement st = c.prepareStatement("DELETE FROM remux_job WHERE media_file_id = ?")) {
                    st.setLong(1, id);
                    st.executeUpdate();
                }
            }
        }
        int removed = cache.sweep(keep);
        if (removed > 0) {
            LOG.infof("Remux : %d fichier(s) temporaire(s) ou orphelin(s) effacé(s) du cache", removed);
        }
    }

    public boolean usable() {
        return usable && ffmpegVersion != null;
    }

    /** Un remux est en cours ou attend (hors attente faute de place) : le travail de fond se met en pause. */
    public boolean busy() {
        if (current != null) {
            return true;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT 1 FROM remux_job WHERE status = 'QUEUED' AND blocked IS NULL"
                     + " AND (next_attempt_at IS NULL OR next_attempt_at <= now()) LIMIT 1");
             ResultSet rs = st.executeQuery()) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    /** Tests seulement : taille du cache en octets à la place de REMUX_CACHE_MAX_GB. */
    private volatile Long maxBytesOverride;

    void maxBytesForTests(Long bytes) {
        maxBytesOverride = bytes;
    }

    @Inject
    fr.plexwish.animeserver.setup.AppSettings settings;

    /** Plafond du cache : réglage de l'installation (assistant, Administration > Réglages), sinon REMUX_CACHE_MAX_GB. */
    long maxBytes() {
        Long o = maxBytesOverride;
        if (o != null) {
            return o;
        }
        return (long) (settings.getDouble(fr.plexwish.animeserver.setup.InstallationSettings.REMUX_CAP_GB)
                .orElse(config.remuxCacheMaxGb()) * 1e9);
    }

    // --- Demande d'un utilisateur ---------------------------------------------------------------------------------

    /** Ce fichier doit-il être remuxé pour Android ? (analyse : « remux nécessaire » ; sans analyse : AVI / OGM). */
    public boolean needsRemux(long mediaFileId, String extension) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT status, android_class FROM media_probe WHERE media_file_id = ?")) {
            st.setLong(1, mediaFileId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next() && "OK".equals(rs.getString(1))) {
                    return "REMUX".equals(rs.getString(2));
                }
            }
        }
        return extension != null && REMUX_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    /**
     * Lecture demandée : copie prête, préparation en cours (mise en file si besoin, avant les préparations de l'admin),
     * échec (avec la date d'un nouvel essai automatique), cache plein, ou remux indisponible.
     */
    public Decision request(long mediaFileId, long sourceSize, OffsetDateTime sourceModified) throws SQLException {
        if (!usable() && lastToolCheck.isBefore(Instant.now().minusSeconds(60))) {
            // Droits du dossier corrigés, ffmpeg ajouté… : revérifié au plus une fois par minute.
            lastToolCheck = Instant.now();
            checkTools();
        }
        if (!usable()) {
            return new Unavailable(ffmpegVersion == null ? "outil de conversion absent du serveur"
                    : "dossier de conversion du serveur inaccessible");
        }
        String key = RemuxCache.key(mediaFileId, sourceSize, sourceModified);
        Optional<Job> job = job(mediaFileId);
        if (job.isPresent() && !job.get().key().equals(key)) {
            // Source modifiée : l'ancienne copie ne vaut plus rien.
            cache.delete(job.get().key());
            delete(mediaFileId);
            job = Optional.empty();
        }
        if (job.isPresent()) {
            Job j = job.get();
            switch (j.status()) {
                case "READY" -> {
                    if (cache.existing(key).isPresent()) {
                        return new Ready(key);
                    }
                    delete(mediaFileId); // fichier disparu du cache : on refait
                }
                case "FAILED" -> {
                    if (j.nextAttempt() != null && j.nextAttempt().isAfter(Instant.now())) {
                        return new Failed(j.error(), j.nextAttempt());
                    }
                    requeue(mediaFileId, 0, false);
                }
                default -> {
                    if (j.priority() > 0) {
                        requeue(mediaFileId, 0, false); // préparation de l'admin devenue urgente
                    }
                }
            }
        }
        if (job(mediaFileId).isEmpty()) {
            insert(mediaFileId, key, sourceSize, sourceModified, 0);
        }
        wakeAndYield();
        Job j = job(mediaFileId).orElseThrow();
        if ("CACHE_FULL".equals(j.blocked())) {
            return new CacheFull(60);
        }
        return preparing(j);
    }

    /** Position et attente estimée : remux en cours (reste) + ceux qui passent avant + celui-ci. */
    Preparing preparing(Job self) throws SQLException {
        long aheadBytes = 0;
        int position = 0;
        Double p = progress.get(self.mediaFileId());
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT media_file_id, status, source_size FROM remux_job
                     WHERE status IN ('QUEUED', 'RUNNING') AND blocked IS NULL AND media_file_id <> ?
                       AND (status = 'RUNNING' OR (next_attempt_at IS NULL OR next_attempt_at <= now()))
                       AND (status = 'RUNNING' OR priority < ? OR (priority = ? AND requested_at < ?)
                            OR (priority = ? AND requested_at = ? AND media_file_id < ?))""")) {
            OffsetDateTime requested = requestedAt(self.mediaFileId());
            st.setLong(1, self.mediaFileId());
            st.setInt(2, self.priority());
            st.setInt(3, self.priority());
            st.setObject(4, requested);
            st.setInt(5, self.priority());
            st.setObject(6, requested);
            st.setLong(7, self.mediaFileId());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    position++;
                    double done = "RUNNING".equals(rs.getString(2)) ? progress.getOrDefault(rs.getLong(1), 0.0) : 0;
                    aheadBytes += (long) (rs.getLong(3) * (1 - done));
                }
            }
        }
        if ("RUNNING".equals(self.status())) {
            position = 0;
            aheadBytes = 0;
        }
        long own = (long) (self.sourceSize() * (1 - (p == null ? 0 : p)));
        // Vérification par ffprobe et démarrage : quelques secondes de plus par remux.
        long seconds = Math.round((aheadBytes + own) / bytesPerMs / 1000) + 3L * (position + 1);
        int retry = (int) Math.max(2, Math.min(15, seconds / 4));
        return new Preparing(position, "RUNNING".equals(self.status()) ? (p == null ? 0.0 : p) : null, seconds, retry);
    }

    private OffsetDateTime requestedAt(long id) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT requested_at FROM remux_job WHERE media_file_id = ?")) {
            st.setLong(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getObject(1, OffsetDateTime.class) : OffsetDateTime.now();
            }
        }
    }

    /** Copie servie pour {@code /api/stream/{id}/remux} : seulement prête, même source, fichier présent. */
    public Optional<Path> served(long mediaFileId, long sourceSize, OffsetDateTime sourceModified) throws SQLException {
        Optional<Job> j = job(mediaFileId);
        String key = RemuxCache.key(mediaFileId, sourceSize, sourceModified);
        if (j.isEmpty() || !"READY".equals(j.get().status()) || !j.get().key().equals(key)) {
            return Optional.empty();
        }
        Optional<Path> p = cache.existing(key);
        if (p.isPresent()) {
            touch(mediaFileId, key);
        }
        return p;
    }

    /** Dernière lecture (au plus une écriture par minute et par copie) : protège la copie de la purge. */
    private void touch(long mediaFileId, String key) throws SQLException {
        Instant last = lastReadWrite.get(key);
        if (last != null && last.isAfter(Instant.now().minusSeconds(60))) {
            return;
        }
        lastReadWrite.put(key, Instant.now());
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("UPDATE remux_job SET last_read_at = now() WHERE media_file_id = ?")) {
            st.setLong(1, mediaFileId);
            st.executeUpdate();
        }
    }

    private void wakeAndYield() {
        remuxTest.yieldTo();
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    // --- Base ------------------------------------------------------------------------------------------------------

    Optional<Job> job(long mediaFileId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT media_file_id, status, priority, cache_key, source_size, source_modified, variant, last_read_at,
                            next_attempt_at, blocked, error, attempts FROM remux_job WHERE media_file_id = ?""")) {
            st.setLong(1, mediaFileId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                OffsetDateTime lr = rs.getObject(8, OffsetDateTime.class);
                OffsetDateTime na = rs.getObject(9, OffsetDateTime.class);
                return Optional.of(new Job(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getLong(5),
                        rs.getObject(6, OffsetDateTime.class), rs.getString(7), lr == null ? null : lr.toInstant(),
                        na == null ? null : na.toInstant(), rs.getString(10), rs.getString(11), rs.getInt(12)));
            }
        }
    }

    private void insert(long id, String key, long size, OffsetDateTime modified, int priority) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     INSERT INTO remux_job (media_file_id, status, priority, cache_key, source_size, source_modified)
                     VALUES (?, 'QUEUED', ?, ?, ?, ?) ON CONFLICT (media_file_id) DO NOTHING""")) {
            st.setLong(1, id);
            st.setInt(2, priority);
            st.setString(3, key);
            st.setLong(4, size);
            st.setObject(5, modified);
            st.executeUpdate();
        }
    }

    /** Remet en file ({@code resetAttempts} : « relancer » de l'admin). */
    void requeue(long id, int priority, boolean resetAttempts) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("UPDATE remux_job SET status = 'QUEUED', priority = LEAST(priority, ?),"
                     + " next_attempt_at = NULL, blocked = NULL" + (resetAttempts ? ", attempts = 0, error = NULL" : "")
                     + " WHERE media_file_id = ? AND status IN ('QUEUED', 'FAILED')")) {
            st.setInt(1, priority);
            st.setLong(2, id);
            st.executeUpdate();
        }
    }

    private void delete(long id) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement("DELETE FROM remux_job WHERE media_file_id = ?")) {
            st.setLong(1, id);
            st.executeUpdate();
        }
    }

    // --- Exécution ------------------------------------------------------------------------------------------------

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
                LOG.warnf(e, "Remux : erreur inattendue");
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Prochain remux : demandes des utilisateurs d'abord, puis préparations de l'admin, dans l'ordre d'arrivée. */
    Optional<Job> claim() throws SQLException {
        Long id = null;
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE remux_job SET status = 'RUNNING', started_at = now(), blocked = NULL
                     WHERE media_file_id = (SELECT media_file_id FROM remux_job WHERE status = 'QUEUED'
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

    /** Traite un remux tout de suite (tests ; en temps normal, le fil « remux »). */
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
        try {
            Optional<String[]> src = source(job.mediaFileId());
            Optional<Path> file = src.flatMap(s -> probe.resolve(s[0]));
            if (file.isEmpty()) {
                fail(job, "fichier source introuvable", true);
                return;
            }
            long needed = needed(job.sourceSize());
            switch (makeRoom(needed, job.key())) {
                case TOO_BIG -> {
                    fail(job, "fichier trop gros pour le cache de conversion (" + job.sourceSize() / MB + " Mo, cache de "
                            + maxBytes() / MB + " Mo)", true);
                    return;
                }
                case FULL -> {
                    block(job);
                    return;
                }
                case OK -> {
                }
            }
            Double sourceDuration = sourceDuration(job.mediaFileId(), file.get());
            String codec = src.get()[1];
            List<RemuxTestService.Variant> order = variants(job.mediaFileId(), codec);
            String lastError = null;
            for (RemuxTestService.Variant v : order) {
                String err = attempt(job, v, file.get(), sourceDuration);
                if (err == null) {
                    return;
                }
                lastError = (lastError == null ? "" : lastError + " ; ") + label(v) + " : " + err;
            }
            fail(job, lastError, false);
        } finally {
            progress.remove(job.mediaFileId());
            current = null;
        }
    }

    /** Place nécessaire : taille de la source + 5 % (en-têtes MKV, index) + 16 Mo au plus de marge. */
    static long needed(long sourceSize) {
        return sourceSize + sourceSize / 20 + Math.min(16 * MB, sourceSize);
    }

    enum Room { OK, FULL, TOO_BIG }

    /**
     * Fait de la place : copies prêtes les moins récemment lues d'abord, jamais une copie lue récemment
     * ({@code remuxInUse}) ni celle en cours. FULL : tout ce qui reste est en cours de lecture.
     */
    synchronized Room makeRoom(long needed, String exceptKey) throws SQLException {
        long max = maxBytes();
        long reserve = (long) (config.remuxCacheReserveGb() * 1e9);
        if (needed > max) {
            return Room.TOO_BIG;
        }
        List<Object[]> candidates = new ArrayList<>();
        long used;
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement st = c.prepareStatement("SELECT coalesce(sum(bytes), 0) FROM remux_job WHERE status = 'READY'");
                 ResultSet rs = st.executeQuery()) {
                rs.next();
                used = rs.getLong(1);
            }
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT media_file_id, cache_key, coalesce(bytes, 0) FROM remux_job
                    WHERE status = 'READY' AND cache_key <> ?
                      AND (last_read_at IS NULL OR last_read_at < now() - make_interval(secs => ?))
                    ORDER BY coalesce(last_read_at, finished_at) NULLS FIRST, media_file_id""")) {
                st.setString(1, exceptKey);
                st.setLong(2, config.remuxInUse().toSeconds());
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
            LOG.infof("Remux : copie %d effacée du cache (la moins récemment lue)", (Long) victim[0]);
        }
        return used + needed <= max && (free < 0 || free - reserve >= needed) ? Room.OK : Room.FULL;
    }

    /** Ordre des commandes : celle qui a réussi au test à blanc d'abord ; sinon la plus simple, puis l'autre. */
    List<RemuxTestService.Variant> variants(long mediaFileId, String videoCodec) throws SQLException {
        List<RemuxTestService.Variant> all = new ArrayList<>();
        for (RemuxTestService.Variant v : RemuxTestService.Variant.values()) {
            if (v.appliesTo(videoCodec == null ? "mpeg4" : videoCodec)) {
                all.add(v);
            }
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "SELECT variant FROM remux_test_result WHERE media_file_id = ? AND ok ORDER BY variant")) {
            st.setLong(1, mediaFileId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    RemuxTestService.Variant best = RemuxTestService.Variant.valueOf(rs.getString(1));
                    all.remove(best);
                    all.add(0, best);
                }
            }
        }
        return all;
    }

    /** Commande (sans shell) : copie de toutes les pistes en MKV, progression sur la sortie standard. */
    List<String> command(RemuxTestService.Variant v, Path input, Path output) {
        List<String> cmd = new ArrayList<>(ProcessRunner.lowPriorityPrefix(config.lowPriority()));
        cmd.addAll(List.of(config.ffmpegPath(), "-nostdin", "-hide_banner", "-v", "warning", "-progress", "pipe:1", "-nostats"));
        cmd.addAll(v.input);
        cmd.addAll(List.of("-i", "file:" + input, "-map", "0", "-c", "copy"));
        cmd.addAll(v.output);
        cmd.addAll(List.of("-f", "matroska", "-y", "file:" + output));
        return cmd;
    }

    /** Un essai : null si la copie est prête (vérifiée, publiée), sinon la raison de l'échec. */
    private String attempt(Job job, RemuxTestService.Variant v, Path input, Double sourceDuration)
            throws SQLException, InterruptedException {
        Path tmp;
        try {
            tmp = cache.prepareTemp(job.key());
        } catch (IOException e) {
            return "écriture impossible dans le cache";
        }
        long id = job.mediaFileId();
        ProcessRunner.Result r;
        try {
            r = ProcessRunner.run(command(v, input, tmp), config.remuxTimeout(), 64 * 1024, null, line -> {
                if (line.startsWith("out_time_us=") && sourceDuration != null && sourceDuration > 0) {
                    try {
                        double t = Long.parseLong(line.substring(12)) / 1e6;
                        progress.put(id, Math.max(0, Math.min(0.99, t / sourceDuration)));
                    } catch (NumberFormatException ignored) {
                        // « N/A » au début
                    }
                }
            });
        } catch (IOException e) {
            cache.delete(job.key());
            return "ffmpeg introuvable ou non exécutable";
        }
        if (!r.ok()) {
            cache.delete(job.key());
            String msg = probe.clean(r.stderrTail(), input);
            return r.timedOut() ? "délai dépassé (" + config.remuxTimeout().toMinutes() + " min)"
                    : "code " + r.exitCode() + (msg == null || msg.isBlank() ? "" : " : " + lastLine(msg));
        }
        String invalid = verify(tmp, sourceDuration);
        if (invalid != null) {
            cache.delete(job.key());
            return "copie incorrecte (" + invalid + ")";
        }
        long bytes;
        try {
            bytes = java.nio.file.Files.size(tmp);
            cache.publish(job.key());
        } catch (IOException e) {
            cache.delete(job.key());
            return "publication impossible dans le cache";
        }
        if (r.elapsedMs() > 1000) {
            bytesPerMs = 0.7 * bytesPerMs + 0.3 * ((double) job.sourceSize() / r.elapsedMs());
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE remux_job SET status = 'READY', variant = ?, bytes = ?, duration_seconds = ?, finished_at = now(),
                         error = ?, blocked = NULL, next_attempt_at = NULL WHERE media_file_id = ?""")) {
            st.setString(1, v.name());
            st.setLong(2, bytes);
            st.setObject(3, verifiedDuration, Types.NUMERIC);
            String warn = probe.clean(r.stderrTail(), input);
            st.setString(4, warn == null || warn.isBlank() ? null : "avertissement : " + lastLine(warn));
            st.setLong(5, id);
            st.executeUpdate();
        }
        LOG.infof("Remux : copie prête pour le fichier %d (%s, %d Mo, %d ms)", id, v.name(), bytes / MB, r.elapsedMs());
        return null;
    }

    private volatile java.math.BigDecimal verifiedDuration;

    /** Copie vérifiée avant d'être servie : vidéo et son présents, durée proche de l'original (1 %, 2 s au moins). */
    String verify(Path copy, Double sourceDuration) throws InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(config.ffprobePath(), "-v", "error", "-hide_banner", "-show_format",
                "-show_streams", "-of", "json", "-i", "file:" + copy));
        ProbeFacts f;
        try {
            ProcessRunner.Result r = ProcessRunner.run(cmd, config.probeTimeout(), 2 * 1024 * 1024, null);
            if (!r.ok()) {
                return "illisible par ffprobe";
            }
            f = ProbeFacts.parse(r.stdout());
        } catch (IOException | IllegalArgumentException e) {
            return "illisible par ffprobe";
        }
        if (f.video() == null) {
            return "pas de piste vidéo";
        }
        if (f.audio().isEmpty()) {
            return "pas de piste audio";
        }
        if (f.durationSeconds() == null || f.durationSeconds() <= 0) {
            return "durée inconnue";
        }
        if (sourceDuration != null && sourceDuration > 0
                && Math.abs(f.durationSeconds() - sourceDuration) > Math.max(2.0, sourceDuration * 0.01)) {
            return String.format(Locale.ROOT, "durée %.0f s au lieu de %.0f s", f.durationSeconds(), sourceDuration);
        }
        verifiedDuration = java.math.BigDecimal.valueOf(f.durationSeconds()).setScale(3, java.math.RoundingMode.HALF_UP);
        return null;
    }

    /** Durée de l'original : analyse 9.1, sinon ffprobe tout de suite (fichier pas encore analysé). */
    private Double sourceDuration(long id, Path file) throws SQLException, InterruptedException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT duration_seconds FROM media_probe WHERE media_file_id = ? AND status = 'OK'")) {
            st.setLong(1, id);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next() && rs.getBigDecimal(1) != null) {
                    return rs.getBigDecimal(1).doubleValue();
                }
            }
        }
        try {
            ProcessRunner.Result r = ProcessRunner.run(List.of(config.ffprobePath(), "-v", "error", "-hide_banner", "-show_format",
                    "-show_streams", "-of", "json", "-i", "file:" + file), config.probeTimeout(), 2 * 1024 * 1024, null);
            return r.ok() ? ProbeFacts.parse(r.stdout()).durationSeconds() : null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** {chemin relatif, codec vidéo analysé (ou null)} du fichier source, s'il est disponible. */
    private Optional<String[]> source(long id) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT f.relative_path, p.video_codec FROM media_file f LEFT JOIN media_probe p ON p.media_file_id = f.id
                     WHERE f.id = ? AND f.available""")) {
            st.setLong(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(new String[]{rs.getString(1), rs.getString(2)}) : Optional.empty();
            }
        }
    }

    /** Échec : nouvel essai automatique espacé (10 min, 20 min, 40 min… 24 h au plus), jamais en boucle. */
    private void fail(Job job, String reason, boolean permanent) throws SQLException {
        LOG.warnf("Remux impossible pour le fichier %d : %s", job.mediaFileId(), reason);
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE remux_job SET status = 'FAILED', attempts = attempts + 1, finished_at = now(), error = ?, blocked = NULL,
                         next_attempt_at = CASE WHEN ? THEN now() + interval '1 day'
                             ELSE now() + make_interval(mins => LEAST(1440, 10 * power(2, attempts)::int)) END
                     WHERE media_file_id = ?""")) {
            st.setString(1, reason == null ? "échec" : reason.length() > 600 ? reason.substring(0, 600) : reason);
            st.setBoolean(2, permanent);
            st.setLong(3, job.mediaFileId());
            st.executeUpdate();
        }
    }

    /** Cache plein (tout est en cours de lecture) : la demande reste en file, retentée dans une minute. */
    private void block(Job job) throws SQLException {
        LOG.infof("Remux : cache plein (copies en cours de lecture), fichier %d en attente", job.mediaFileId());
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE remux_job SET status = 'QUEUED', blocked = 'CACHE_FULL', started_at = NULL,
                         next_attempt_at = now() + interval '1 minute' WHERE media_file_id = ?""")) {
            st.setLong(1, job.mediaFileId());
            st.executeUpdate();
        }
    }

    static String label(RemuxTestService.Variant v) {
        return v == RemuxTestService.Variant.GENPTS ? "genpts" : "genpts + mpeg4_unpack_bframes";
    }

    private static String lastLine(String msg) {
        String[] lines = msg.strip().split("\\R");
        return lines[lines.length - 1];
    }

    // --- Admin -------------------------------------------------------------------------------------------------------

    public Double progressOf(long mediaFileId) {
        return progress.get(mediaFileId);
    }

    public Long currentJob() {
        return current;
    }

    public String ffmpegVersion() {
        return ffmpegVersion;
    }

    /** « Préparer à l'avance » (priorité basse) ; renvoie le nombre de fichiers mis en file. */
    public int prepare(List<long[]> files, List<OffsetDateTime> modified) throws SQLException {
        int n = 0;
        for (int i = 0; i < files.size(); i++) {
            long id = files.get(i)[0];
            long size = files.get(i)[1];
            String key = RemuxCache.key(id, size, modified.get(i));
            Optional<Job> j = job(id);
            if (j.isPresent() && !j.get().key().equals(key)) {
                cache.delete(j.get().key());
                delete(id);
                j = Optional.empty();
            }
            if (j.isEmpty()) {
                insert(id, key, size, modified.get(i), 1);
                n++;
            } else if ("FAILED".equals(j.get().status())) {
                requeue(id, 1, true);
                n++;
            }
        }
        synchronized (signal) {
            signal.notifyAll();
        }
        return n;
    }

    /** « Relancer » un remux en échec (compteur remis à zéro). */
    public boolean retry(long mediaFileId) throws SQLException {
        Optional<Job> j = job(mediaFileId);
        if (j.isEmpty() || !"FAILED".equals(j.get().status())) {
            return false;
        }
        requeue(mediaFileId, j.get().priority(), true);
        synchronized (signal) {
            signal.notifyAll();
        }
        return true;
    }

    /** « Vider le cache » : copies prêtes effacées, sauf celles en cours de lecture. Renvoie {effacées, gardées}. */
    public synchronized int[] clear() throws SQLException {
        int removed = 0;
        int kept = 0;
        List<Object[]> ready = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT media_file_id, cache_key, last_read_at IS NOT NULL AND last_read_at >= now() - make_interval(secs => ?)
                     FROM remux_job WHERE status = 'READY'""")) {
            st.setLong(1, config.remuxInUse().toSeconds());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    ready.add(new Object[]{rs.getLong(1), rs.getString(2), rs.getBoolean(3)});
                }
            }
        }
        for (Object[] r : ready) {
            if ((Boolean) r[2]) {
                kept++;
                continue;
            }
            cache.delete((String) r[1]);
            delete((Long) r[0]);
            removed++;
        }
        return new int[]{removed, kept};
    }
}
