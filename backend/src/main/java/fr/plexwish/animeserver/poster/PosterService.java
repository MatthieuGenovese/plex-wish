package fr.plexwish.animeserver.poster;

import fr.plexwish.animeserver.metadata.MetadataProvider.ProviderUnavailableException;
import fr.plexwish.animeserver.poster.PosterDownloader.Image;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Affiches locales (ARCHITECTURE §17). Source voulue pour un animé : l'affiche TMDB si la fiche TMDB a moins de
 * 6 mois, sinon celle d'AniList. Un animé à la fois, état en base (idempotent, reprenable) : on ne télécharge que
 * ce qui manque ou a changé, et une image déjà présente pour la même source est réutilisée sans appel réseau.
 * Affiches TMDB : retéléchargées après 5 mois, effacées à 6 (conditions de l'API TMDB).
 */
@ApplicationScoped
public class PosterService {

    private static final Logger LOG = Logger.getLogger(PosterService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int MAX_ATTEMPTS = 5;

    public sealed interface Step permits Done, Idle, Unavailable {
    }

    public record Done(long animeId, String what) implements Step {
    }

    public record Idle() implements Step {
    }

    public record Unavailable(String message, Duration retryAfter) implements Step {
    }

    /** URL d'affiche pour l'API : locale (/api/posters/…) si le fichier est là, sinon distante, sinon null. */
    public record Urls(String small, String large, boolean local) {
    }

    @Inject
    AgroalDataSource dataSource;
    @Inject
    PosterDownloader downloader;
    @Inject
    PosterStore store;
    @Inject
    PosterConfig config;

    private volatile String lastUnavailable;
    private volatile Instant pausedUntil;

    public Optional<String> lastUnavailable() {
        return Optional.ofNullable(lastUnavailable);
    }

    public Optional<Instant> pausedUntil() {
        Instant p = pausedUntil;
        return p != null && p.isAfter(Instant.now()) ? Optional.of(p) : Optional.empty();
    }

    /** Source voulue par animé (fournisseur, URL) : TMDB récent d'abord, sinon AniList. Paramètres : base TMDB, âge max. */
    private static final String DESIRED = """
            SELECT a.id,
                   CASE WHEN t.poster_path IS NOT NULL AND t.fetched_at > now() - make_interval(secs => ?) THEN 'TMDB'
                        WHEN coalesce(a.poster_large_url, a.poster_url) IS NOT NULL THEN 'ANILIST' END AS provider,
                   CASE WHEN t.poster_path IS NOT NULL AND t.fetched_at > now() - make_interval(secs => ?) THEN ? || t.poster_path
                        ELSE coalesce(a.poster_large_url, a.poster_url) END AS src
            FROM anime a LEFT JOIN anime_tmdb t ON t.anime_id = a.id""";

    private void bindDesired(PreparedStatement st) throws SQLException {
        st.setLong(1, config.tmdbMaxAge().toSeconds());
        st.setLong(2, config.tmdbMaxAge().toSeconds());
        st.setString(3, config.tmdbImageBase().replaceAll("/+$", ""));
    }

    // --- Tâche de fond -------------------------------------------------------------------------------

    public Step processNext() throws SQLException {
        expireTmdb();
        record Next(long animeId, String provider, String src) {
        }
        Next next = null;
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("WITH d AS (" + DESIRED + """
                     )
                     SELECT d.id, d.provider, d.src FROM d LEFT JOIN anime_poster p ON p.anime_id = d.id
                     WHERE d.src IS NOT NULL AND d.src IS DISTINCT FROM p.failed_source
                       AND (p.next_attempt_at IS NULL OR p.next_attempt_at <= now())
                       AND (p.anime_id IS NULL OR p.status = 'PENDING' OR p.source_url <> d.src
                            OR (p.provider = 'TMDB' AND p.fetched_at < now() - make_interval(secs => ?)))
                     ORDER BY p.anime_id NULLS FIRST, d.id LIMIT 1""")) {
            bindDesired(st);
            st.setLong(4, config.tmdbRefreshAfter().toSeconds());
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    next = new Next(rs.getLong(1), rs.getString(2), rs.getString(3));
                }
            }
        }
        if (next == null) {
            return new Idle();
        }
        try {
            String what = fetch(next.animeId(), next.provider(), next.src());
            lastUnavailable = null;
            return new Done(next.animeId(), what);
        } catch (ProviderUnavailableException e) {
            Duration wait = e.retryAfter().orElse(config.unavailablePause());
            lastUnavailable = e.getMessage();
            pausedUntil = Instant.now().plus(wait);
            LOG.infof("Affiches : %s, nouvel essai dans %d s", e.getMessage(), wait.toSeconds());
            return new Unavailable(e.getMessage(), wait);
        } catch (PosterDownloader.Rejected e) {
            LOG.infof("Affiches : affiche de l'animé %d refusée (%s)", next.animeId(), e.getMessage());
            failed(next.animeId(), next.provider(), next.src(), e.getMessage(), true);
            return new Done(next.animeId(), "REJECTED");
        } catch (PosterDownloader.Retry e) {
            failed(next.animeId(), next.provider(), next.src(), e.getMessage(), false);
            return new Done(next.animeId(), "RETRY");
        } catch (RuntimeException e) {
            LOG.warnf(e, "Affiches : échec pour l'animé %d", next.animeId());
            failed(next.animeId(), next.provider(), next.src(), e.toString(), false);
            return new Done(next.animeId(), "ERROR");
        }
    }

    /** Une image déjà présente pour la même source est réutilisée (pas de nouvel appel), sinon téléchargement. */
    String fetch(long animeId, String provider, String src)
            throws SQLException, ProviderUnavailableException, PosterDownloader.Rejected, PosterDownloader.Retry {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT relative_path, sha256, content_type, bytes, fetched_at FROM anime_poster
                     WHERE source_url = ? AND status = 'OK' AND anime_id <> ? AND relative_path IS NOT NULL
                       AND (provider <> 'TMDB' OR fetched_at > now() - make_interval(secs => ?))
                     ORDER BY fetched_at DESC LIMIT 1""")) {
            st.setString(1, src);
            st.setLong(2, animeId);
            st.setLong(3, config.tmdbRefreshAfter().toSeconds());
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next() && store.existing(rs.getString(1)).isPresent()) {
                    save(animeId, provider, src, rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getObject(5, OffsetDateTime.class));
                    return "REUSED";
                }
            }
        }
        Image image = downloader.download(src);
        String relative = PosterStore.relativePath(image.sha256(), image.extension());
        store.write(relative, image.bytes());
        save(animeId, provider, src, relative, image.sha256(), image.contentType(), image.bytes().length,
                OffsetDateTime.now(ZoneOffset.UTC));
        return "DOWNLOADED";
    }

    private void save(long animeId, String provider, String src, String relative, String sha, String type, long bytes,
                      OffsetDateTime fetchedAt) throws SQLException {
        String oldPath = null;
        String publicId = null;
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement st = c.prepareStatement("SELECT relative_path, sha256, public_id FROM anime_poster WHERE anime_id = ?")) {
                st.setLong(1, animeId);
                try (ResultSet rs = st.executeQuery()) {
                    if (rs.next()) {
                        oldPath = rs.getString(1);
                        // Même image : même URL (cache navigateur conservé) ; image différente : nouvelle URL.
                        publicId = sha.equals(rs.getString(2)) ? rs.getString(3) : null;
                    }
                }
            }
            try (PreparedStatement st = c.prepareStatement("""
                    INSERT INTO anime_poster (anime_id, status, provider, source_url, public_id, relative_path, sha256,
                                              content_type, bytes, fetched_at, failed_source, attempts, next_attempt_at,
                                              last_error, updated_at)
                    VALUES (?, 'OK', ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0, NULL, NULL, now())
                    ON CONFLICT (anime_id) DO UPDATE SET status = 'OK', provider = EXCLUDED.provider,
                        source_url = EXCLUDED.source_url, public_id = EXCLUDED.public_id,
                        relative_path = EXCLUDED.relative_path, sha256 = EXCLUDED.sha256,
                        content_type = EXCLUDED.content_type, bytes = EXCLUDED.bytes, fetched_at = EXCLUDED.fetched_at,
                        failed_source = NULL, attempts = 0, next_attempt_at = NULL, last_error = NULL, updated_at = now()""")) {
                st.setLong(1, animeId);
                st.setString(2, provider);
                st.setString(3, src);
                st.setString(4, publicId != null ? publicId : newPublicId());
                st.setString(5, relative);
                st.setString(6, sha);
                st.setString(7, type);
                st.setLong(8, bytes);
                st.setObject(9, fetchedAt);
                st.executeUpdate();
            }
        }
        if (oldPath != null && !oldPath.equals(relative)) {
            deleteIfUnreferenced(List.of(oldPath));
        }
    }

    /**
     * Échec : refus définitif, ou échecs passagers répétés ({@value #MAX_ATTEMPTS}) → cette source n'est plus
     * retentée tant qu'elle ne change pas. Une affiche locale déjà présente reste servie.
     */
    private void failed(long animeId, String provider, String src, String error, boolean permanent) throws SQLException {
        String message = error.length() > 300 ? error.substring(0, 300) : error;
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     INSERT INTO anime_poster (anime_id, status, provider, source_url, failed_source, attempts, next_attempt_at,
                                               last_error, updated_at)
                     VALUES (?, CASE WHEN ? THEN 'FAILED' ELSE 'PENDING' END, ?, ?, CASE WHEN ? THEN ? END, 1,
                             CASE WHEN ? THEN NULL ELSE now() + interval '2 minutes' END, ?, now())
                     ON CONFLICT (anime_id) DO UPDATE SET
                         attempts = anime_poster.attempts + 1,
                         failed_source = CASE WHEN ? OR anime_poster.attempts + 1 >= ? THEN EXCLUDED.source_url END,
                         status = CASE WHEN anime_poster.relative_path IS NOT NULL THEN 'OK'
                                       WHEN ? OR anime_poster.attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
                         next_attempt_at = CASE WHEN ? OR anime_poster.attempts + 1 >= ? THEN NULL
                             ELSE now() + make_interval(mins => power(2, anime_poster.attempts + 1)::int) END,
                         provider = CASE WHEN anime_poster.relative_path IS NULL THEN EXCLUDED.provider ELSE anime_poster.provider END,
                         source_url = CASE WHEN anime_poster.relative_path IS NULL THEN EXCLUDED.source_url ELSE anime_poster.source_url END,
                         last_error = EXCLUDED.last_error, updated_at = now()""")) {
            int i = 1;
            st.setLong(i++, animeId);
            st.setBoolean(i++, permanent);
            st.setString(i++, provider);
            st.setString(i++, src);
            st.setBoolean(i++, permanent);
            st.setString(i++, src);
            st.setBoolean(i++, permanent);
            st.setString(i++, message);
            st.setBoolean(i++, permanent);
            st.setInt(i++, MAX_ATTEMPTS);
            st.setBoolean(i++, permanent);
            st.setInt(i++, MAX_ATTEMPTS);
            st.setBoolean(i++, permanent);
            st.setInt(i, MAX_ATTEMPTS);
            st.executeUpdate();
        }
    }

    /** Conditions TMDB : affiche TMDB de plus de 6 mois, ou dont la fiche TMDB a disparu → effacée (fichier compris). */
    int expireTmdb() throws SQLException {
        List<String> paths = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     DELETE FROM anime_poster p WHERE p.provider = 'TMDB'
                       AND (p.fetched_at < now() - make_interval(secs => ?)
                            OR NOT EXISTS (SELECT 1 FROM anime_tmdb t WHERE t.anime_id = p.anime_id AND t.poster_path IS NOT NULL
                                           AND t.fetched_at > now() - make_interval(secs => ?)))
                     RETURNING p.relative_path""")) {
            st.setLong(1, config.tmdbMaxAge().toSeconds());
            st.setLong(2, config.tmdbMaxAge().toSeconds());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    if (rs.getString(1) != null) {
                        paths.add(rs.getString(1));
                    }
                }
            }
        }
        deleteIfUnreferenced(paths);
        return paths.size();
    }

    /** Fin de licence TMDB : toutes les affiches TMDB effacées, fichiers compris. */
    public int purgeTmdb() throws SQLException {
        List<String> paths = new ArrayList<>();
        int n;
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("DELETE FROM anime_poster WHERE provider = 'TMDB' RETURNING relative_path")) {
            n = 0;
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    n++;
                    if (rs.getString(1) != null) {
                        paths.add(rs.getString(1));
                    }
                }
            }
        }
        deleteIfUnreferenced(paths);
        return n;
    }

    private void deleteIfUnreferenced(Collection<String> paths) throws SQLException {
        if (paths.isEmpty()) {
            return;
        }
        Set<String> referenced = referenced();
        for (String p : new HashSet<>(paths)) {
            if (!referenced.contains(p)) {
                store.delete(p);
            }
        }
    }

    public Set<String> referenced() throws SQLException {
        Set<String> out = new HashSet<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT DISTINCT relative_path FROM anime_poster WHERE relative_path IS NOT NULL");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** Fichier local disparu (effacé à la main, disque changé) : on oublie le chemin, la tâche le retélécharge. */
    public void markMissing(long animeId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE anime_poster SET status = 'PENDING', relative_path = NULL, public_id = NULL, sha256 = NULL,
                         failed_source = NULL, attempts = 0, next_attempt_at = NULL, last_error = 'fichier local disparu',
                         updated_at = now()
                     WHERE anime_id = ?""")) {
            st.setLong(1, animeId);
            st.executeUpdate();
        }
    }

    /** Retélécharger (admin) : on oublie les échecs ; l'affiche locale actuelle reste servie en attendant. */
    public void requeue(long animeId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE anime_poster SET status = 'PENDING', failed_source = NULL, attempts = 0, next_attempt_at = NULL,
                         last_error = NULL, updated_at = now()
                     WHERE anime_id = ?""")) {
            st.setLong(1, animeId);
            st.executeUpdate();
        }
    }

    // --- URL pour l'API ------------------------------------------------------------------------------

    /** Repli : fichier local, puis URL distante (TMDB, sinon AniList), puis rien (visuel de remplacement). */
    public Map<Long, Urls> urls(Collection<Long> animeIds) {
        Map<Long, Urls> out = new HashMap<>();
        if (animeIds.isEmpty()) {
            return out;
        }
        List<Long> missing = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT a.id, p.public_id, p.relative_path,
                            p.provider <> 'TMDB' OR p.fetched_at > now() - make_interval(secs => ?),
                            CASE WHEN t.poster_path IS NOT NULL AND t.fetched_at > now() - make_interval(secs => ?) THEN ? || t.poster_path END,
                            a.poster_url, a.poster_large_url, p.source_url
                     FROM anime a LEFT JOIN anime_poster p ON p.anime_id = a.id LEFT JOIN anime_tmdb t ON t.anime_id = a.id
                     WHERE a.id = ANY(?)""")) {
            st.setLong(1, config.tmdbMaxAge().toSeconds());
            st.setLong(2, config.tmdbMaxAge().toSeconds());
            st.setString(3, config.tmdbImageBase().replaceAll("/+$", ""));
            Array ids = c.createArrayOf("bigint", animeIds.toArray());
            st.setArray(4, ids);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    String publicId = rs.getString(2);
                    String relative = rs.getString(3);
                    String tmdb = rs.getString(5);
                    String small = rs.getString(6);
                    String large = rs.getString(7);
                    // Source voulue aujourd'hui (même règle que DESIRED) : la copie locale n'est servie que si elle en
                    // vient. Après une correction manuelle, la nouvelle image s'affiche donc tout de suite (chargée à
                    // la source), le temps que la tâche de fond télécharge la nouvelle copie.
                    String desired = tmdb != null ? tmdb : (large != null ? large : small);
                    boolean current = desired != null && desired.equals(rs.getString(8));
                    if (publicId != null && relative != null && rs.getBoolean(4) && current) {
                        if (store.existing(relative).isPresent()) {
                            String local = "/api/posters/" + publicId;
                            out.put(id, new Urls(local, local, true));
                            continue;
                        }
                        missing.add(id);
                    }
                    if (tmdb != null) {
                        out.put(id, new Urls(tmdb, tmdb, false));
                    } else if (small != null || large != null) {
                        out.put(id, new Urls(small != null ? small : large, large != null ? large : small, false));
                    }
                }
            }
            for (long id : missing) {
                markMissing(id);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** Fichier servi pour /api/posters/{publicId} (jamais un chemin venu du client). */
    public record Served(long animeId, java.nio.file.Path file, String contentType, String sha256) {
    }

    public Optional<Served> served(String publicId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT anime_id, relative_path, content_type, sha256 FROM anime_poster
                     WHERE public_id = ? AND relative_path IS NOT NULL
                       AND (provider <> 'TMDB' OR fetched_at > now() - make_interval(secs => ?))""")) {
            st.setString(1, publicId);
            st.setLong(2, config.tmdbMaxAge().toSeconds());
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next() || !PosterDownloader.TYPES.containsKey(rs.getString(3))) {
                    return Optional.empty();
                }
                long animeId = rs.getLong(1);
                Optional<java.nio.file.Path> file = store.existing(rs.getString(2));
                if (file.isEmpty()) {
                    markMissing(animeId);
                    return Optional.empty();
                }
                return Optional.of(new Served(animeId, file.get(), rs.getString(3), rs.getString(4)));
            }
        }
    }

    /** État de chaque animé, pour l'admin : source voulue et affiche locale. */
    public record State(long animeId, String title, String provider, String src, String status, String localProvider,
                        String relative, Long bytes, Instant fetchedAt, String failedSource, String lastError,
                        boolean localAllowed) {
        public boolean failed() {
            return failedSource != null && failedSource.equals(src);
        }
    }

    public List<State> states() throws SQLException {
        List<State> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("WITH d AS (" + DESIRED + """
                     )
                     SELECT d.id, a.title, d.provider, d.src, p.status, p.provider, p.relative_path, p.bytes, p.fetched_at,
                            p.failed_source, p.last_error,
                            p.provider IS NULL OR p.provider <> 'TMDB' OR p.fetched_at > now() - make_interval(secs => ?)
                     FROM d JOIN anime a ON a.id = d.id LEFT JOIN anime_poster p ON p.anime_id = d.id
                     ORDER BY lower(a.title), a.id""")) {
            bindDesired(st);
            st.setLong(4, config.tmdbMaxAge().toSeconds());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    OffsetDateTime f = rs.getObject(9, OffsetDateTime.class);
                    out.add(new State(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getString(7), (Long) rs.getObject(8), f == null ? null : f.toInstant(),
                            rs.getString(10), rs.getString(11), rs.getBoolean(12)));
                }
            }
        }
        return out;
    }

    static String newPublicId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** Pour la tâche : ménage des fichiers temporaires et orphelins. */
    public int sweep() throws SQLException {
        return store.sweep(referenced(), Duration.ofMinutes(10));
    }
}
