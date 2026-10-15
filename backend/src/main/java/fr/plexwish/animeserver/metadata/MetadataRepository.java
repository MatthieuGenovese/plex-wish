package fr.plexwish.animeserver.metadata;

import fr.plexwish.animeserver.common.db.Db;
import fr.plexwish.animeserver.common.db.Jdbc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Appariement des animés avec leur fiche ({@code anime_metadata_match}), fiche recopiée dans {@code anime} et genres
 * ({@code anime_genre}). Les méthodes qui prennent une {@link Connection} s'exécutent dans la transaction de l'appelant.
 */
@ApplicationScoped
public class MetadataRepository {

    /** Titre du dossier et nombre d'épisodes hors Spéciaux. */
    public record AnimeToMatch(String folderTitle, int localEpisodes) {
    }

    /** Fiche recopiée dans l'animé ; tout à null : fiche retirée. */
    public record AnimeSheet(String alternativeTitle, String synopsis, String synopsisLanguage, String posterUrl,
                             String posterLargeUrl, Integer year, String provider, String providerId, String siteUrl) {
        static final AnimeSheet NONE = new AnimeSheet(null, null, null, null, null, null, null, null, null);
    }

    /** Ligne de l'administration ({@code candidatesJson} : candidats gardés, JSON). */
    public record EntryRow(long animeId, String title, String status, String reason, BigDecimal score, boolean locked,
                           String providerId, Integer year, String posterUrl, String metadataUrl, String candidatesJson,
                           String lastError, Instant updatedAt, String updatedBy, String alternativeTitle) {
    }

    /** Fiche appliquée à l'animé (verrouillé pour la transaction). */
    public record CurrentSheet(String providerId, Integer year, String posterUrl, String metadataUrl, String synopsis,
                               String candidatesJson, String alternativeTitle, String title) {
    }

    public record AnimeRef(long id, String title) {
    }

    @Inject
    Db db;

    // --- Tâche automatique -------------------------------------------------------------------------------------

    public Optional<String> titleOf(long animeId) throws SQLException {
        return db.one("SELECT title FROM anime WHERE id = ?", rs -> rs.getString(1), animeId);
    }

    /** Animés sans état, ou à refaire et dont l'heure est venue ; les plus anciens d'abord. */
    public Optional<Long> nextPending(OffsetDateTime now) throws SQLException {
        return db.one("""
                SELECT a.id FROM anime a LEFT JOIN anime_metadata_match m ON m.anime_id = a.id
                WHERE m.anime_id IS NULL
                   OR (m.status = 'PENDING' AND NOT m.locked AND (m.next_attempt_at IS NULL OR m.next_attempt_at <= ?))
                ORDER BY m.next_attempt_at NULLS FIRST, a.id LIMIT 1""", rs -> rs.getLong(1), now);
    }

    public Optional<AnimeToMatch> animeToMatch(long animeId) throws SQLException {
        return db.one("""
                SELECT a.title, (SELECT count(*) FROM episode e JOIN season s ON s.id = e.season_id
                                 WHERE s.anime_id = a.id AND s.season_number > 0)
                FROM anime a WHERE a.id = ?""", rs -> new AnimeToMatch(rs.getString(1), rs.getInt(2)), animeId);
    }

    /** Décision automatique ; faux si une correction manuelle a verrouillé l'animé entre-temps (rien d'écrit). */
    public boolean saveAutomatic(Connection c, long animeId, String status, String provider, String providerId, Double score,
                                 String reason, String candidatesJson) throws SQLException {
        return Jdbc.one(c, """
                INSERT INTO anime_metadata_match (anime_id, status, provider, provider_id, score, reason, candidates,
                                                  locked, attempts, next_attempt_at, last_error, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, FALSE, 0, NULL, NULL, now(), 'auto')
                ON CONFLICT (anime_id) DO UPDATE SET status = EXCLUDED.status, provider = EXCLUDED.provider,
                    provider_id = EXCLUDED.provider_id, score = EXCLUDED.score, reason = EXCLUDED.reason,
                    candidates = EXCLUDED.candidates, attempts = 0, next_attempt_at = NULL, last_error = NULL,
                    updated_at = now(), updated_by = 'auto'
                WHERE NOT anime_metadata_match.locked
                RETURNING anime_id""", rs -> true,
                animeId, status, provider, Jdbc.varchar(providerId), score, Jdbc.varchar(reason), candidatesJson).isPresent();
    }

