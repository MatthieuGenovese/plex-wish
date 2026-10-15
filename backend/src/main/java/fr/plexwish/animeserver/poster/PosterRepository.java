package fr.plexwish.animeserver.poster;

import fr.plexwish.animeserver.common.db.Db;
import fr.plexwish.animeserver.common.db.Jdbc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Affiches stockées sur le NAS ({@code anime_poster}) et choix de la source (TMDB récent, sinon AniList).
 * {@code tmdbMaxAgeSeconds} : âge maximal d'une fiche TMDB (conditions de l'API TMDB) ; {@code tmdbImageBase} : préfixe
 * des images TMDB, sans « / » final.
 */
@ApplicationScoped
public class PosterRepository {

    /** Animé dont l'affiche est à (re)télécharger depuis cette source. */
    public record Next(long animeId, String provider, String src) {
    }

    /** Image déjà téléchargée pour la même source (par un autre animé). */
    public record Stored(String relativePath, String sha256, String contentType, long bytes, OffsetDateTime fetchedAt) {
    }

    /** Affiche actuelle d'un animé. */
    public record Current(String relativePath, String sha256, String publicId) {
    }

    /** Affiche enregistrée avec succès. */
    public record Saved(String provider, String src, String publicId, String relativePath, String sha256, String contentType,
                        long bytes, OffsetDateTime fetchedAt) {
    }

    /** De quoi choisir l'URL d'affiche d'un animé ({@code localAllowed} : copie TMDB encore dans les 6 mois). */
    public record UrlRow(long animeId, String publicId, String relativePath, boolean localAllowed, String tmdbUrl,
                         String posterUrl, String posterLargeUrl, String sourceUrl) {
    }

    /** Fichier servi pour un identifiant public. */
    public record ServedRow(long animeId, String relativePath, String contentType, String sha256) {
    }

    /**
     * TMDB passe avant AniList pour l'affiche, sauf si l'admin a choisi la fiche AniList à la main (correction
     * verrouillée) : il a choisi cette fiche en voyant son affiche, c'est elle qu'il attend. Une fiche TMDB choisie à la
     * main plus récemment repasse devant. Un paramètre : âge maximal d'une fiche TMDB (secondes).
     */
    static final String TMDB_WINS = """
            (t.poster_path IS NOT NULL AND t.fetched_at > now() - make_interval(secs => ?)
             AND (m.locked IS NOT TRUE OR (t.locked AND t.updated_at >= m.updated_at)))""";

    /** Source voulue par animé (fournisseur, URL) : TMDB récent d'abord, sinon AniList. Paramètres : âge max ×2, base TMDB. */
    private static final String DESIRED = """
            SELECT a.id,
                   CASE WHEN """ + TMDB_WINS + """
             THEN 'TMDB'
                        WHEN coalesce(a.poster_large_url, a.poster_url) IS NOT NULL THEN 'ANILIST' END AS provider,
                   CASE WHEN """ + TMDB_WINS + """
             THEN ? || t.poster_path
                        ELSE coalesce(a.poster_large_url, a.poster_url) END AS src
            FROM anime a LEFT JOIN anime_tmdb t ON t.anime_id = a.id
                 LEFT JOIN anime_metadata_match m ON m.anime_id = a.id""";

    @Inject
    Db db;

    /** Prochain animé dont l'affiche manque, a changé de source ou doit être redemandée à TMDB. */
    public Optional<Next> next(long tmdbMaxAgeSeconds, String tmdbImageBase, long tmdbRefreshAfterSeconds) throws SQLException {
        return db.one("WITH d AS (" + DESIRED + """
                )
                SELECT d.id, d.provider, d.src FROM d LEFT JOIN anime_poster p ON p.anime_id = d.id
                WHERE d.src IS NOT NULL AND d.src IS DISTINCT FROM p.failed_source
                  AND (p.next_attempt_at IS NULL OR p.next_attempt_at <= now())
                  AND (p.anime_id IS NULL OR p.status = 'PENDING' OR p.source_url <> d.src
                       OR (p.provider = 'TMDB' AND p.fetched_at < now() - make_interval(secs => ?)))
                ORDER BY p.anime_id NULLS FIRST, d.id LIMIT 1""",
                rs -> new Next(rs.getLong(1), rs.getString(2), rs.getString(3)),
                tmdbMaxAgeSeconds, tmdbMaxAgeSeconds, tmdbImageBase, tmdbRefreshAfterSeconds);
    }

