package fr.plexwish.animeserver.media;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.library.LibraryConfig;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Analyse ffprobe des fichiers (ARCHITECTURE §22) : un fichier à la fois, seulement les nouveaux ou modifiés (taille,
 * date), état en base (reprise après arrêt), classification Android / navigateur, durée des épisodes.
 * Le chemin vient de la base et doit rester sous la racine des médias ; aucune entrée client.
 */
@ApplicationScoped
public class MediaProbeService {

    private static final Logger LOG = Logger.getLogger(MediaProbeService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Sortie JSON de ffprobe plafonnée (quelques Ko en temps normal). */
    private static final int MAX_JSON = 2 * 1024 * 1024;

    @Inject
    AgroalDataSource dataSource;
    @Inject
    MediaConfig config;
    @Inject
    LibraryConfig library;

    private volatile String ffprobeVersion;
    private volatile boolean versionChecked;

    /** Fichier à analyser (lu en base). */
    public record Work(long mediaFileId, String relativePath, long size, OffsetDateTime modified, String extension) {
    }

    // --- Outils ---------------------------------------------------------------------------------------------------

    /** « ffprobe version 7.1.5 … » (première ligne), null si ffprobe est absent. */
    public String ffprobeVersion() {
        if (!versionChecked) {
            ffprobeVersion = version(config.ffprobePath());
            versionChecked = true;
        }
        return ffprobeVersion;
    }

    public String version(String executable) {
        try {
            ProcessRunner.Result r = ProcessRunner.run(List.of(executable, "-version"), java.time.Duration.ofSeconds(10), 4096, null);
            return r.ok() ? r.stdout().lines().findFirst().map(l -> l.replaceAll(" Copyright.*", "")).orElse(null) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public boolean scanRunning() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT 1 FROM scan_run WHERE status = 'RUNNING'");
             ResultSet rs = st.executeQuery()) {
            return rs.next();
        }
    }

    /**
     * Fichier sur le disque, vérifié : sous la racine des médias (liens symboliques résolus), fichier ordinaire.
     * Jamais un chemin venu d'un client : {@code relative} est lu en base.
     */
    public Optional<Path> resolve(String relative) {
        try {
            Path root = Path.of(library.mediaRoot()).toAbsolutePath().normalize();
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                return Optional.empty();
            }
            Path real = file.toRealPath();
            if (!real.startsWith(root.toRealPath()) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            return Optional.of(real);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Message d'outil sans chemin du serveur (racine des médias et nom du fichier remplacés). */
    public String clean(String message, Path file) {
        if (message == null) {
            return null;
        }
        String m = message;
        if (file != null) {
            m = m.replace(file.toString(), "<fichier>");
        }
        try {
            m = m.replace(Path.of(library.mediaRoot()).toAbsolutePath().normalize().toString(), "<médias>");
        } catch (RuntimeException ignored) {
            // racine illisible : rien à remplacer
        }
        m = m.strip();
        return m.length() > 500 ? "…" + m.substring(m.length() - 500) : m;
    }

    // --- Analyse ------------------------------------------------------------------------------------------------------

    /** Prochain épisode disponible jamais analysé, ou modifié depuis (taille ou date). */
    public Optional<Work> nextPending() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT f.id, f.relative_path, f.file_size, f.last_modified, f.container
                     FROM media_file f LEFT JOIN media_probe p ON p.media_file_id = f.id
                     WHERE f.available AND f.kind = 'EPISODE'
                       AND (p.media_file_id IS NULL OR p.probed_size <> f.file_size
                            OR p.probed_modified IS DISTINCT FROM f.last_modified)
                     ORDER BY f.id LIMIT 1""");
             ResultSet rs = st.executeQuery()) {
            return rs.next() ? Optional.of(new Work(rs.getLong(1), rs.getString(2), rs.getLong(3),
                    rs.getObject(4, OffsetDateTime.class), rs.getString(5))) : Optional.empty();
        }
    }

    /** Analyse un fichier et enregistre le résultat (succès ou échec avec la raison). Renvoie « OK » ou « FAILED ». */
    public String probe(Work w) throws SQLException, InterruptedException {
        Optional<Path> file = resolve(w.relativePath());
        if (file.isEmpty()) {
            saveFailure(w, "fichier introuvable ou hors de la bibliothèque");
            return "FAILED";
        }
        List<String> cmd = new ArrayList<>(ProcessRunner.lowPriorityPrefix(config.lowPriority()));
        cmd.addAll(List.of(config.ffprobePath(), "-v", "error", "-hide_banner", "-show_format", "-show_streams",
                "-of", "json", "-i", "file:" + file.get()));
        ProcessRunner.Result r;
        try {
            r = ProcessRunner.run(cmd, config.probeTimeout(), MAX_JSON, null);
        } catch (IOException e) {
            saveFailure(w, "ffprobe introuvable ou non exécutable");
            return "FAILED";
        }
        if (r.timedOut()) {
            saveFailure(w, "délai dépassé (" + config.probeTimeout().toSeconds() + " s)");
            return "FAILED";
        }
        if (r.exitCode() != 0) {
            String why = clean(r.stderrTail(), file.get());
            saveFailure(w, "ffprobe a échoué (code " + r.exitCode() + ")" + (why == null || why.isEmpty() ? "" : " : " + why));
            return "FAILED";
        }
        ProbeFacts facts;
        try {
            facts = ProbeFacts.parse(r.stdout());
        } catch (IllegalArgumentException e) {
            saveFailure(w, e.getMessage());
            return "FAILED";
        }
        save(w, facts);
        return "OK";
    }

    void save(Work w, ProbeFacts f) throws SQLException {
        MediaRules.Classification cl = MediaRules.classify(f, w.extension());
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement st = c.prepareStatement("""
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
                        browser_reasons = EXCLUDED.browser_reasons, rules_version = EXCLUDED.rules_version""")) {
                ProbeFacts.Video v = f.video();
                int i = 1;
                st.setLong(i++, w.mediaFileId());
                st.setLong(i++, w.size());
                st.setObject(i++, w.modified());
                st.setObject(i++, f.durationSeconds() == null ? null : java.math.BigDecimal.valueOf(f.durationSeconds())
                        .setScale(3, java.math.RoundingMode.HALF_UP), Types.NUMERIC);
                st.setString(i++, f.formatName());
                st.setString(i++, MediaRules.container(f.formatName(), w.extension()));
                st.setString(i++, v == null ? null : v.codec());
                st.setString(i++, v == null ? null : v.profile());
                st.setObject(i++, v == null ? null : v.bitDepth(), Types.INTEGER);
                st.setObject(i++, v == null ? null : v.width(), Types.INTEGER);
                st.setObject(i++, v == null ? null : v.height(), Types.INTEGER);
                st.setString(i++, json(f.audio()));
                st.setString(i++, json(f.subtitles()));
                st.setString(i++, cl.android().name());
                st.setString(i++, String.join(" ; ", cl.androidReasons()));
                st.setBoolean(i++, cl.browserPlayable());
                st.setString(i++, String.join(" ; ", cl.browserReasons()));
                st.setInt(i, MediaRules.VERSION);
                st.executeUpdate();
            }
            syncDurations(c, w.mediaFileId());
            c.commit();
        }
    }

    void saveFailure(Work w, String error) throws SQLException {
        LOG.infof("Analyse média : échec pour le fichier %d (%s)", w.mediaFileId(), error);
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     INSERT INTO media_probe (media_file_id, probed_size, probed_modified, status, probed_at, error)
                     VALUES (?, ?, ?, 'FAILED', now(), ?)
                     ON CONFLICT (media_file_id) DO UPDATE SET probed_size = EXCLUDED.probed_size,
                         probed_modified = EXCLUDED.probed_modified, status = 'FAILED', probed_at = now(),
                         error = EXCLUDED.error, duration_seconds = NULL, android_class = NULL, browser_playable = NULL,
                         android_reasons = NULL, browser_reasons = NULL""")) {
            st.setLong(1, w.mediaFileId());
            st.setLong(2, w.size());
            st.setObject(3, w.modified());
            st.setString(4, error.length() > 600 ? error.substring(0, 600) : error);
            st.executeUpdate();
        }
    }

