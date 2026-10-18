package fr.plexwish.animeserver.live;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.common.db.Db;
import fr.plexwish.animeserver.common.db.Jdbc;
import fr.plexwish.animeserver.media.ProbeFacts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Données de la lecture à la volée (docs/LIVE-REMUX.md) : fichier source, analyse, images clés ({@code media_keyframes}). */
@ApplicationScoped
public class LiveRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Fichier à lire : chemin relatif (null si indisponible), état sur le disque, et ce que l'analyse 9.1 en dit
     * ({@code videoCodec} null et {@code audio} vide sans analyse réussie).
     */
    public record Source(long mediaFileId, String relativePath, long fileSize, OffsetDateTime lastModified, String videoCodec,
                         List<ProbeFacts.Audio> audio, List<ProbeFacts.Subtitle> subtitles) {
    }

    /** Images clés enregistrées, avec l'état du fichier au moment du relevé. */
    public record StoredKeyframes(long probedSize, OffsetDateTime probedModified, int[] timesMs, int durationMs) {
    }

    @Inject
    Db db;

    public Optional<Source> source(long mediaFileId) throws SQLException {
        return db.one("""
                SELECT f.id, CASE WHEN f.available THEN f.relative_path END, f.file_size, f.last_modified,
                       CASE WHEN p.status = 'OK' THEN p.video_codec END, CASE WHEN p.status = 'OK' THEN p.audio::text END,
                       CASE WHEN p.status = 'OK' THEN p.subtitles::text END
                FROM media_file f LEFT JOIN media_probe p ON p.media_file_id = f.id
                WHERE f.id = ?""",
                rs -> new Source(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getObject(4, OffsetDateTime.class),
                        rs.getString(5), read(rs.getString(6), new TypeReference<List<ProbeFacts.Audio>>() {
                        }), read(rs.getString(7), new TypeReference<List<ProbeFacts.Subtitle>>() {
                        })),
                mediaFileId);
    }

    /**
     * Fichier de l'épisode suivant du même animé (saison puis numéro), s'il est disponible et à remuxer pour Android :
     * son index est relevé à l'avance pendant que l'utilisateur regarde celui-ci (docs/LIVE-REMUX.md §4).
     */
    public Optional<Long> nextRemuxFile(long mediaFileId) throws SQLException {
        return db.one("""
                SELECT f2.id
                FROM episode e JOIN season s ON s.id = e.season_id
                JOIN season s2 ON s2.anime_id = s.anime_id
                JOIN episode e2 ON e2.season_id = s2.id
                JOIN media_file f2 ON f2.id = e2.media_file_id AND f2.available
                JOIN media_probe p2 ON p2.media_file_id = f2.id AND p2.status = 'OK' AND p2.android_class = 'REMUX'
                WHERE e.media_file_id = ?
                  AND (s2.season_number > s.season_number
                       OR (s2.season_number = s.season_number AND e2.episode_number > e.episode_number))
                ORDER BY s2.season_number, e2.episode_number LIMIT 1""", rs -> rs.getLong(1), mediaFileId);
    }

    /** Utilisateur actif (false : désactivé ou supprimé depuis l'émission du lien). */
    public boolean userEnabled(long userId) throws SQLException {
        return db.one("SELECT enabled FROM app_user WHERE id = ?", rs -> rs.getBoolean(1), userId).orElse(false);
    }

    public Optional<StoredKeyframes> keyframes(long mediaFileId) throws SQLException {
        return db.one("SELECT probed_size, probed_modified, times_ms, duration_ms FROM media_keyframes WHERE media_file_id = ?",
                rs -> {
                    Array a = rs.getArray(3);
                    Integer[] boxed = (Integer[]) a.getArray();
                    int[] times = new int[boxed.length];
                    for (int i = 0; i < times.length; i++) {
                        times[i] = boxed[i];
                    }
                    return new StoredKeyframes(rs.getLong(1), rs.getObject(2, OffsetDateTime.class), times, rs.getInt(4));
                }, mediaFileId);
    }

    public void saveKeyframes(long mediaFileId, long size, OffsetDateTime modified, int[] timesMs, int durationMs)
            throws SQLException {
        db.runInTransaction(c -> {
            Integer[] boxed = new Integer[timesMs.length];
            for (int i = 0; i < boxed.length; i++) {
                boxed[i] = timesMs[i];
            }
            Array array = c.createArrayOf("integer", boxed);
            try {
                Jdbc.update(c, """
                        INSERT INTO media_keyframes (media_file_id, probed_size, probed_modified, times_ms, duration_ms, computed_at)
                        VALUES (?, ?, ?, ?, ?, now())
                        ON CONFLICT (media_file_id) DO UPDATE SET probed_size = EXCLUDED.probed_size,
                            probed_modified = EXCLUDED.probed_modified, times_ms = EXCLUDED.times_ms,
                            duration_ms = EXCLUDED.duration_ms, computed_at = now()""",
                        mediaFileId, size, new Jdbc.Typed(modified, java.sql.Types.TIMESTAMP_WITH_TIMEZONE), array, durationMs);
            } finally {
                array.free();
            }
        });
    }

    private static <T> List<T> read(String json, TypeReference<List<T>> type) {
        if (json == null) {
            return List.of();
        }
        try {
            return JSON.readValue(json, type);
        } catch (IOException e) {
            throw new IllegalStateException("analyse enregistrée illisible", e);
        }
    }
}
