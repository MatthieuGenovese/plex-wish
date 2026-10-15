package fr.plexwish.animeserver.webplay;

import fr.plexwish.animeserver.common.db.Db;
import fr.plexwish.animeserver.common.db.Jdbc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Préparations pour le lecteur web ({@code web_job}) : une par fichier et par sorte (BASE : sous-titres et copie HLS,
 * CONV : conversion). Le plan et le résultat ({@code manifest}) sont gardés en JSON, lu et écrit par le service.
 */
@ApplicationScoped
public class WebJobRepository {

    /** Préparation telle qu'enregistrée ({@code manifestJson} : plan ou résultat, JSON). */
    record JobRow(long mediaFileId, String kind, String status, String phase, int priority, String key, long sourceSize,
                  OffsetDateTime sourceModified, String manifestJson, Instant nextAttempt, String blocked, String error,
                  int attempts) {
    }

    /** Préparation qui passe avant une autre (en cours ou plus prioritaire). */
    record Ahead(long mediaFileId, String status, long sourceSize, String manifestJson) {
    }

    /** Préparation prête, candidate à l'effacement. */
    record ReadyCopy(long mediaFileId, String key, long bytes, String kind) {
    }

    /** Fichier source disponible : chemin relatif et extension. */
    record Source(String relativePath, String extension) {
    }

    private static final String COLUMNS = """
            media_file_id, kind, status, phase, priority, cache_key, source_size, source_modified, manifest,
            next_attempt_at, blocked, error, attempts""";

    @Inject
    Db db;

    /** Démarrage : les préparations interrompues repartent en file (sans le plan d'une préparation de base). */
    void requeueRunning() throws SQLException {
        db.update("""
                UPDATE web_job SET status = 'QUEUED', phase = NULL, started_at = NULL,
                    manifest = CASE WHEN kind = 'BASE' THEN NULL ELSE manifest END
                WHERE status = 'RUNNING'""");
    }

    /** Clés des préparations prêtes. */
    List<String> readyKeys() throws SQLException {
        return db.list("SELECT cache_key FROM web_job WHERE status = 'READY'", rs -> rs.getString(1));
    }

    /** Une préparation peut démarrer ({@code preventiveAllowed} : les préventives aussi, la nuit). */
    boolean hasRunnableQueued(boolean preventiveAllowed) throws SQLException {
        return db.one("SELECT 1 FROM web_job WHERE status = 'QUEUED' AND blocked IS NULL"
                + " AND (next_attempt_at IS NULL OR next_attempt_at <= now()) AND (priority < 2 OR ?) LIMIT 1",
                rs -> true, preventiveAllowed).isPresent();
    }

