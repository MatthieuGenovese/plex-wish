package fr.plexwish.animeserver.tmdb;

import fr.plexwish.animeserver.common.db.Db;
import fr.plexwish.animeserver.common.db.Jdbc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Fiches TMDB des animés ({@code anime_tmdb}) : appariement, champs récupérés (6 mois au plus), corrections. */
@ApplicationScoped
public class TmdbRepository {

    /** Champs récupérés chez TMDB ; tout à null : rien de conservé. */
    public record Fields(String language, String title, String synopsis, String posterPath, OffsetDateTime fetchedAt) {
        static final Fields NONE = new Fields(null, null, null, null, null);
    }

    /** Ce que l'on sait de l'animé pour l'apparier ({@code candidatesJson} : candidats AniList gardés). */
    public record KnownRow(String folderTitle, Integer year, String providerId, String candidatesJson, String alternativeTitle,
                           int localEpisodes) {
    }

    /** Fiche TMDB : type (tv ou movie) et identifiant. */
    public record Reference(String type, long id) {
    }

    /** Fiche TMDB appliquée (titre et synopsis récupérés). */
    public record CurrentSheet(String type, long id, String title, String synopsis) {
    }

    /** Ligne de l'administration ({@code candidatesJson} : candidats gardés, JSON). */
    public record EntryRow(long animeId, String title, String status, String reason, BigDecimal score, boolean locked,
                           String tmdbType, Long tmdbId, String tmdbTitle, boolean hasFrenchSynopsis, Instant fetchedAt,
                           String candidatesJson, String lastError, Instant updatedAt, String updatedBy) {
    }

    @Inject
    Db db;

    // --- Tâche automatique -------------------------------------------------------------------------------------

    /**
     * Conditions de l'API TMDB : champs récupérés effacés après {@code maxAgeSeconds}, noms des candidats aussi.
     * Renvoie le nombre de fiches dont les champs ont été effacés.
     */
    public int purgeExpired(long maxAgeSeconds) throws SQLException {
        return db.withConnection(c -> {
            Jdbc.update(c, """
                    UPDATE anime_tmdb SET candidates = NULL
                    WHERE candidates IS NOT NULL AND updated_at < now() - make_interval(secs => ?)""", maxAgeSeconds);
            return Jdbc.update(c, """
                    UPDATE anime_tmdb SET title = NULL, synopsis = NULL, poster_path = NULL, language = NULL, fetched_at = NULL
                    WHERE fetched_at < now() - make_interval(secs => ?)""", maxAgeSeconds);
        });
    }

    /**
     * Prochain animé à apparier. Sauf si {@code metadataDisabled}, un animé encore en attente chez AniList attend :
     * ses titres AniList rendent l'appariement plus sûr.
     */
    public Optional<Long> nextToMatch(boolean metadataDisabled) throws SQLException {
        return db.one("""
                SELECT a.id FROM anime a LEFT JOIN anime_tmdb t ON t.anime_id = a.id
                WHERE (t.anime_id IS NULL OR (t.status = 'PENDING' AND NOT t.locked AND (t.next_attempt_at IS NULL OR t.next_attempt_at <= now())))
                  AND (%s OR EXISTS (SELECT 1 FROM anime_metadata_match m WHERE m.anime_id = a.id AND m.status <> 'PENDING'))
                ORDER BY t.next_attempt_at NULLS FIRST, a.id LIMIT 1""".formatted(metadataDisabled), rs -> rs.getLong(1));
    }

    /** Prochaine fiche récupérée depuis plus de {@code refreshAfterSeconds} (ou jamais). */
    public Optional<Long> nextToRefresh(long refreshAfterSeconds) throws SQLException {
        return db.one("""
                SELECT anime_id FROM anime_tmdb WHERE tmdb_id IS NOT NULL
                  AND (fetched_at IS NULL OR fetched_at < now() - make_interval(secs => %d))
                  AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                ORDER BY fetched_at NULLS FIRST, anime_id LIMIT 1""".formatted(refreshAfterSeconds), rs -> rs.getLong(1));
    }

    public Optional<KnownRow> known(long animeId) throws SQLException {
        return db.one("""
                SELECT a.title, a.year, m.provider_id, m.candidates::text, a.alternative_title,
                       (SELECT count(*) FROM episode e JOIN season s ON s.id = e.season_id WHERE s.anime_id = a.id)
                FROM anime a LEFT JOIN anime_metadata_match m ON m.anime_id = a.id WHERE a.id = ?""",
                rs -> new KnownRow(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getInt(6)),
                animeId);
    }

