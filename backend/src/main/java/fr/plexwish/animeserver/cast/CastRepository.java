package fr.plexwish.animeserver.cast;

import fr.plexwish.animeserver.common.db.Db;
import fr.plexwish.animeserver.common.db.Jdbc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Distribution (personnages, comédiens, rôles par animé) : tables anime_cast, cast_character, person, état de
 * récupération par animé (anime_cast_state) et photos des comédiens (cast_image). Les méthodes qui prennent une
 * {@link Connection} s'exécutent dans la transaction de l'appelant.
 */
@ApplicationScoped
public class CastRepository {

    /** Animé visible : au moins un épisode dont le fichier est disponible (comme la bibliothèque). */
    static final String VISIBLE = """
            EXISTS (SELECT 1 FROM episode e JOIN season s ON s.id = e.season_id JOIN media_file f ON f.id = e.media_file_id
                    WHERE s.anime_id = a.id AND f.available)""";

    /** Ligne de distribution d'un animé ({@code personProviderId} null : personnage sans comédien connu). */
    public record CastRow(String role, String language, String characterName, String characterNativeName,
                          String personProviderId, String personName, String personNativeName, String personImageUrl) {
    }

    public record PersonRow(long id, String name, String nativeName, String imageUrl) {
    }

    /** Rôle d'un comédien dans un animé visible. */
    public record RoleRow(long animeId, String animeTitle, Integer year, String role, String characterName,
                          String characterNativeName) {
    }

    /** {@code metadataUrl} peut être null : enveloppé pour distinguer « animé absent ». */
    public record VisibleAnime(String metadataUrl) {
    }

    @Inject
    Db db;

    /** Animé visible (vide sinon), avec l'adresse de sa fiche. */
    public Optional<VisibleAnime> visibleAnime(long animeId) throws SQLException {
        return db.one("SELECT a.metadata_url FROM anime a WHERE a.id = ? AND " + VISIBLE,
                rs -> new VisibleAnime(rs.getString(1)), animeId);
    }