    /** Recopie la fiche dans l'animé ({@link AnimeSheet#NONE} : l'efface). Le titre du dossier ne change jamais. */
    public void applyToAnime(Connection c, long animeId, AnimeSheet s) throws SQLException {
        Jdbc.update(c, """
                UPDATE anime SET alternative_title = ?, synopsis = ?, synopsis_language = ?, poster_url = ?,
                    poster_large_url = ?, year = ?, metadata_provider = ?, metadata_provider_id = ?, metadata_url = ?,
                    metadata_fetched_at = CASE WHEN ?::text IS NULL THEN NULL ELSE now() END
                WHERE id = ?""",
                Jdbc.varchar(s.alternativeTitle()), Jdbc.varchar(s.synopsis()), Jdbc.varchar(s.synopsisLanguage()),
                Jdbc.varchar(s.posterUrl()), Jdbc.varchar(s.posterLargeUrl()), s.year(), Jdbc.varchar(s.provider()),
                Jdbc.varchar(s.providerId()), Jdbc.varchar(s.siteUrl()), Jdbc.varchar(s.providerId()), animeId);
    }

    /** Remplace les genres ; {@code fetchedAt} null : genres à récupérer plus tard. */
    public void replaceGenres(Connection c, long animeId, List<String> genres, OffsetDateTime fetchedAt) throws SQLException {
        Jdbc.update(c, "DELETE FROM anime_genre WHERE anime_id = ?", animeId);
        if (genres != null && !genres.isEmpty()) {
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO anime_genre (anime_id, genre) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                for (String g : genres) {
                    ins.setLong(1, animeId);
                    ins.setString(2, g);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
        Jdbc.update(c, "UPDATE anime SET genres_fetched_at = ? WHERE id = ?", fetchedAt, animeId);
    }

    /** Fiches de ce fournisseur appariées avant l'ajout des genres : identifiant de la fiche → animé (50 au plus). */
    public Map<String, Long> genresToBackfill(String provider) throws SQLException {
        Map<String, Long> batch = new LinkedHashMap<>();
        db.list("""
                SELECT id, metadata_provider_id FROM anime
                WHERE metadata_provider = ? AND metadata_provider_id IS NOT NULL AND genres_fetched_at IS NULL
                ORDER BY id LIMIT 50""", rs -> batch.put(rs.getString(2), rs.getLong(1)), provider);
        return batch;
    }

    /** Erreur propre à cet animé : nouvel essai plus tard, puis abandon en « non apparié » après {@code maxAttempts}. */
    public void recordError(long animeId, String provider, String error, int maxAttempts, OffsetDateTime now) throws SQLException {
        db.update("""
                INSERT INTO anime_metadata_match (anime_id, status, provider, attempts, next_attempt_at, last_error, updated_by)
                VALUES (?, 'PENDING', ?, 1, ?, ?, 'auto')
                ON CONFLICT (anime_id) DO UPDATE SET attempts = anime_metadata_match.attempts + 1,
                    status = CASE WHEN anime_metadata_match.attempts + 1 >= ? THEN 'UNMATCHED' ELSE 'PENDING' END,
                    reason = CASE WHEN anime_metadata_match.attempts + 1 >= ? THEN 'ERROR' ELSE NULL END,
                    next_attempt_at = ? + make_interval(mins => LEAST(1440, power(2, anime_metadata_match.attempts + 1)::int)),
                    last_error = EXCLUDED.last_error, updated_at = now(), updated_by = 'auto'
                WHERE NOT anime_metadata_match.locked""",
                animeId, provider, now.plusMinutes(2), error, maxAttempts, maxAttempts, now);
    }

    // --- Administration -------------------------------------------------------------------------------------------

    /** Nombre d'animés par statut d'appariement (sans état : PENDING). */
    public Map<String, Long> statusCounts() throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        db.list("""
                SELECT coalesce(m.status, 'PENDING'), count(*) FROM anime a
                LEFT JOIN anime_metadata_match m ON m.anime_id = a.id GROUP BY 1""",
                rs -> counts.put(rs.getString(1), rs.getLong(2)));
        return counts;
    }

    private static final String ENTRY_SELECT = """
            SELECT a.id, a.title, coalesce(m.status, 'PENDING'), m.reason, m.score, coalesce(m.locked, FALSE),
                   m.provider_id, a.year, a.poster_url, a.metadata_url, m.candidates::text, m.last_error,
                   m.updated_at, m.updated_by, a.alternative_title
            FROM anime a LEFT JOIN anime_metadata_match m ON m.anime_id = a.id""";

    private static final String ENTRY_FILTER = " WHERE (?::text IS NULL OR coalesce(m.status, 'PENDING') = ?)"
            + " AND (?::text IS NULL OR lower(unaccent(a.title)) LIKE lower(unaccent(?)) ESCAPE '!')";

    /** {@code titleLike} : motif LIKE (échappement « ! »), sans accents ni casse ; filtres null : ignorés. */
    public long countEntries(String status, String titleLike) throws SQLException {
        return db.one("SELECT count(*) FROM anime a LEFT JOIN anime_metadata_match m ON m.anime_id = a.id" + ENTRY_FILTER,
                rs -> rs.getLong(1), Jdbc.varchar(status), Jdbc.varchar(status), Jdbc.varchar(titleLike), Jdbc.varchar(titleLike))
                .orElseThrow();
    }

    public List<EntryRow> entries(String status, String titleLike, int size, long offset) throws SQLException {
        return db.list(ENTRY_SELECT + ENTRY_FILTER + " ORDER BY lower(a.title), a.id LIMIT ? OFFSET ?", MetadataRepository::entry,
                Jdbc.varchar(status), Jdbc.varchar(status), Jdbc.varchar(titleLike), Jdbc.varchar(titleLike), size, offset);
    }

    public Optional<EntryRow> entry(long animeId) throws SQLException {
        return db.one(ENTRY_SELECT + " WHERE a.id = ?", MetadataRepository::entry, animeId);
    }

    /** Fiche appliquée à l'animé, ligne de l'animé verrouillée ; vide si l'animé n'a pas de fiche. */
    public Optional<CurrentSheet> currentSheetForUpdate(Connection c, long animeId) throws SQLException {
        return Jdbc.one(c, """
                SELECT a.metadata_provider_id, a.year, a.poster_url, a.metadata_url, a.synopsis, m.candidates::text,
                       a.alternative_title, a.title
                FROM anime a LEFT JOIN anime_metadata_match m ON m.anime_id = a.id WHERE a.id = ? FOR UPDATE OF a""",
                rs -> rs.getString(1) == null ? null : new CurrentSheet(rs.getString(1), (Integer) rs.getObject(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)),
                animeId);
    }

    /** Autres animés qui ont cette fiche. */
    public List<AnimeRef> othersUsing(Connection c, long animeId, String providerId) throws SQLException {
        return Jdbc.list(c, "SELECT id, title FROM anime WHERE metadata_provider_id = ? AND id <> ? ORDER BY lower(title)",
                rs -> new AnimeRef(rs.getLong(1), rs.getString(2)), providerId, animeId);
    }

    /** Candidats gardés (JSON) de l'animé, s'il a un état d'appariement. */
    public Optional<String> candidatesJson(Connection c, long animeId) throws SQLException {
        return Jdbc.one(c, "SELECT candidates::text FROM anime_metadata_match WHERE anime_id = ?",
                rs -> Optional.ofNullable(rs.getString(1)), animeId).flatMap(o -> o);
    }

    /** Appariement manuel verrouillé ({@code providerId} null : « aucune fiche »). */
    public void saveManual(Connection c, long animeId, String provider, String providerId, String candidatesJson,
                           String updatedBy) throws SQLException {
        Jdbc.update(c, """
                INSERT INTO anime_metadata_match (anime_id, status, provider, provider_id, score, reason, candidates,
                                                  locked, attempts, next_attempt_at, last_error, updated_at, updated_by)
                VALUES (?, 'MANUAL', ?, ?, NULL, NULL, ?::jsonb, TRUE, 0, NULL, NULL, now(), ?)
                ON CONFLICT (anime_id) DO UPDATE SET status = 'MANUAL', provider = EXCLUDED.provider,
                    provider_id = EXCLUDED.provider_id, score = NULL, reason = NULL,
                    candidates = coalesce(EXCLUDED.candidates, anime_metadata_match.candidates), locked = TRUE,
                    attempts = 0, next_attempt_at = NULL, last_error = NULL, updated_at = now(),
                    updated_by = EXCLUDED.updated_by""",
                animeId, provider, Jdbc.varchar(providerId), Jdbc.varchar(candidatesJson), updatedBy);
    }

    /** Oublie la fiche TMDB trouvée automatiquement (une fiche choisie à la main est gardée). */
    public void deleteAutomaticTmdb(Connection c, long animeId) throws SQLException {
        Jdbc.update(c, "DELETE FROM anime_tmdb WHERE anime_id = ? AND NOT locked", animeId);
    }

    /** Retire le verrou manuel ; 0 si l'animé n'était pas verrouillé. */
    public int unlock(long animeId, String updatedBy) throws SQLException {
        return db.update("""
                UPDATE anime_metadata_match SET locked = FALSE, status = 'PENDING', attempts = 0, next_attempt_at = NULL,
                    updated_at = now(), updated_by = ? WHERE anime_id = ? AND locked""", updatedBy, animeId);
    }

    /** Remet « à faire » les animés non verrouillés de ce statut. */
    public int requeue(String status, String updatedBy) throws SQLException {
        return db.update("""
                UPDATE anime_metadata_match SET status = 'PENDING', attempts = 0, next_attempt_at = NULL,
                    updated_at = now(), updated_by = ? WHERE status = ? AND NOT locked""", updatedBy, status);
    }

    private static EntryRow entry(ResultSet rs) throws SQLException {
        return new EntryRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5),
                rs.getBoolean(6), rs.getString(7), (Integer) rs.getObject(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), Jdbc.instant(rs, 13), rs.getString(14), rs.getString(15));
    }
}