    /** Image déjà présente pour la même source (autre animé), TMDB seulement si récente. */
    public Optional<Stored> sameSource(String src, long animeId, long tmdbRefreshAfterSeconds) throws SQLException {
        return db.one("""
                SELECT relative_path, sha256, content_type, bytes, fetched_at FROM anime_poster
                WHERE source_url = ? AND status = 'OK' AND anime_id <> ? AND relative_path IS NOT NULL
                  AND (provider <> 'TMDB' OR fetched_at > now() - make_interval(secs => ?))
                ORDER BY fetched_at DESC LIMIT 1""",
                rs -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getObject(5, OffsetDateTime.class)),
                src, animeId, tmdbRefreshAfterSeconds);
    }

    public Optional<Current> current(long animeId) throws SQLException {
        return db.one("SELECT relative_path, sha256, public_id FROM anime_poster WHERE anime_id = ?",
                rs -> new Current(rs.getString(1), rs.getString(2), rs.getString(3)), animeId);
    }

    public void saveOk(long animeId, Saved s) throws SQLException {
        db.update("""
                INSERT INTO anime_poster (anime_id, status, provider, source_url, public_id, relative_path, sha256,
                                          content_type, bytes, fetched_at, failed_source, attempts, next_attempt_at,
                                          last_error, updated_at)
                VALUES (?, 'OK', ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0, NULL, NULL, now())
                ON CONFLICT (anime_id) DO UPDATE SET status = 'OK', provider = EXCLUDED.provider,
                    source_url = EXCLUDED.source_url, public_id = EXCLUDED.public_id,
                    relative_path = EXCLUDED.relative_path, sha256 = EXCLUDED.sha256,
                    content_type = EXCLUDED.content_type, bytes = EXCLUDED.bytes, fetched_at = EXCLUDED.fetched_at,
                    failed_source = NULL, attempts = 0, next_attempt_at = NULL, last_error = NULL, updated_at = now()""",
                animeId, s.provider(), s.src(), s.publicId(), s.relativePath(), s.sha256(), s.contentType(), s.bytes(),
                s.fetchedAt());
    }

    /**
     * Échec : refus définitif ({@code permanent}), ou échecs passagers répétés ({@code maxAttempts}) → cette source n'est
     * plus retentée tant qu'elle ne change pas. Une affiche locale déjà présente reste servie.
     */
    public void saveFailure(long animeId, String provider, String src, String message, boolean permanent, int maxAttempts)
            throws SQLException {
        db.update("""
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
                    last_error = EXCLUDED.last_error, updated_at = now()""",
                animeId, permanent, provider, src, permanent, Jdbc.varchar(src), permanent, message, permanent, maxAttempts,
                permanent, maxAttempts, permanent, maxAttempts);
    }

    /** Affiches TMDB trop vieilles ou dont la fiche TMDB a disparu : effacées ; renvoie leurs chemins locaux. */
    public List<String> deleteExpiredTmdb(long tmdbMaxAgeSeconds) throws SQLException {
        List<String> paths = new ArrayList<>();
        db.list("""
                DELETE FROM anime_poster p WHERE p.provider = 'TMDB'
                  AND (p.fetched_at < now() - make_interval(secs => ?)
                       OR NOT EXISTS (SELECT 1 FROM anime_tmdb t WHERE t.anime_id = p.anime_id AND t.poster_path IS NOT NULL
                                      AND t.fetched_at > now() - make_interval(secs => ?)))
                RETURNING p.relative_path""", rs -> {
            if (rs.getString(1) != null) {
                paths.add(rs.getString(1));
            }
            return true;
        }, tmdbMaxAgeSeconds, tmdbMaxAgeSeconds);
        return paths;
    }

    /** Toutes les affiches TMDB effacées ; renvoie le chemin local de chacune (null si aucun). */
    public List<String> deleteAllTmdb() throws SQLException {
        List<String> paths = new ArrayList<>();
        db.list("DELETE FROM anime_poster WHERE provider = 'TMDB' RETURNING relative_path", rs -> {
            paths.add(rs.getString(1));
            return true;
        });
        return paths;
    }

    /** Chemins locaux encore utilisés par une affiche. */
    public Set<String> referenced() throws SQLException {
        return new HashSet<>(db.list("SELECT DISTINCT relative_path FROM anime_poster WHERE relative_path IS NOT NULL",
                rs -> rs.getString(1)));
    }

    /** Fichier local disparu : on oublie le chemin, la tâche le retélécharge. */
    public void markMissing(long animeId) throws SQLException {
        db.update("""
                UPDATE anime_poster SET status = 'PENDING', relative_path = NULL, public_id = NULL, sha256 = NULL,
                    failed_source = NULL, attempts = 0, next_attempt_at = NULL, last_error = 'fichier local disparu',
                    updated_at = now()
                WHERE anime_id = ?""", animeId);
    }

    /** On oublie les échecs ; l'affiche locale actuelle reste servie en attendant. */
    public void requeue(long animeId) throws SQLException {
        db.update("""
                UPDATE anime_poster SET status = 'PENDING', failed_source = NULL, attempts = 0, next_attempt_at = NULL,
                    last_error = NULL, updated_at = now()
                WHERE anime_id = ?""", animeId);
    }

    public List<UrlRow> urlRows(Collection<Long> animeIds, long tmdbMaxAgeSeconds, String tmdbImageBase) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT a.id, p.public_id, p.relative_path,
                           p.provider <> 'TMDB' OR p.fetched_at > now() - make_interval(secs => ?),
                           CASE WHEN """ + TMDB_WINS + """
                     THEN ? || t.poster_path END,
                           a.poster_url, a.poster_large_url, p.source_url
                    FROM anime a LEFT JOIN anime_poster p ON p.anime_id = a.id LEFT JOIN anime_tmdb t ON t.anime_id = a.id
                         LEFT JOIN anime_metadata_match m ON m.anime_id = a.id
                    WHERE a.id = ANY(?)""")) {
                st.setLong(1, tmdbMaxAgeSeconds);
                st.setLong(2, tmdbMaxAgeSeconds);
                st.setString(3, tmdbImageBase);
                st.setArray(4, c.createArrayOf("bigint", animeIds.toArray()));
                List<UrlRow> out = new ArrayList<>();
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        out.add(new UrlRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5),
                                rs.getString(6), rs.getString(7), rs.getString(8)));
                    }
                }
                return out;
            }
        });
    }

    public Optional<ServedRow> served(String publicId, long tmdbMaxAgeSeconds) throws SQLException {
        return db.one("""
                SELECT anime_id, relative_path, content_type, sha256 FROM anime_poster
                WHERE public_id = ? AND relative_path IS NOT NULL
                  AND (provider <> 'TMDB' OR fetched_at > now() - make_interval(secs => ?))""",
                rs -> new ServedRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                publicId, tmdbMaxAgeSeconds);
    }

    /** État de chaque animé pour l'admin : source voulue et affiche locale. */
    public List<PosterService.State> states(long tmdbMaxAgeSeconds, String tmdbImageBase) throws SQLException {
        return db.list("WITH d AS (" + DESIRED + """
                )
                SELECT d.id, a.title, d.provider, d.src, p.status, p.provider, p.relative_path, p.bytes, p.fetched_at,
                       p.failed_source, p.last_error,
                       p.provider IS NULL OR p.provider <> 'TMDB' OR p.fetched_at > now() - make_interval(secs => ?)
                FROM d JOIN anime a ON a.id = d.id LEFT JOIN anime_poster p ON p.anime_id = d.id
                ORDER BY lower(a.title), a.id""",
                rs -> new PosterService.State(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), (Long) rs.getObject(8), Jdbc.instant(rs, 9),
                        rs.getString(10), rs.getString(11), rs.getBoolean(12)),
                tmdbMaxAgeSeconds, tmdbMaxAgeSeconds, tmdbImageBase, tmdbMaxAgeSeconds);
    }
}