    public List<CastRow> castOf(long animeId, String language) throws SQLException {
        return db.list("""
                SELECT ac.role, ac.language, ch.name, ch.native_name, p.provider_id, p.name, p.native_name, p.image_url
                FROM anime_cast ac JOIN cast_character ch ON ch.id = ac.character_id LEFT JOIN person p ON p.id = ac.person_id
                WHERE ac.anime_id = ? AND ac.language = ? ORDER BY ac.position""",
                rs -> new CastRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8)),
                animeId, language);
    }

    public Optional<PersonRow> person(String provider, String providerId) throws SQLException {
        return db.one("SELECT id, name, native_name, image_url FROM person WHERE provider = ? AND provider_id = ?",
                rs -> new PersonRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), provider, providerId);
    }

    /** Rôles du comédien dans les animés visibles, par titre. */
    public List<RoleRow> visibleRolesOf(long personId) throws SQLException {
        return db.list("""
                SELECT a.id, a.title, a.year, ac.role, ch.name, ch.native_name
                FROM anime_cast ac JOIN anime a ON a.id = ac.anime_id JOIN cast_character ch ON ch.id = ac.character_id
                WHERE ac.person_id = ?""" + " AND " + VISIBLE + """
                 ORDER BY lower(a.title), a.id, ac.position""",
                rs -> new RoleRow(rs.getLong(1), rs.getString(2), (Integer) rs.getObject(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)),
                personId);
    }

    // --- Tâche de fond : distribution d'un animé ---------------------------------------------------------------

    /**
     * Prochain animé apparié à AniList dont la distribution manque, a changé de fiche, est à rafraîchir
     * ({@code refreshAfterSeconds}) ou incomplète ; {@code seasons} : saisons voulues (au plus {@code maxSeasons}).
     */
    Optional<CastService.Work> nextAnime(int maxSeasons, long refreshAfterSeconds) throws SQLException {
        String wanted = """
                LEAST(?, GREATEST(1, (SELECT count(*) FROM season s WHERE s.anime_id = a.id AND s.season_number > 0)))""";
        return db.one("SELECT a.id, a.metadata_provider_id, " + wanted + ", a.title" + """
                 FROM anime a LEFT JOIN anime_cast_state st ON st.anime_id = a.id
                WHERE a.metadata_provider = 'ANILIST' AND a.metadata_provider_id IS NOT NULL
                  AND (st.next_attempt_at IS NULL OR st.next_attempt_at <= now())
                  AND (st.anime_id IS NULL OR st.status = 'PENDING'
                       OR st.source_id IS DISTINCT FROM a.metadata_provider_id
                       OR (st.status IN ('OK', 'NONE') AND st.fetched_at < now() - make_interval(secs => ?))
                       OR (st.status = 'OK' AND st.last_error IS NOT NULL AND st.seasons < """ + wanted + """
                ))
                ORDER BY st.anime_id NULLS FIRST, a.id LIMIT 1""",
                rs -> new CastService.Work(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4)),
                maxSeasons, refreshAfterSeconds, maxSeasons);
    }

    void deleteCastOf(Connection c, long animeId) throws SQLException {
        Jdbc.update(c, "DELETE FROM anime_cast WHERE anime_id = ?", animeId);
    }

    /** Personnage (nom seulement) ; renvoie son id. */
    long upsertCharacter(Connection c, String provider, String providerId, String name, String nativeName) throws SQLException {
        return Jdbc.one(c, """
                INSERT INTO cast_character (provider, provider_id, name, native_name, fetched_at) VALUES (?, ?, ?, ?, now())
                ON CONFLICT (provider, provider_id) DO UPDATE SET name = EXCLUDED.name, native_name = EXCLUDED.native_name,
                    fetched_at = now()
                RETURNING id""", rs -> rs.getLong(1), provider, providerId, name, Jdbc.varchar(nativeName)).orElseThrow();
    }

    /** Comédien ; renvoie son id. */
    long upsertPerson(Connection c, String provider, String providerId, String name, String nativeName, String imageUrl)
            throws SQLException {
        return Jdbc.one(c, """
                INSERT INTO person (provider, provider_id, name, native_name, image_url, fetched_at) VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (provider, provider_id) DO UPDATE SET name = EXCLUDED.name, native_name = EXCLUDED.native_name,
                    image_url = EXCLUDED.image_url, fetched_at = now()
                RETURNING id""", rs -> rs.getLong(1), provider, providerId, name, Jdbc.varchar(nativeName),
                Jdbc.varchar(imageUrl)).orElseThrow();
    }

    /** Photo mise en file de téléchargement (rien si elle est déjà connue). */
    void queueImage(Connection c, String sourceUrl) throws SQLException {
        Jdbc.update(c, "INSERT INTO cast_image (source_url, status) VALUES (?, 'PENDING') ON CONFLICT DO NOTHING", sourceUrl);
    }

    void insertRole(Connection c, long animeId, long characterId, String language, Long personId, String role, int position,
                    String sourceId) throws SQLException {
        Jdbc.update(c, """
                INSERT INTO anime_cast (anime_id, character_id, language, person_id, role, position, source_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                animeId, characterId, language, new Jdbc.Typed(personId, Types.BIGINT), role, position, sourceId);
    }

    /**
     * Ménage : personnes, personnages et photos que plus aucune distribution n'utilise. Renvoie les chemins locaux
     * des photos effacées.
     */
    List<String> deleteUnused() throws SQLException {
        return db.withConnection(c -> {
            Jdbc.update(c, "DELETE FROM person p WHERE NOT EXISTS (SELECT 1 FROM anime_cast ac WHERE ac.person_id = p.id)");
            Jdbc.update(c, "DELETE FROM cast_character ch WHERE NOT EXISTS (SELECT 1 FROM anime_cast ac WHERE ac.character_id = ch.id)");
            List<String> paths = new ArrayList<>();
            Jdbc.list(c, """
                    DELETE FROM cast_image i WHERE NOT EXISTS (SELECT 1 FROM person p WHERE p.image_url = i.source_url)
                    RETURNING relative_path""", rs -> {
                if (rs.getString(1) != null) {
                    paths.add(rs.getString(1));
                }
                return true;
            });
            return paths;
        });
    }

    /** État de récupération ({@code retryInSeconds} : prochain essai, null = au prochain rafraîchissement). */
    void saveState(long animeId, String status, String provider, String sourceId, int seasons, int roles, Long retryInSeconds,
                   String error) throws SQLException {
        db.update("""
                INSERT INTO anime_cast_state (anime_id, status, provider, source_id, seasons, roles, fetched_at, attempts,
                                              next_attempt_at, last_error, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, now(), 0, now() + make_interval(secs => ?), ?, now())
                ON CONFLICT (anime_id) DO UPDATE SET status = EXCLUDED.status, provider = EXCLUDED.provider,
                    source_id = EXCLUDED.source_id, seasons = EXCLUDED.seasons, roles = EXCLUDED.roles,
                    fetched_at = now(), attempts = 0, next_attempt_at = EXCLUDED.next_attempt_at,
                    last_error = EXCLUDED.last_error, updated_at = now()""",
                animeId, status, provider, sourceId, seasons, roles, new Jdbc.Typed(retryInSeconds, Types.BIGINT),
                Jdbc.varchar(error));
    }

    /** Erreur imprévue : nouvel essai espacé, abandon (FAILED) après {@code maxAttempts}. */
    void saveFailure(long animeId, String provider, String sourceId, String error, int maxAttempts) throws SQLException {
        db.update("""
                INSERT INTO anime_cast_state (anime_id, status, provider, source_id, attempts, next_attempt_at, last_error)
                VALUES (?, 'PENDING', ?, ?, 1, now() + interval '2 minutes', ?)
                ON CONFLICT (anime_id) DO UPDATE SET attempts = anime_cast_state.attempts + 1,
                    status = CASE WHEN anime_cast_state.attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
                    source_id = EXCLUDED.source_id,
                    next_attempt_at = now() + make_interval(mins => power(2, anime_cast_state.attempts + 1)::int),
                    last_error = EXCLUDED.last_error, updated_at = now()""",
                animeId, provider, sourceId, error, maxAttempts);
    }

    boolean hasAniListMatch(long animeId) throws SQLException {
        return db.one("SELECT 1 FROM anime WHERE id = ? AND metadata_provider = 'ANILIST' AND metadata_provider_id IS NOT NULL",
                rs -> true, animeId).isPresent();
    }

    boolean animeExists(long animeId) throws SQLException {
        return db.one("SELECT 1 FROM anime WHERE id = ?", rs -> true, animeId).isPresent();
    }

    /** À redemander au prochain passage ; les photos en échec de ses comédiens sont retentées aussi. */
    void requeue(long animeId, String provider) throws SQLException {
        db.withConnection(c -> {
            Jdbc.update(c, """
                    INSERT INTO anime_cast_state (anime_id, status, provider) VALUES (?, 'PENDING', ?)
                    ON CONFLICT (anime_id) DO UPDATE SET status = 'PENDING', attempts = 0, next_attempt_at = NULL,
                        last_error = NULL, updated_at = now()""", animeId, provider);
            Jdbc.update(c, """
                    UPDATE cast_image SET status = 'PENDING', attempts = 0, next_attempt_at = NULL, last_error = NULL
                    WHERE status = 'FAILED' AND source_url IN (
                        SELECT p.image_url FROM anime_cast ac JOIN person p ON p.id = ac.person_id WHERE ac.anime_id = ?)""",
                    animeId);
            return null;
        });
    }

    /** Efface toute la distribution (une transaction) ; renvoie le nombre de rôles qu'il y avait. */
    int truncateAll() throws SQLException {
        return db.inTransaction(c -> {
            int n = Jdbc.one(c, "SELECT count(*) FROM anime_cast", rs -> rs.getInt(1)).orElseThrow();
            try (PreparedStatement st = c.prepareStatement("TRUNCATE anime_cast, anime_cast_state, person, cast_character, cast_image")) {
                st.execute();
            }
            return n;
        });
    }

    // --- Photos des comédiens -----------------------------------------------------------------------------------

    /** Image d'une source : chemin local, empreinte, identifiant public. */
    record ImageRow(String sourceUrl, String publicId, String relativePath, String sha256, String contentType) {
    }

    Optional<String> nextImage() throws SQLException {
        return db.one("""
                SELECT source_url FROM cast_image WHERE status = 'PENDING'
                  AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                ORDER BY attempts, source_url LIMIT 1""", rs -> rs.getString(1));
    }

    Optional<ImageRow> image(String sourceUrl) throws SQLException {
        return db.one("SELECT relative_path, sha256, public_id FROM cast_image WHERE source_url = ?",
                rs -> new ImageRow(sourceUrl, rs.getString(3), rs.getString(1), rs.getString(2), null), sourceUrl);
    }

    void imageDownloaded(String sourceUrl, String publicId, String relativePath, String sha256, String contentType, long bytes)
            throws SQLException {
        db.update("""
                UPDATE cast_image SET status = 'OK', public_id = ?, relative_path = ?, sha256 = ?, content_type = ?,
                    bytes = ?, fetched_at = now(), attempts = 0, next_attempt_at = NULL, last_error = NULL
                WHERE source_url = ?""", publicId, relativePath, sha256, contentType, bytes, sourceUrl);
    }

    void imageFailed(String sourceUrl, String error, boolean permanent, int maxAttempts) throws SQLException {
        db.update("""
                UPDATE cast_image SET attempts = attempts + 1,
                    status = CASE WHEN relative_path IS NOT NULL THEN 'OK'
                                  WHEN ? OR attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
                    next_attempt_at = CASE WHEN ? OR attempts + 1 >= ? THEN NULL
                                           ELSE now() + make_interval(mins => power(2, attempts + 1)::int) END,
                    last_error = ?
                WHERE source_url = ?""", permanent, maxAttempts, permanent, maxAttempts, error, sourceUrl);
    }

    Set<String> referencedImages() throws SQLException {
        return new HashSet<>(db.list("SELECT DISTINCT relative_path FROM cast_image WHERE relative_path IS NOT NULL",
                rs -> rs.getString(1)));
    }

    List<ImageRow> images(Collection<String> sources) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT source_url, public_id, relative_path FROM cast_image WHERE source_url = ANY(?)")) {
                st.setArray(1, c.createArrayOf("text", sources.toArray()));
                List<ImageRow> out = new ArrayList<>();
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        out.add(new ImageRow(rs.getString(1), rs.getString(2), rs.getString(3), null, null));
                    }
                }
                return out;
            }
        });
    }

    /** Fichier local disparu : on oublie le chemin, la tâche le retélécharge. */
    void markImageMissing(String sourceUrl) throws SQLException {
        db.update("""
                UPDATE cast_image SET status = 'PENDING', public_id = NULL, relative_path = NULL, sha256 = NULL,
                    attempts = 0, next_attempt_at = NULL, last_error = 'fichier local disparu' WHERE source_url = ?""", sourceUrl);
    }

    Optional<ImageRow> servedImage(String publicId) throws SQLException {
        return db.one("SELECT source_url, relative_path, content_type, sha256 FROM cast_image WHERE public_id = ? AND relative_path IS NOT NULL",
                rs -> new ImageRow(rs.getString(1), publicId, rs.getString(2), rs.getString(4), rs.getString(3)), publicId);
    }

    // --- Administration -----------------------------------------------------------------------------------------

    /** Animés appariés à AniList, par état de la distribution (sans état : PENDING). */
    Map<String, Long> stateCounts() throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        db.list("""
                SELECT coalesce(st.status, 'PENDING'), count(*) FROM anime a LEFT JOIN anime_cast_state st ON st.anime_id = a.id
                WHERE a.metadata_provider = 'ANILIST' AND a.metadata_provider_id IS NOT NULL GROUP BY 1""",
                rs -> out.put(rs.getString(1), rs.getLong(2)));
        return out;
    }

    Map<String, Long> imageCounts() throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        db.list("SELECT status, count(*) FROM cast_image GROUP BY 1", rs -> out.put(rs.getString(1), rs.getLong(2)));
        return out;
    }

    /** Place des photos sur le disque (une fois par image) : total et moyenne, en octets. */
    long[] imageDisk() throws SQLException {
        return db.one("""
                SELECT coalesce(sum(bytes), 0), coalesce(avg(bytes), 0)::bigint
                FROM (SELECT DISTINCT ON (sha256) bytes FROM cast_image WHERE relative_path IS NOT NULL) x""",
                rs -> new long[]{rs.getLong(1), rs.getLong(2)}).orElseThrow();
    }

    /** Nombres de comédiens, personnages et rôles. */
    long[] totals() throws SQLException {
        return db.withConnection(c -> new long[]{
                Jdbc.one(c, "SELECT count(*) FROM person", rs -> rs.getLong(1)).orElseThrow(),
                Jdbc.one(c, "SELECT count(*) FROM cast_character", rs -> rs.getLong(1)).orElseThrow(),
                Jdbc.one(c, "SELECT count(*) FROM anime_cast", rs -> rs.getLong(1)).orElseThrow()});
    }

    /** Ligne de l'administration ({@code status} NO_MATCH : pas d'appariement AniList). */
    record EntryRow(long animeId, String title, String status, int roles, int seasons, Instant fetchedAt, String lastError) {
    }

    private static final String ENTRY_STATUS = """
            CASE WHEN a.metadata_provider IS DISTINCT FROM 'ANILIST' OR a.metadata_provider_id IS NULL THEN 'NO_MATCH'
                 ELSE coalesce(st.status, 'PENDING') END""";

    private static final String ENTRY_FROM = " FROM anime a LEFT JOIN anime_cast_state st ON st.anime_id = a.id";

    /** {@code filter} : missing, failed, ok ou null ; {@code titleLike} : motif LIKE (échappement « ! »). */
    private static String entryWhere(String filter) {
        return " WHERE (?::text IS NULL OR lower(unaccent(a.title)) LIKE lower(unaccent(?)) ESCAPE '!')"
                + switch (filter == null ? "" : filter) {
                    case "missing" -> " AND (" + ENTRY_STATUS + ") <> 'OK'";
                    case "failed" -> " AND st.status = 'FAILED'";
                    case "ok" -> " AND st.status = 'OK'";
                    default -> "";
                };
    }

    long countEntries(String filter, String titleLike) throws SQLException {
        return db.one("SELECT count(*)" + ENTRY_FROM + entryWhere(filter), rs -> rs.getLong(1),
                Jdbc.varchar(titleLike), Jdbc.varchar(titleLike)).orElseThrow();
    }

    List<EntryRow> entries(String filter, String titleLike, int size, long offset) throws SQLException {
        return db.list("SELECT a.id, a.title, " + ENTRY_STATUS
                        + ", coalesce(st.roles, 0), coalesce(st.seasons, 0), st.fetched_at, st.last_error" + ENTRY_FROM
                        + entryWhere(filter) + " ORDER BY lower(a.title), a.id LIMIT ? OFFSET ?",
                rs -> new EntryRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                        Jdbc.instant(rs, 6), rs.getString(7)),
                Jdbc.varchar(titleLike), Jdbc.varchar(titleLike), size, offset);
    }
}