    /** Décision automatique, sauf si une correction manuelle a verrouillé l'animé. */
    public void saveAutomatic(long animeId, String status, String tmdbType, Long tmdbId, Double score, String reason,
                              String candidatesJson, Fields f) throws SQLException {
        db.update("""
                INSERT INTO anime_tmdb (anime_id, status, tmdb_type, tmdb_id, score, reason, candidates, locked, attempts,
                                        next_attempt_at, last_error, language, title, synopsis, poster_path, fetched_at,
                                        updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, FALSE, 0, NULL, NULL, ?, ?, ?, ?, ?, now(), 'auto')
                ON CONFLICT (anime_id) DO UPDATE SET status = EXCLUDED.status, tmdb_type = EXCLUDED.tmdb_type,
                    tmdb_id = EXCLUDED.tmdb_id, score = EXCLUDED.score, reason = EXCLUDED.reason,
                    candidates = EXCLUDED.candidates, attempts = 0, next_attempt_at = NULL, last_error = NULL,
                    language = EXCLUDED.language, title = EXCLUDED.title, synopsis = EXCLUDED.synopsis,
                    poster_path = EXCLUDED.poster_path, fetched_at = EXCLUDED.fetched_at, updated_at = now(),
                    updated_by = 'auto'
                WHERE NOT anime_tmdb.locked""",
                animeId, status, Jdbc.varchar(tmdbType), tmdbId, score, Jdbc.varchar(reason), candidatesJson,
                Jdbc.varchar(f.language()), Jdbc.varchar(f.title()), Jdbc.varchar(f.synopsis()), Jdbc.varchar(f.posterPath()),
                f.fetchedAt());
    }

    /** Fiche TMDB de l'animé (l'animé doit en avoir une). */
    public Reference reference(long animeId) throws SQLException {
        return db.one("SELECT tmdb_type, tmdb_id FROM anime_tmdb WHERE anime_id = ?",
                rs -> new Reference(rs.getString(1), rs.getLong(2)), animeId).orElseThrow();
    }

    /** Champs redemandés ({@code nextAttemptAt} : fiche supprimée chez TMDB, à redemander plus tard). */
    public void refreshFields(long animeId, Reference ref, Fields f, OffsetDateTime nextAttemptAt) throws SQLException {
        db.update("""
                UPDATE anime_tmdb SET language = ?, title = ?, synopsis = ?, poster_path = ?, fetched_at = ?,
                    next_attempt_at = ? WHERE anime_id = ? AND tmdb_type = ? AND tmdb_id = ?""",
                Jdbc.varchar(f.language()), Jdbc.varchar(f.title()), Jdbc.varchar(f.synopsis()), Jdbc.varchar(f.posterPath()),
                f.fetchedAt(), nextAttemptAt, animeId, ref.type(), ref.id());
    }

    /** Erreur propre à cet animé : nouvel essai plus tard, puis abandon (sans fiche) après 5 essais. */
    public void postpone(long animeId, String error) throws SQLException {
        db.update("""
                INSERT INTO anime_tmdb (anime_id, status, attempts, next_attempt_at, last_error, updated_by)
                VALUES (?, 'PENDING', 1, now() + interval '10 minutes', ?, 'auto')
                ON CONFLICT (anime_id) DO UPDATE SET attempts = anime_tmdb.attempts + 1,
                    status = CASE WHEN anime_tmdb.attempts + 1 >= 5 AND anime_tmdb.tmdb_id IS NULL THEN 'UNMATCHED' ELSE anime_tmdb.status END,
                    reason = CASE WHEN anime_tmdb.attempts + 1 >= 5 AND anime_tmdb.tmdb_id IS NULL THEN 'ERROR' ELSE anime_tmdb.reason END,
                    next_attempt_at = now() + make_interval(mins => LEAST(1440, power(2, anime_tmdb.attempts + 1)::int)),
                    last_error = EXCLUDED.last_error, updated_at = now()""", animeId, error);
    }

    // --- Administration -------------------------------------------------------------------------------------------

    public Optional<String> titleOf(long animeId) throws SQLException {
        return db.one("SELECT title FROM anime WHERE id = ?", rs -> rs.getString(1), animeId);
    }