    /**
     * Durée des épisodes = durée analysée (arrondie à la seconde). {@code mediaFileId} null : tous les épisodes (après
     * un scan, un épisode peut avoir changé de fichier). Renvoie le nombre d'épisodes mis à jour.
     */
    public int syncDurations(Connection c, Long mediaFileId) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("""
                UPDATE episode e SET duration_seconds = round(p.duration_seconds)::int
                FROM media_probe p
                WHERE p.media_file_id = e.media_file_id AND p.status = 'OK' AND p.duration_seconds >= 1
                  AND e.duration_seconds IS DISTINCT FROM round(p.duration_seconds)::int
                  AND (?::bigint IS NULL OR e.media_file_id = ?)""")) {
            st.setObject(1, mediaFileId, Types.BIGINT);
            st.setObject(2, mediaFileId, Types.BIGINT);
            return st.executeUpdate();
        }
    }

    public int syncDurations() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return syncDurations(c, null);
        }
    }

    /** Fichiers classés avec d'anciennes règles : reclassés sans nouvelle analyse (par lots). */
    public int reclassifyOutdated(int batch) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT p.media_file_id, p.duration_seconds, p.format_name, p.video_codec, p.video_profile,
                            p.video_bit_depth, p.width, p.height, p.audio::text, p.subtitles::text, f.container
                     FROM media_probe p JOIN media_file f ON f.id = p.media_file_id
                     WHERE p.status = 'OK' AND p.rules_version < ? ORDER BY p.media_file_id LIMIT ?""")) {
            st.setInt(1, MediaRules.VERSION);
            st.setInt(2, batch);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Object[]{rs.getLong(1), facts(rs), rs.getString(11)});
                }
            }
        }
        for (Object[] r : rows) {
            MediaRules.Classification cl = MediaRules.classify((ProbeFacts) r[1], (String) r[2]);
            try (Connection c = dataSource.getConnection();
                 PreparedStatement st = c.prepareStatement("""
                         UPDATE media_probe SET android_class = ?, android_reasons = ?, browser_playable = ?,
                             browser_reasons = ?, rules_version = ? WHERE media_file_id = ?""")) {
                st.setString(1, cl.android().name());
                st.setString(2, String.join(" ; ", cl.androidReasons()));
                st.setBoolean(3, cl.browserPlayable());
                st.setString(4, String.join(" ; ", cl.browserReasons()));
                st.setInt(5, MediaRules.VERSION);
                st.setLong(6, (Long) r[0]);
                st.executeUpdate();
            }
        }
        return rows.size();
    }

    /** Faits enregistrés (colonnes 2 à 10 de la requête) → ProbeFacts. */
    static ProbeFacts facts(ResultSet rs) throws SQLException {
        String codec = rs.getString(4);
        ProbeFacts.Video v = codec == null ? null : new ProbeFacts.Video(codec, rs.getString(5),
                (Integer) rs.getObject(6), (Integer) rs.getObject(7), (Integer) rs.getObject(8), null);
        java.math.BigDecimal d = rs.getBigDecimal(2);
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

    /** Admin : refaire l'analyse d'un fichier (ou de tous les échecs) au prochain passage. */
    public int requeue(Long mediaFileId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(mediaFileId == null
                     ? "DELETE FROM media_probe WHERE status = 'FAILED'"
                     : "DELETE FROM media_probe WHERE media_file_id = ?")) {
            if (mediaFileId != null) {
                st.setLong(1, mediaFileId);
            }
            return st.executeUpdate();
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
