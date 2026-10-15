package fr.plexwish.animeserver.media;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.common.db.Db;
import fr.plexwish.animeserver.common.db.Jdbc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Analyses ffprobe ({@code media_probe}) et durée des épisodes qui en découle. */
@ApplicationScoped
public class MediaProbeRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Analyse réussie à enregistrer : faits lus par ffprobe et classement par les règles. */
    public record ProbeResult(String container, ProbeFacts facts, MediaRules.Classification classification) {
    }

    /** Analyse enregistrée avec d'anciennes règles : faits relus et extension du fichier. */
    public record Outdated(long mediaFileId, ProbeFacts facts, String extension) {
    }

    @Inject
    Db db;

    public boolean scanRunning() throws SQLException {
        return db.one("SELECT 1 FROM scan_run WHERE status = 'RUNNING'", rs -> true).isPresent();
    }

    /** Prochain épisode disponible jamais analysé, ou modifié depuis (taille ou date). */
    public Optional<MediaProbeService.Work> nextPending() throws SQLException {
        return db.one("""
                SELECT f.id, f.relative_path, f.file_size, f.last_modified, f.container
                FROM media_file f LEFT JOIN media_probe p ON p.media_file_id = f.id
                WHERE f.available AND f.kind = 'EPISODE'
                  AND (p.media_file_id IS NULL OR p.probed_size <> f.file_size
                       OR p.probed_modified IS DISTINCT FROM f.last_modified)
                ORDER BY f.id LIMIT 1""",
                rs -> new MediaProbeService.Work(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getObject(4, OffsetDateTime.class), rs.getString(5)));
    }

    /** Enregistre une analyse réussie et met à jour la durée de l'épisode, dans une transaction. */
    public void saveSuccess(MediaProbeService.Work w, ProbeResult result) throws SQLException {
        ProbeFacts f = result.facts();
        MediaRules.Classification cl = result.classification();
        ProbeFacts.Video v = f.video();
        db.runInTransaction(c -> {
            Jdbc.update(c, """
                    INSERT INTO media_probe (media_file_id, probed_size, probed_modified, status, probed_at, error, duration_seconds,
                        format_name, container, video_codec, video_profile, video_bit_depth, width, height, audio, subtitles,
                        android_class, android_reasons, browser_playable, browser_reasons, rules_version)
                    VALUES (?, ?, ?, 'OK', now(), NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
                    ON CONFLICT (media_file_id) DO UPDATE SET probed_size = EXCLUDED.probed_size,
                        probed_modified = EXCLUDED.probed_modified, status = 'OK', probed_at = now(), error = NULL,
                        duration_seconds = EXCLUDED.duration_seconds, format_name = EXCLUDED.format_name,
                        container = EXCLUDED.container, video_codec = EXCLUDED.video_codec,
                        video_profile = EXCLUDED.video_profile, video_bit_depth = EXCLUDED.video_bit_depth,
                        width = EXCLUDED.width, height = EXCLUDED.height, audio = EXCLUDED.audio,
                        subtitles = EXCLUDED.subtitles, android_class = EXCLUDED.android_class,
                        android_reasons = EXCLUDED.android_reasons, browser_playable = EXCLUDED.browser_playable,
                        browser_reasons = EXCLUDED.browser_reasons, rules_version = EXCLUDED.rules_version""",
                    w.mediaFileId(),
                    w.size(),
                    w.modified(),
                    new Jdbc.Typed(f.durationSeconds() == null ? null
                            : BigDecimal.valueOf(f.durationSeconds()).setScale(3, RoundingMode.HALF_UP), Types.NUMERIC),
                    Jdbc.varchar(f.formatName()),
                    Jdbc.varchar(result.container()),
                    Jdbc.varchar(v == null ? null : v.codec()),
                    Jdbc.varchar(v == null ? null : v.profile()),
                    new Jdbc.Typed(v == null ? null : v.bitDepth(), Types.INTEGER),
                    new Jdbc.Typed(v == null ? null : v.width(), Types.INTEGER),
                    new Jdbc.Typed(v == null ? null : v.height(), Types.INTEGER),
                    json(f.audio()),
                    json(f.subtitles()),
                    cl.android().name(),
                    String.join(" ; ", cl.androidReasons()),
                    cl.browserPlayable(),
                    String.join(" ; ", cl.browserReasons()),
                    MediaRules.VERSION);
            syncDurations(c, w.mediaFileId());
        });
    }

    public void saveFailure(MediaProbeService.Work w, String error) throws SQLException {
        db.update("""
                INSERT INTO media_probe (media_file_id, probed_size, probed_modified, status, probed_at, error)
                VALUES (?, ?, ?, 'FAILED', now(), ?)
                ON CONFLICT (media_file_id) DO UPDATE SET probed_size = EXCLUDED.probed_size,
                    probed_modified = EXCLUDED.probed_modified, status = 'FAILED', probed_at = now(),
                    error = EXCLUDED.error, duration_seconds = NULL, android_class = NULL, browser_playable = NULL,
                    android_reasons = NULL, browser_reasons = NULL""",
                w.mediaFileId(), w.size(), w.modified(), error);
    }

    /**
     * Durée des épisodes = durée analysée (arrondie à la seconde). {@code mediaFileId} null : tous les épisodes.
     * Renvoie le nombre d'épisodes mis à jour.
     */
    int syncDurations(Connection c, Long mediaFileId) throws SQLException {
        return Jdbc.update(c, """
                UPDATE episode e SET duration_seconds = round(p.duration_seconds)::int
                FROM media_probe p
                WHERE p.media_file_id = e.media_file_id AND p.status = 'OK' AND p.duration_seconds >= 1
                  AND e.duration_seconds IS DISTINCT FROM round(p.duration_seconds)::int
                  AND (?::bigint IS NULL OR e.media_file_id = ?)""",
                new Jdbc.Typed(mediaFileId, Types.BIGINT), new Jdbc.Typed(mediaFileId, Types.BIGINT));
    }

    public int syncAllDurations() throws SQLException {
        return db.withConnection(c -> syncDurations(c, null));
    }

    /** Analyses réussies classées avec des règles plus anciennes que {@code rulesVersion}, au plus {@code limit}. */
    public List<Outdated> outdated(int rulesVersion, int limit) throws SQLException {
        return db.list("""
                SELECT p.media_file_id, p.duration_seconds, p.format_name, p.video_codec, p.video_profile,
                       p.video_bit_depth, p.width, p.height, p.audio::text, p.subtitles::text, f.container
                FROM media_probe p JOIN media_file f ON f.id = p.media_file_id
                WHERE p.status = 'OK' AND p.rules_version < ? ORDER BY p.media_file_id LIMIT ?""",
                rs -> new Outdated(rs.getLong(1), facts(rs), rs.getString(11)), rulesVersion, limit);
    }

    public void updateClassification(long mediaFileId, MediaRules.Classification cl, int rulesVersion) throws SQLException {
        db.update("""
                UPDATE media_probe SET android_class = ?, android_reasons = ?, browser_playable = ?,
                    browser_reasons = ?, rules_version = ? WHERE media_file_id = ?""",
                cl.android().name(), String.join(" ; ", cl.androidReasons()), cl.browserPlayable(),
                String.join(" ; ", cl.browserReasons()), rulesVersion, mediaFileId);
    }

    /** Oublie l'analyse d'un fichier ({@code mediaFileId}), ou tous les échecs (null). */
    public int delete(Long mediaFileId) throws SQLException {
        return mediaFileId == null
                ? db.update("DELETE FROM media_probe WHERE status = 'FAILED'")
                : db.update("DELETE FROM media_probe WHERE media_file_id = ?", mediaFileId);
    }

    // --- Administration ------------------------------------------------------------------------------------------

    /** Compteurs des épisodes disponibles : analyses et classement (les fichiers modifiés depuis comptent « en attente »). */
    public record ProbeCounts(long files, long pending, long analyzed, long failed, long browserPlayable,
                              long browserNotPlayable, long remuxBytes) {
    }

    public record DurationCounts(long episodes, long withDuration) {
    }

    /**
     * Fichier de la liste d'administration : analyse, épisode lié, résultats du test à blanc (JSON) et remux.
     * {@code height} vaut 0 si inconnue.
     */
    public record FileRow(long mediaFileId, String path, String animeTitle, Integer seasonNumber, Integer episodeNumber,
                          String extension, long fileSize, String status, String error, BigDecimal durationSeconds,
                          String videoCodec, String videoProfile, Integer videoBitDepth, Integer width, int height,
                          String audioJson, String subtitlesJson, String androidClass, String androidReasons,
                          Boolean browserPlayable, String browserReasons, String remuxTestsJson, Long animeId,
                          String remuxStatus, String remuxError) {
    }

    private static final String FILES = " FROM media_file f LEFT JOIN media_probe p ON p.media_file_id = f.id WHERE f.available AND f.kind = 'EPISODE'";
    private static final String PENDING = "(p.media_file_id IS NULL OR p.probed_size <> f.file_size OR p.probed_modified IS DISTINCT FROM f.last_modified)";

    public ProbeCounts probeCounts() throws SQLException {
        return db.one("SELECT count(*), count(*) FILTER (WHERE " + PENDING
                + "), count(*) FILTER (WHERE NOT " + PENDING + " AND p.status = 'OK'), count(*) FILTER (WHERE NOT "
                + PENDING + " AND p.status = 'FAILED'), count(*) FILTER (WHERE p.browser_playable AND NOT " + PENDING
                + "), count(*) FILTER (WHERE NOT p.browser_playable AND NOT " + PENDING
                + "), coalesce(sum(f.file_size) FILTER (WHERE p.android_class = 'REMUX'), 0)" + FILES,
                rs -> new ProbeCounts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6),
                        rs.getLong(7))).orElseThrow();
    }

    /** Nombre d'épisodes analysés (à jour) par classe Android. */
    public java.util.Map<String, Long> androidClassCounts() throws SQLException {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        db.list("SELECT p.android_class, count(*)" + FILES + " AND p.android_class IS NOT NULL AND NOT " + PENDING + " GROUP BY 1",
                rs -> out.put(rs.getString(1), rs.getLong(2)));
        return out;
    }

    public DurationCounts durationCounts() throws SQLException {
        return db.one("""
                SELECT count(*), count(*) FILTER (WHERE e.duration_seconds IS NOT NULL)
                FROM episode e JOIN media_file f ON f.id = e.media_file_id WHERE f.available""",
                rs -> new DurationCounts(rs.getLong(1), rs.getLong(2))).orElseThrow();
    }

    /**
     * Filtre de la liste : {@code pending}, {@code failed}, {@code browser-ko}, {@code browser-ok}, une classe Android,
     * ou null (tous). {@code pathLike} : motif LIKE (échappement « ! ») sur le chemin, sans accents ni casse.
     */
    private static String filesWhere(String filter) {
        return FILES + " AND (?::text IS NULL OR lower(unaccent(f.relative_path)) LIKE lower(unaccent(?)) ESCAPE '!')"
                + switch (filter == null ? "" : filter) {
                    case "pending" -> " AND " + PENDING;
                    case "failed" -> " AND NOT " + PENDING + " AND p.status = 'FAILED'";
                    case "browser-ko" -> " AND NOT " + PENDING + " AND p.browser_playable = FALSE";
                    case "browser-ok" -> " AND NOT " + PENDING + " AND p.browser_playable";
                    case "" -> "";
                    default -> " AND NOT " + PENDING + " AND p.android_class = '" + MediaRules.Android.valueOf(filter).name() + "'";
                };
    }

    public long countFiles(String filter, String pathLike) throws SQLException {
        return db.one("SELECT count(*)" + filesWhere(filter), rs -> rs.getLong(1), Jdbc.varchar(pathLike), Jdbc.varchar(pathLike))
                .orElseThrow();
    }

    public List<FileRow> files(String filter, String pathLike, int size, long offset) throws SQLException {
        return db.list("""
                SELECT f.id, f.relative_path, a.title, s.season_number, e.episode_number, f.container, f.file_size,
                       CASE WHEN """ + PENDING + """
                 THEN 'PENDING' ELSE p.status END, p.error, p.duration_seconds,
                       p.video_codec, p.video_profile, p.video_bit_depth, p.width, p.height, p.audio::text, p.subtitles::text,
                       p.android_class, p.android_reasons, p.browser_playable, p.browser_reasons,
                       (SELECT json_agg(json_build_object('variant', r.variant, 'ok', r.ok, 'exitCode', r.exit_code,
                               'timedOut', r.timed_out, 'elapsedMs', r.elapsed_ms, 'message', r.message) ORDER BY r.variant)
                        FROM remux_test_result r WHERE r.media_file_id = f.id)::text,
                       a.id, (SELECT j.status FROM remux_job j WHERE j.media_file_id = f.id),
                       (SELECT j.error FROM remux_job j WHERE j.media_file_id = f.id)
                """ + filesWhere(filter).replace(" FROM media_file f LEFT JOIN media_probe p ON p.media_file_id = f.id",
                " FROM media_file f LEFT JOIN media_probe p ON p.media_file_id = f.id LEFT JOIN episode e ON e.media_file_id = f.id"
                        + " LEFT JOIN season s ON s.id = e.season_id LEFT JOIN anime a ON a.id = s.anime_id")
                        + " ORDER BY f.relative_path LIMIT ? OFFSET ?",
                rs -> new FileRow(rs.getLong(1), rs.getString(2), rs.getString(3), (Integer) rs.getObject(4),
                        (Integer) rs.getObject(5), rs.getString(6), rs.getLong(7), rs.getString(8), rs.getString(9),
                        rs.getBigDecimal(10), rs.getString(11), rs.getString(12), (Integer) rs.getObject(13),
                        (Integer) rs.getObject(14), rs.getInt(15), rs.getString(16), rs.getString(17), rs.getString(18),
                        rs.getString(19), (Boolean) rs.getObject(20), rs.getString(21), rs.getString(22),
                        (Long) rs.getObject(23), rs.getString(24), rs.getString(25)),
                Jdbc.varchar(pathLike), Jdbc.varchar(pathLike), size, offset);
    }

    /** Faits enregistrés (colonnes 2 à 10 de la requête : durée, format, vidéo, audio et sous-titres JSON) → ProbeFacts. */
    static ProbeFacts facts(ResultSet rs) throws SQLException {
        String codec = rs.getString(4);
        ProbeFacts.Video v = codec == null ? null : new ProbeFacts.Video(codec, rs.getString(5),
                (Integer) rs.getObject(6), (Integer) rs.getObject(7), (Integer) rs.getObject(8), null);
        BigDecimal d = rs.getBigDecimal(2);
        try {
            List<ProbeFacts.Audio> audio = JSON.readValue(rs.getString(9), new TypeReference<>() {
            });
            List<ProbeFacts.Subtitle> subs = JSON.readValue(rs.getString(10), new TypeReference<>() {
            });
            return new ProbeFacts(d == null ? null : d.doubleValue(), rs.getString(3), v, audio, subs);
        } catch (IOException e) {
            throw new IllegalStateException("analyse enregistrée illisible", e);
        }
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