    /** Préparations de même sorte qui passent avant celle-ci. */
    List<Ahead> ahead(long mediaFileId, String kind) throws SQLException {
        return db.list("""
                SELECT j.media_file_id, j.status, j.source_size, j.manifest FROM web_job j, web_job me
                WHERE me.media_file_id = ? AND me.kind = ? AND j.kind = me.kind AND j.media_file_id <> me.media_file_id
                  AND j.status IN ('QUEUED', 'RUNNING') AND j.blocked IS NULL
                  AND (j.status = 'RUNNING' OR (j.next_attempt_at IS NULL OR j.next_attempt_at <= now()))
                  AND (j.status = 'RUNNING' OR j.priority < me.priority OR (j.priority = me.priority AND j.requested_at < me.requested_at)
                       OR (j.priority = me.priority AND j.requested_at = me.requested_at AND j.media_file_id < me.media_file_id))""",
                rs -> new Ahead(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4)), mediaFileId, kind);
    }

    void touchRead(String key) throws SQLException {
        db.update("UPDATE web_job SET last_read_at = now() WHERE cache_key = ?", key);
    }

    Optional<JobRow> job(long mediaFileId, String kind) throws SQLException {
        return db.one("SELECT " + COLUMNS + " FROM web_job WHERE media_file_id = ? AND kind = ?", WebJobRepository::row,
                mediaFileId, kind);
    }

    Optional<JobRow> jobByKey(String key) throws SQLException {
        return db.one("SELECT " + COLUMNS + " FROM web_job WHERE cache_key = ?", WebJobRepository::row, key);
    }

    void insert(long mediaFileId, String kind, String key, long size, OffsetDateTime modified, int priority, String planJson)
            throws SQLException {
        db.update("""
                INSERT INTO web_job (media_file_id, kind, status, priority, cache_key, source_size, source_modified, manifest)
                VALUES (?, ?, 'QUEUED', ?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING""",
                mediaFileId, kind, priority, key, size, modified, Jdbc.varchar(planJson));
    }

    void requeue(long mediaFileId, String kind, int priority, boolean resetAttempts) throws SQLException {
        db.update("UPDATE web_job SET status = 'QUEUED', priority = LEAST(priority, ?),"
                + " next_attempt_at = NULL, blocked = NULL" + (resetAttempts ? ", attempts = 0, error = NULL" : "")
                + " WHERE media_file_id = ? AND kind = ? AND status IN ('QUEUED', 'FAILED')", priority, mediaFileId, kind);
    }

    /** La priorité monte (en file comme en cours) ; l'attente faute de place reste. */
    void raise(long mediaFileId, String kind, int priority) throws SQLException {
        db.update("UPDATE web_job SET priority = LEAST(priority, ?), blocked = CASE"
                + " WHEN blocked = 'CACHE_FULL' THEN blocked ELSE NULL END WHERE media_file_id = ? AND kind = ?",
                priority, mediaFileId, kind);
    }

    void delete(long mediaFileId, String kind) throws SQLException {
        db.update("DELETE FROM web_job WHERE media_file_id = ? AND kind = ?", mediaFileId, kind);
    }

    void deleteByKey(String key) throws SQLException {
        db.update("DELETE FROM web_job WHERE cache_key = ?", key);
    }

    /** Étape en cours ; le plan n'est remplacé que s'il est donné. */
    void setPhase(long mediaFileId, String kind, String phase, String manifestJson) throws SQLException {
        db.update("UPDATE web_job SET phase = ?, manifest = coalesce(?::jsonb, manifest)"
                + " WHERE media_file_id = ? AND kind = ?", Jdbc.varchar(phase), Jdbc.varchar(manifestJson), mediaFileId, kind);
    }

    /** Prochaine préparation de cette sorte, passée RUNNING : la plus urgente, puis dans l'ordre d'arrivée. */
    Optional<Long> claimNext(String kind, boolean preventiveAllowed) throws SQLException {
        return db.one("""
                UPDATE web_job SET status = 'RUNNING', phase = 'PROBE', started_at = now(), blocked = NULL
                WHERE (media_file_id, kind) = (SELECT media_file_id, kind FROM web_job WHERE kind = ? AND status = 'QUEUED'
                    AND (next_attempt_at IS NULL OR next_attempt_at <= now()) AND (priority < 2 OR ?)
                    ORDER BY priority, requested_at, media_file_id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING media_file_id""", rs -> rs.getLong(1), kind, preventiveAllowed);
    }

    Optional<Integer> priority(long mediaFileId, String kind) throws SQLException {
        return db.one("SELECT priority FROM web_job WHERE media_file_id = ? AND kind = ?", rs -> rs.getInt(1), mediaFileId, kind);
    }

    /** Une préparation plus urgente attend et peut démarrer. */
    boolean urgentWaiting(String kind, int priority) throws SQLException {
        return db.one("SELECT 1 FROM web_job WHERE kind = ? AND status = 'QUEUED' AND priority < ?"
                + " AND blocked IS NULL AND (next_attempt_at IS NULL OR next_attempt_at <= now()) LIMIT 1",
                rs -> true, kind, priority).isPresent();
    }

    /** Arrêt voulu : repart en file. */
    void putBack(long mediaFileId, String kind) throws SQLException {
        db.update("UPDATE web_job SET status = 'QUEUED', phase = NULL, started_at = NULL"
                + " WHERE media_file_id = ? AND kind = ? AND status = 'RUNNING'", mediaFileId, kind);
    }

    void markReady(long mediaFileId, String kind, String manifestJson, long bytes, long elapsedMs) throws SQLException {
        db.update("""
                UPDATE web_job SET status = 'READY', phase = NULL, manifest = ?::jsonb, bytes = ?, finished_at = now(),
                    error = NULL, blocked = NULL, next_attempt_at = NULL, elapsed_ms = ? WHERE media_file_id = ? AND kind = ?""",
                manifestJson, bytes, elapsedMs, mediaFileId, kind);
    }

    /** Préparations prêtes effaçables (pas lues depuis {@code inUseSeconds}, sauf {@code exceptKey}), la moins récemment lue d'abord. */
    List<ReadyCopy> evictionCandidates(String exceptKey, long inUseSeconds) throws SQLException {
        return db.list("""
                SELECT media_file_id, cache_key, coalesce(bytes, 0), kind FROM web_job
                WHERE status = 'READY' AND cache_key <> ?
                  AND (last_read_at IS NULL OR last_read_at < now() - make_interval(secs => ?))
                ORDER BY coalesce(last_read_at, finished_at) NULLS FIRST, media_file_id""",
                rs -> new ReadyCopy(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4)), exceptKey, inUseSeconds);
    }

    Optional<Source> source(long mediaFileId) throws SQLException {
        return db.one("SELECT relative_path, container FROM media_file WHERE id = ? AND available",
                rs -> new Source(rs.getString(1), rs.getString(2)), mediaFileId);
    }

    /** Échec : nouvel essai espacé, {@code maxAttempts} au plus ; un échec définitif attend un jour. */
    void markFailed(long mediaFileId, String kind, String reason, boolean permanent, int maxAttempts) throws SQLException {
        db.update("""
                UPDATE web_job SET status = 'FAILED', phase = NULL, attempts = attempts + 1, finished_at = now(), error = ?,
                    blocked = NULL, next_attempt_at = CASE WHEN ? THEN now() + interval '1 day'
                        WHEN attempts + 1 >= ? THEN NULL
                        ELSE now() + make_interval(mins => LEAST(1440, 10 * power(2, attempts)::int)) END
                WHERE media_file_id = ? AND kind = ?""", reason, permanent, maxAttempts, mediaFileId, kind);
    }

    /** Cache plein : reste en file, retentée dans une minute. */
    void markBlocked(long mediaFileId, String kind) throws SQLException {
        db.update("""
                UPDATE web_job SET status = 'QUEUED', phase = NULL, blocked = 'CACHE_FULL', started_at = NULL,
                    next_attempt_at = now() + interval '1 minute' WHERE media_file_id = ? AND kind = ?""", mediaFileId, kind);
    }

    /** Relance par l'admin : essais remis à zéro, priorité « admin » au moins ; faux si rien à relancer. */
    boolean retry(long mediaFileId, String kind) throws SQLException {
        return db.update("""
                UPDATE web_job SET status = 'QUEUED', attempts = 0, error = NULL, next_attempt_at = NULL, blocked = NULL,
                    priority = LEAST(priority, 1), requested_at = now()
                WHERE media_file_id = ? AND kind = ? AND status IN ('FAILED', 'QUEUED')""", mediaFileId, kind) > 0;
    }

    /** Taille des préparations prêtes (octets). */
    long readyBytes() throws SQLException {
        return db.one("SELECT coalesce(sum(bytes), 0) FROM web_job WHERE status = 'READY'", rs -> rs.getLong(1)).orElseThrow();
    }

    // --- Nuit -------------------------------------------------------------------------------------------------------

    /** Fichier disponible d'un épisode : identifiant, taille, date. */
    record EpisodeFile(long mediaFileId, long size, OffsetDateTime modified) {
    }

    /** Préparations (hors travail en cours) dont la source a disparu ou changé (taille, date). */
    List<String> orphanKeys() throws SQLException {
        return db.list("""
                SELECT j.cache_key FROM web_job j LEFT JOIN media_file f ON f.id = j.media_file_id
                WHERE j.status <> 'RUNNING' AND (f.id IS NULL OR NOT f.available OR f.file_size <> j.source_size
                    OR f.last_modified IS DISTINCT FROM j.source_modified)""", rs -> rs.getString(1));
    }

    void deleteIfNotRunning(String key) throws SQLException {
        db.update("DELETE FROM web_job WHERE cache_key = ? AND status <> 'RUNNING'", key);
    }

    /**
     * Copies HLS sans conversion non lues depuis {@code keepSeconds}, sauf si une conversion prête du même fichier s'en
     * sert pour ses sous-titres.
     */
    List<String> expiredCopyKeys(long keepSeconds) throws SQLException {
        return db.list("""
                SELECT j.cache_key FROM web_job j
                WHERE j.kind = 'BASE' AND j.status = 'READY' AND (j.manifest ->> 'hls') = 'true'
                  AND coalesce(j.last_read_at, j.finished_at) < now() - make_interval(secs => ?)
                  AND NOT EXISTS (SELECT 1 FROM web_job v WHERE v.media_file_id = j.media_file_id AND v.kind = 'CONV'
                                  AND v.status = 'READY')""", rs -> rs.getString(1), keepSeconds);
    }

    void deleteIfReady(String key) throws SQLException {
        db.update("DELETE FROM web_job WHERE cache_key = ? AND status = 'READY'", key);
    }

    long readyCount() throws SQLException {
        return db.one("SELECT count(*) FROM web_job WHERE status = 'READY'", rs -> rs.getLong(1)).orElseThrow();
    }

    /** Dernier épisode regardé (30 derniers jours) par animé et par utilisateur. */
    List<Long> recentlyWatchedEpisodes() throws SQLException {
        return db.list("""
                SELECT DISTINCT ON (s.anime_id, p.user_id) p.episode_id
                FROM playback_progress p JOIN episode e ON e.id = p.episode_id JOIN season s ON s.id = e.season_id
                WHERE p.updated_at > now() - interval '30 days'
                ORDER BY s.anime_id, p.user_id, p.updated_at DESC""", rs -> rs.getLong(1));
    }

    /** Épisodes disponibles ajoutés ces 7 derniers jours, les plus récents d'abord. */
    List<Long> recentlyAddedEpisodes(int limit) throws SQLException {
        return db.list("""
                SELECT e.id FROM episode e JOIN media_file f ON f.id = e.media_file_id
                WHERE f.available AND f.first_seen_at > now() - interval '7 days'
                ORDER BY f.first_seen_at DESC, e.id LIMIT ?""", rs -> rs.getLong(1), limit);
    }

    Optional<EpisodeFile> availableFileOf(long episodeId) throws SQLException {
        return db.one("""
                SELECT f.id, f.file_size, f.last_modified FROM episode e JOIN media_file f ON f.id = e.media_file_id
                WHERE e.id = ? AND f.available""",
                rs -> new EpisodeFile(rs.getLong(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class)), episodeId);
    }

    // --- Administration -------------------------------------------------------------------------------------------------

    /** En file ; prêtes (base, conversion). */
    record Counts(long queued, long readyBase, long readyConv) {
    }

    /** Préparation et l'épisode qui la désigne (le premier lié au fichier ; champs null s'il n'y en a pas). */
    record ItemRow(String kind, long mediaFileId, String status, int priority, String phase, String error, int attempts,
                   Instant requestedAt, Instant nextAttemptAt, boolean blocked, Long episodeId, Long animeId, String animeTitle,
                   Integer seasonNumber, Integer episodeNumber) {
    }

    /** Épisode qui désigne un fichier (le premier lié). */
    record EpisodeLabel(long episodeId, long animeId, String animeTitle, int seasonNumber, int episodeNumber) {
    }

    private static final String ITEMS = """
            SELECT j.kind, j.media_file_id, j.status, j.priority, j.phase, j.error, j.attempts, j.requested_at, j.next_attempt_at,
                   j.blocked IS NOT NULL, e.id, a.id, a.title, s.season_number, e.episode_number
            FROM web_job j
            LEFT JOIN LATERAL (SELECT id, season_id, episode_number FROM episode WHERE media_file_id = j.media_file_id
                               ORDER BY id LIMIT 1) e ON true
            LEFT JOIN season s ON s.id = e.season_id LEFT JOIN anime a ON a.id = s.anime_id""";

    Counts counts() throws SQLException {
        return db.one("""
                SELECT count(*) FILTER (WHERE status = 'QUEUED'), count(*) FILTER (WHERE status = 'READY' AND kind = 'BASE'),
                       count(*) FILTER (WHERE status = 'READY' AND kind = 'CONV') FROM web_job""",
                rs -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3))).orElseThrow();
    }

    /** En cours puis en file, par priorité et ordre d'arrivée (30 au plus). */
    List<ItemRow> activeItems() throws SQLException {
        return db.list(ITEMS + " WHERE j.status IN ('QUEUED', 'RUNNING')"
                + " ORDER BY j.status DESC, j.priority, j.requested_at LIMIT 30", WebJobRepository::item);
    }

    /** Échecs, les plus récents d'abord (50 au plus). */
    List<ItemRow> failedItems() throws SQLException {
        return db.list(ITEMS + " WHERE j.status = 'FAILED' ORDER BY j.finished_at DESC LIMIT 50", WebJobRepository::item);
    }

    Optional<EpisodeLabel> label(long mediaFileId) throws SQLException {
        return db.one("""
                SELECT e.id, a.id, a.title, s.season_number, e.episode_number FROM episode e
                JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id
                WHERE e.media_file_id = ? ORDER BY e.id LIMIT 1""",
                rs -> new EpisodeLabel(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getInt(5)), mediaFileId);
    }

    /** Épisodes liés à un fichier de l'animé : saisons 1, 2… puis Spéciaux, par numéro. */
    List<Long> episodesOfAnime(long animeId) throws SQLException {
        return db.list("""
                SELECT e.id FROM episode e JOIN season s ON s.id = e.season_id WHERE s.anime_id = ? AND e.media_file_id IS NOT NULL
                ORDER BY s.season_number = 0, s.season_number, e.episode_number""", rs -> rs.getLong(1), animeId);
    }

    private static ItemRow item(ResultSet rs) throws SQLException {
        return new ItemRow(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6),
                rs.getInt(7), Jdbc.instant(rs, 8), Jdbc.instant(rs, 9), rs.getBoolean(10), (Long) rs.getObject(11),
                (Long) rs.getObject(12), rs.getString(13), (Integer) rs.getObject(14), (Integer) rs.getObject(15));
    }

    private static JobRow row(ResultSet rs) throws SQLException {
        return new JobRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6),
                rs.getLong(7), rs.getObject(8, OffsetDateTime.class), rs.getString(9), Jdbc.instant(rs, 10), rs.getString(11),
                rs.getString(12), rs.getInt(13));
    }
}
