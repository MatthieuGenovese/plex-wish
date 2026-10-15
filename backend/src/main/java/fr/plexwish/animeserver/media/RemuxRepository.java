package fr.plexwish.animeserver.media;

import fr.plexwish.animeserver.common.db.Db;
import fr.plexwish.animeserver.common.db.Jdbc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Remux à la demande ({@code remux_job}, file d'attente et copies du cache) et test à blanc
 * ({@code remux_test_result}).
 */
@ApplicationScoped
public class RemuxRepository {

    /** Copie du cache : fichier, clé, taille ; {@code inUse} : lue récemment. */
    record CachedCopy(long mediaFileId, String key, long bytes, boolean inUse) {
    }

    /** Remux en file ou en cours, pour estimer l'attente. */
    record Pending(long mediaFileId, String status, long sourceSize) {
    }

    /** Résultat d'analyse d'un fichier ({@code status} OK ou FAILED). */
    record ProbeClass(String status, String androidClass) {
    }

    /** Fichier source : chemin relatif et codec vidéo analysé (ou null). */
    record Source(String relativePath, String videoCodec) {
    }

    /** Fichier « remux nécessaire » à tester et commandes déjà testées. */
    record TestCandidate(long mediaFileId, String relativePath, long size, String videoCodec, List<String> variantsDone) {
    }

    record CountAndBytes(long count, long bytes) {
    }

    @Inject
    Db db;

    // --- File d'attente --------------------------------------------------------------------------------------------

    /** Démarrage : les remux interrompus repartent en file. */
    void requeueRunning() throws SQLException {
        db.update("UPDATE remux_job SET status = 'QUEUED', started_at = NULL WHERE status = 'RUNNING'");
    }

    /** Copies prêtes ; {@code inUseSeconds} : lue depuis moins longtemps = en cours de lecture. */
    List<CachedCopy> readyCopies(long inUseSeconds) throws SQLException {
        return db.list("""
                SELECT media_file_id, cache_key, coalesce(bytes, 0),
                       last_read_at IS NOT NULL AND last_read_at >= now() - make_interval(secs => ?)
                FROM remux_job WHERE status = 'READY'""",
                rs -> new CachedCopy(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getBoolean(4)), inUseSeconds);
    }

    /** Un remux attend (hors attente faute de place) et peut partir maintenant. */
    boolean hasRunnableQueued() throws SQLException {
        return db.one("SELECT 1 FROM remux_job WHERE status = 'QUEUED' AND blocked IS NULL"
                + " AND (next_attempt_at IS NULL OR next_attempt_at <= now()) LIMIT 1", rs -> true).isPresent();
    }

    Optional<ProbeClass> probeClass(long mediaFileId) throws SQLException {
        return db.one("SELECT status, android_class FROM media_probe WHERE media_file_id = ?",
                rs -> new ProbeClass(rs.getString(1), rs.getString(2)), mediaFileId);
    }

    /** Remux qui passent avant {@code self} (en cours, plus prioritaires ou demandés avant). */
    List<Pending> ahead(long mediaFileId, int priority, OffsetDateTime requestedAt) throws SQLException {
        return db.list("""
                SELECT media_file_id, status, source_size FROM remux_job
                WHERE status IN ('QUEUED', 'RUNNING') AND blocked IS NULL AND media_file_id <> ?
                  AND (status = 'RUNNING' OR (next_attempt_at IS NULL OR next_attempt_at <= now()))
                  AND (status = 'RUNNING' OR priority < ? OR (priority = ? AND requested_at < ?)
                       OR (priority = ? AND requested_at = ? AND media_file_id < ?))""",
                rs -> new Pending(rs.getLong(1), rs.getString(2), rs.getLong(3)),
                mediaFileId, priority, priority, requestedAt, priority, requestedAt, mediaFileId);
    }

    Optional<OffsetDateTime> requestedAt(long mediaFileId) throws SQLException {
        return db.one("SELECT requested_at FROM remux_job WHERE media_file_id = ?",
                rs -> rs.getObject(1, OffsetDateTime.class), mediaFileId);
    }

    void touchRead(long mediaFileId) throws SQLException {
        db.update("UPDATE remux_job SET last_read_at = now() WHERE media_file_id = ?", mediaFileId);
    }

    Optional<RemuxService.Job> job(long mediaFileId) throws SQLException {
        return db.one("""
                SELECT media_file_id, status, priority, cache_key, source_size, source_modified, variant, last_read_at,
                       next_attempt_at, blocked, error, attempts FROM remux_job WHERE media_file_id = ?""",
                rs -> new RemuxService.Job(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getLong(5),
                        rs.getObject(6, OffsetDateTime.class), rs.getString(7), Jdbc.instant(rs, 8), Jdbc.instant(rs, 9),
                        rs.getString(10), rs.getString(11), rs.getInt(12)),
                mediaFileId);
    }

    void insert(long mediaFileId, String key, long size, OffsetDateTime modified, int priority) throws SQLException {
        db.update("""
                INSERT INTO remux_job (media_file_id, status, priority, cache_key, source_size, source_modified)
                VALUES (?, 'QUEUED', ?, ?, ?, ?) ON CONFLICT (media_file_id) DO NOTHING""",
                mediaFileId, priority, key, size, modified);
    }

    /** Remet en file ({@code resetAttempts} : « relancer » de l'admin). */
    void requeue(long mediaFileId, int priority, boolean resetAttempts) throws SQLException {
        db.update("UPDATE remux_job SET status = 'QUEUED', priority = LEAST(priority, ?),"
                + " next_attempt_at = NULL, blocked = NULL" + (resetAttempts ? ", attempts = 0, error = NULL" : "")
                + " WHERE media_file_id = ? AND status IN ('QUEUED', 'FAILED')", priority, mediaFileId);
    }

    void delete(long mediaFileId) throws SQLException {
        db.update("DELETE FROM remux_job WHERE media_file_id = ?", mediaFileId);
    }

    /** Prend le prochain remux (demandes des utilisateurs d'abord, puis dans l'ordre d'arrivée) et le passe RUNNING. */
    Optional<Long> claimNext() throws SQLException {
        return db.one("""
                UPDATE remux_job SET status = 'RUNNING', started_at = now(), blocked = NULL
                WHERE media_file_id = (SELECT media_file_id FROM remux_job WHERE status = 'QUEUED'
                    AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                    ORDER BY priority, requested_at, media_file_id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING media_file_id""", rs -> rs.getLong(1));
    }

    // --- Cache -------------------------------------------------------------------------------------------------------

    long readyBytes() throws SQLException {
        return db.one("SELECT coalesce(sum(bytes), 0) FROM remux_job WHERE status = 'READY'", rs -> rs.getLong(1)).orElseThrow();
    }

    /** Copies effaçables (pas lues depuis {@code inUseSeconds}, sauf {@code exceptKey}), la moins récemment lue d'abord. */
    List<CachedCopy> evictionCandidates(String exceptKey, long inUseSeconds) throws SQLException {
        return db.list("""
                SELECT media_file_id, cache_key, coalesce(bytes, 0) FROM remux_job
                WHERE status = 'READY' AND cache_key <> ?
                  AND (last_read_at IS NULL OR last_read_at < now() - make_interval(secs => ?))
                ORDER BY coalesce(last_read_at, finished_at) NULLS FIRST, media_file_id""",
                rs -> new CachedCopy(rs.getLong(1), rs.getString(2), rs.getLong(3), false), exceptKey, inUseSeconds);
    }

    // --- Exécution -----------------------------------------------------------------------------------------------------

    /** Commande qui a réussi au test à blanc pour ce fichier (la première par nom), s'il y en a une. */
    Optional<String> successfulTestVariant(long mediaFileId) throws SQLException {
        return db.one("SELECT variant FROM remux_test_result WHERE media_file_id = ? AND ok ORDER BY variant",
                rs -> rs.getString(1), mediaFileId);
    }

    void markReady(long mediaFileId, String variant, long bytes, BigDecimal durationSeconds, String warning) throws SQLException {
        db.update("""
                UPDATE remux_job SET status = 'READY', variant = ?, bytes = ?, duration_seconds = ?, finished_at = now(),
                    error = ?, blocked = NULL, next_attempt_at = NULL WHERE media_file_id = ?""",
                variant, bytes, new Jdbc.Typed(durationSeconds, Types.NUMERIC), Jdbc.varchar(warning), mediaFileId);
    }

    /** Durée analysée (analyse réussie), s'il y en a une. */
    Optional<BigDecimal> probedDuration(long mediaFileId) throws SQLException {
        return db.one("SELECT duration_seconds FROM media_probe WHERE media_file_id = ? AND status = 'OK'",
                rs -> rs.getBigDecimal(1), mediaFileId);
    }

    /** Fichier source disponible. */
    Optional<Source> source(long mediaFileId) throws SQLException {
        return db.one("""
                SELECT f.relative_path, p.video_codec FROM media_file f LEFT JOIN media_probe p ON p.media_file_id = f.id
                WHERE f.id = ? AND f.available""", rs -> new Source(rs.getString(1), rs.getString(2)), mediaFileId);
    }

    /** Échec : nouvel essai espacé (10 min, 20 min, 40 min… 24 h au plus), ou dans un jour si {@code permanent}. */
    void markFailed(long mediaFileId, String reason, boolean permanent) throws SQLException {
        db.update("""
                UPDATE remux_job SET status = 'FAILED', attempts = attempts + 1, finished_at = now(), error = ?, blocked = NULL,
                    next_attempt_at = CASE WHEN ? THEN now() + interval '1 day'
                        ELSE now() + make_interval(mins => LEAST(1440, 10 * power(2, attempts)::int)) END
                WHERE media_file_id = ?""", reason, permanent, mediaFileId);
    }

    /** Cache plein : reste en file, retenté dans une minute. */
    void markBlocked(long mediaFileId) throws SQLException {
        db.update("""
                UPDATE remux_job SET status = 'QUEUED', blocked = 'CACHE_FULL', started_at = NULL,
                    next_attempt_at = now() + interval '1 minute' WHERE media_file_id = ?""", mediaFileId);
    }

    // --- Test à blanc ------------------------------------------------------------------------------------------------

    int deleteTestResults() throws SQLException {
        return db.update("DELETE FROM remux_test_result");
    }

    /** Prochain fichier « remux nécessaire » qui n'a pas de résultat pour toutes les commandes applicables. */
    Optional<TestCandidate> nextTestCandidate() throws SQLException {
        return db.one("""
                SELECT f.id, f.relative_path, f.file_size, p.video_codec,
                       ARRAY(SELECT r.variant FROM remux_test_result r WHERE r.media_file_id = f.id)
                FROM media_probe p JOIN media_file f ON f.id = p.media_file_id
                WHERE f.available AND p.android_class = 'REMUX'
                  AND (SELECT count(*) FROM remux_test_result r WHERE r.media_file_id = f.id)
                      < CASE WHEN p.video_codec = 'mpeg4' THEN 2 ELSE 1 END
                ORDER BY f.id LIMIT 1""",
                rs -> new TestCandidate(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        List.of((String[]) rs.getArray(5).getArray())));
    }

    void saveTestResult(long mediaFileId, String variant, boolean ok, Integer exitCode, boolean timedOut, long elapsedMs,
                        long bytes, String message) throws SQLException {
        db.update("""
                INSERT INTO remux_test_result (media_file_id, variant, ok, exit_code, timed_out, elapsed_ms, bytes, message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (media_file_id, variant) DO UPDATE SET ok = EXCLUDED.ok, exit_code = EXCLUDED.exit_code,
                    timed_out = EXCLUDED.timed_out, elapsed_ms = EXCLUDED.elapsed_ms, bytes = EXCLUDED.bytes,
                    message = EXCLUDED.message, tested_at = now()""",
                mediaFileId, variant, ok, new Jdbc.Typed(exitCode, Types.INTEGER), timedOut, elapsedMs, bytes,
                Jdbc.varchar(message));
    }

    /** Fichiers « remux nécessaire » disponibles. */
    CountAndBytes remuxNeeded() throws SQLException {
        return db.one("""
                SELECT count(*), coalesce(sum(f.file_size), 0) FROM media_probe p JOIN media_file f ON f.id = p.media_file_id
                WHERE f.available AND p.android_class = 'REMUX'""",
                rs -> new CountAndBytes(rs.getLong(1), rs.getLong(2))).orElseThrow();
    }

    /** Fichiers déjà testés (avec la commande de base). */
    CountAndBytes tested() throws SQLException {
        return db.one("""
                SELECT count(*), coalesce(sum(f.file_size), 0) FROM media_file f
                WHERE EXISTS (SELECT 1 FROM remux_test_result r WHERE r.media_file_id = f.id AND r.variant = 'GENPTS')""",
                rs -> new CountAndBytes(rs.getLong(1), rs.getLong(2))).orElseThrow();
    }

    /** Par commande : {réussites, échecs}. */
    Map<String, long[]> testResultsByVariant() throws SQLException {
        Map<String, long[]> out = new java.util.LinkedHashMap<>();
        db.list("SELECT variant, count(*) FILTER (WHERE ok), count(*) FILTER (WHERE NOT ok) FROM remux_test_result GROUP BY 1",
                rs -> out.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3)}));
        return out;
    }

    // --- Administration ----------------------------------------------------------------------------------------------

    /** Copies prêtes (octets et nombre), remux en file ou en cours, échecs. */
    record JobTotals(long readyBytes, long ready, long queued, long failed) {
    }

    record QueueRow(long mediaFileId, String path, String status, int priority, String blocked, OffsetDateTime requestedAt) {
    }

    record JobRow(long mediaFileId, String path, String status, String variant, Long bytes, java.time.Instant requestedAt,
                  java.time.Instant finishedAt, java.time.Instant lastReadAt, int attempts, java.time.Instant nextAttemptAt,
                  String error) {
    }

    /** Épisode disponible d'un animé sans copie prête ni remux en file, et son analyse. */
    record PrepareCandidate(long mediaFileId, long size, OffsetDateTime modified, String extension, String probeStatus,
                            String androidClass) {
    }

    JobTotals totals() throws SQLException {
        return db.one("""
                SELECT coalesce(sum(bytes) FILTER (WHERE status = 'READY'), 0), count(*) FILTER (WHERE status = 'READY'),
                       count(*) FILTER (WHERE status IN ('QUEUED', 'RUNNING')), count(*) FILTER (WHERE status = 'FAILED')
                FROM remux_job""", rs -> new JobTotals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4))).orElseThrow();
    }

    /** File d'attente : en cours d'abord, puis par priorité et ordre d'arrivée (100 au plus). */
    List<QueueRow> queue() throws SQLException {
        return db.list("""
                SELECT j.media_file_id, f.relative_path, j.status, j.priority, j.blocked, j.requested_at
                FROM remux_job j JOIN media_file f ON f.id = j.media_file_id WHERE j.status IN ('QUEUED', 'RUNNING')
                ORDER BY j.status = 'RUNNING' DESC, j.priority, j.requested_at, j.media_file_id LIMIT 100""",
                rs -> new QueueRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
                        rs.getObject(6, OffsetDateTime.class)));
    }

    /** Remux d'un statut (tous si null), le plus récent d'abord (500 au plus). */
    List<JobRow> jobs(String status) throws SQLException {
        return db.list("""
                SELECT j.media_file_id, f.relative_path, j.status, j.variant, j.bytes, j.requested_at, j.finished_at,
                       j.last_read_at, j.attempts, j.next_attempt_at, j.error
                FROM remux_job j JOIN media_file f ON f.id = j.media_file_id
                WHERE (?::text IS NULL OR j.status = ?) ORDER BY coalesce(j.finished_at, j.requested_at) DESC LIMIT 500""",
                rs -> new JobRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), (Long) rs.getObject(5),
                        Jdbc.instant(rs, 6), Jdbc.instant(rs, 7), Jdbc.instant(rs, 8), rs.getInt(9), Jdbc.instant(rs, 10),
                        rs.getString(11)),
                Jdbc.varchar(status), Jdbc.varchar(status));
    }

    /** Épisodes de l'animé à préparer éventuellement, dans l'ordre des saisons et des épisodes. */
    List<PrepareCandidate> prepareCandidates(long animeId) throws SQLException {
        return db.list("""
                SELECT f.id, f.file_size, f.last_modified, f.container, p.status, p.android_class
                FROM episode e JOIN season s ON s.id = e.season_id JOIN media_file f ON f.id = e.media_file_id AND f.available
                LEFT JOIN media_probe p ON p.media_file_id = f.id
                LEFT JOIN remux_job j ON j.media_file_id = f.id AND j.status IN ('READY', 'QUEUED', 'RUNNING')
                WHERE s.anime_id = ? AND j.media_file_id IS NULL ORDER BY s.season_number, e.episode_number""",
                rs -> new PrepareCandidate(rs.getLong(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class), rs.getString(4),
                        rs.getString(5), rs.getString(6)),
                animeId);
    }
}