    /** Nombre d'animés par statut d'appariement TMDB (sans état : PENDING). */
    public Map<String, Long> statusCounts() throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        db.list("SELECT coalesce(t.status, 'PENDING'), count(*) FROM anime a"
                + " LEFT JOIN anime_tmdb t ON t.anime_id = a.id GROUP BY 1", rs -> counts.put(rs.getString(1), rs.getLong(2)));
        return counts;
    }

    public long countWithFrenchSynopsis() throws SQLException {
        return db.one("SELECT count(*) FROM anime_tmdb WHERE synopsis IS NOT NULL", rs -> rs.getLong(1)).orElseThrow();
    }

    public long countDueForRefresh(long refreshAfterSeconds) throws SQLException {
        return db.one("SELECT count(*) FROM anime_tmdb WHERE tmdb_id IS NOT NULL AND (fetched_at IS NULL"
                + " OR fetched_at < now() - make_interval(secs => " + refreshAfterSeconds + "))", rs -> rs.getLong(1)).orElseThrow();
    }

    private static final String ENTRY_SELECT = """
            SELECT a.id, a.title, coalesce(t.status, 'PENDING'), t.reason, t.score, coalesce(t.locked, FALSE), t.tmdb_type,
                   t.tmdb_id, t.title, t.synopsis IS NOT NULL, t.fetched_at, t.candidates::text, t.last_error, t.updated_at,
                   t.updated_by
            FROM anime a LEFT JOIN anime_tmdb t ON t.anime_id = a.id""";

    private static String entryFilter(boolean noFrench) {
        return " WHERE (?::text IS NULL OR coalesce(t.status, 'PENDING') = ?)"
                + " AND (?::text IS NULL OR lower(unaccent(a.title)) LIKE lower(unaccent(?)) ESCAPE '!')"
                + (noFrench ? " AND t.synopsis IS NULL" : "");
    }

    /** {@code titleLike} : motif LIKE (échappement « ! »), sans accents ni casse ; filtres null : ignorés. */
    public long countEntries(String status, String titleLike, boolean noFrench) throws SQLException {
        return db.one("SELECT count(*) FROM anime a LEFT JOIN anime_tmdb t ON t.anime_id = a.id" + entryFilter(noFrench),
                rs -> rs.getLong(1), Jdbc.varchar(status), Jdbc.varchar(status), Jdbc.varchar(titleLike), Jdbc.varchar(titleLike))
                .orElseThrow();
    }

    public List<EntryRow> entries(String status, String titleLike, boolean noFrench, int size, long offset) throws SQLException {
        return db.list(ENTRY_SELECT + entryFilter(noFrench) + " ORDER BY lower(a.title), a.id LIMIT ? OFFSET ?",
                TmdbRepository::entry, Jdbc.varchar(status), Jdbc.varchar(status), Jdbc.varchar(titleLike),
                Jdbc.varchar(titleLike), size, offset);
    }

    public Optional<EntryRow> entry(long animeId) throws SQLException {
        return db.one(ENTRY_SELECT + " WHERE a.id = ?", TmdbRepository::entry, animeId);
    }

    /** Fiche TMDB appliquée à l'animé, s'il en a une. */
    public Optional<CurrentSheet> currentSheet(long animeId) throws SQLException {
        return db.one("SELECT tmdb_type, tmdb_id, title, synopsis FROM anime_tmdb WHERE anime_id = ? AND tmdb_id IS NOT NULL",
                rs -> new CurrentSheet(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)), animeId);
    }

    /** Appariement manuel verrouillé ({@code tmdbType} et {@code tmdbId} null : « aucune fiche »). */
    public void saveManual(long animeId, String tmdbType, Long tmdbId, Fields f, String updatedBy) throws SQLException {
        db.update("""
                INSERT INTO anime_tmdb (anime_id, status, tmdb_type, tmdb_id, score, reason, candidates, locked, attempts,
                                        next_attempt_at, last_error, language, title, synopsis, poster_path, fetched_at,
                                        updated_at, updated_by)
                VALUES (?, 'MANUAL', ?, ?, NULL, NULL, NULL, TRUE, 0, NULL, NULL, ?, ?, ?, ?, ?, now(), ?)
                ON CONFLICT (anime_id) DO UPDATE SET status = 'MANUAL', tmdb_type = EXCLUDED.tmdb_type,
                    tmdb_id = EXCLUDED.tmdb_id, score = NULL, reason = NULL, locked = TRUE, attempts = 0,
                    next_attempt_at = NULL, last_error = NULL, language = EXCLUDED.language, title = EXCLUDED.title,
                    synopsis = EXCLUDED.synopsis, poster_path = EXCLUDED.poster_path, fetched_at = EXCLUDED.fetched_at,
                    updated_at = now(), updated_by = EXCLUDED.updated_by""",
                animeId, Jdbc.varchar(tmdbType), tmdbId, Jdbc.varchar(f.language()), Jdbc.varchar(f.title()),
                Jdbc.varchar(f.synopsis()), Jdbc.varchar(f.posterPath()), f.fetchedAt(), updatedBy);
    }

    /** Retire le verrou manuel ; 0 si l'animé n'était pas verrouillé. */
    public int unlock(long animeId, String updatedBy) throws SQLException {
        return db.update("UPDATE anime_tmdb SET locked = FALSE, status = 'PENDING', attempts = 0,"
                + " next_attempt_at = NULL, updated_at = now(), updated_by = ? WHERE anime_id = ? AND locked", updatedBy, animeId);
    }

    /** Remet « à faire » les animés non verrouillés de ce statut. */
    public int requeue(String status, String updatedBy) throws SQLException {
        return db.update("UPDATE anime_tmdb SET status = 'PENDING', attempts = 0, next_attempt_at = NULL,"
                + " updated_at = now(), updated_by = ? WHERE status = ? AND NOT locked", updatedBy, status);
    }

    /** Fin de licence : toutes les données TMDB, corrections manuelles comprises. */
    public int deleteAll() throws SQLException {
        return db.update("DELETE FROM anime_tmdb");
    }

    private static EntryRow entry(ResultSet rs) throws SQLException {
        return new EntryRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5),
                rs.getBoolean(6), rs.getString(7), (Long) rs.getObject(8), rs.getString(9), rs.getBoolean(10),
                Jdbc.instant(rs, 11), rs.getString(12), rs.getString(13), Jdbc.instant(rs, 14), rs.getString(15));
    }
}
