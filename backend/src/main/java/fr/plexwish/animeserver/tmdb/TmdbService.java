package fr.plexwish.animeserver.tmdb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.metadata.MetadataProvider.ProviderUnavailableException;
import fr.plexwish.animeserver.metadata.TitleMatcher;
import fr.plexwish.animeserver.tmdb.TmdbClient.Result;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Synopsis et titre en français depuis TMDB (ARCHITECTURE §16), un animé à la fois, état en base (idempotent,
 * reprenable). Ordre de travail : effacer ce qui a dépassé 6 mois, apparier les animés nouveaux, rafraîchir les
 * fiches de plus de 5 mois. Une correction manuelle (locked) n'est jamais modifiée ici.
 */
@ApplicationScoped
public class TmdbService {

    private static final Logger LOG = Logger.getLogger(TmdbService.class);

    public sealed interface Step permits Done, Idle, Unavailable {
    }

    public record Done(long animeId, String what) implements Step {
    }

    public record Idle() implements Step {
    }

    public record Unavailable(String message, Duration retryAfter) implements Step {
    }

    @Inject
    AgroalDataSource dataSource;
    @Inject
    TmdbClient client;
    @Inject
    TmdbConfig config;
    @Inject
    ObjectMapper json;
    @Inject
    fr.plexwish.animeserver.metadata.MetadataConfig metadataConfig;

    Clock clock = Clock.systemUTC();
    private volatile String lastUnavailable;
    private volatile Instant pausedUntil;

    public Optional<String> lastUnavailable() {
        return Optional.ofNullable(lastUnavailable);
    }

    public Optional<Instant> pausedUntil() {
        Instant p = pausedUntil;
        return p != null && p.isAfter(Instant.now()) ? Optional.of(p) : Optional.empty();
    }

    public Step processNext() throws SQLException {
        purgeExpired();
        // Un animé encore en attente chez AniList attend : ses titres AniList rendent l'appariement plus sûr.
        Optional<Long> pending = next("""
                SELECT a.id FROM anime a LEFT JOIN anime_tmdb t ON t.anime_id = a.id
                WHERE (t.anime_id IS NULL OR (t.status = 'PENDING' AND NOT t.locked AND (t.next_attempt_at IS NULL OR t.next_attempt_at <= now())))
                  AND (%s OR EXISTS (SELECT 1 FROM anime_metadata_match m WHERE m.anime_id = a.id AND m.status <> 'PENDING'))
                ORDER BY t.next_attempt_at NULLS FIRST, a.id LIMIT 1""".formatted(!metadataConfig.enabled()));
        Optional<Long> stale = pending.isPresent() ? Optional.empty() : next("""
                SELECT anime_id FROM anime_tmdb WHERE tmdb_id IS NOT NULL
                  AND (fetched_at IS NULL OR fetched_at < now() - make_interval(secs => %d))
                  AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                ORDER BY fetched_at NULLS FIRST, anime_id LIMIT 1""".formatted(config.refreshAfter().toSeconds()));
        if (pending.isEmpty() && stale.isEmpty()) {
            return new Idle();
        }
        long animeId = pending.orElseGet(stale::get);
        try {
            String what = pending.isPresent() ? match(animeId) : refresh(animeId);
            lastUnavailable = null;
            return new Done(animeId, what);
        } catch (ProviderUnavailableException e) {
            Duration wait = e.retryAfter().orElse(config.unavailablePause());
            lastUnavailable = e.getMessage();
            pausedUntil = Instant.now().plus(wait);
            LOG.infof("TMDB : %s, nouvel essai dans %d s", e.getMessage(), wait.toSeconds());
            return new Unavailable(e.getMessage(), wait);
        } catch (RuntimeException e) {
            LOG.warnf(e, "TMDB : échec pour l'animé %d", animeId);
            postpone(animeId, e.toString());
            return new Done(animeId, "ERROR");
        }
    }

    /**
     * Conditions de l'API TMDB : rien de conservé au-delà de 6 mois (champs récupérés, et noms des candidats
     * gardés pour l'admin). Seuls l'identifiant TMDB et le statut restent.
     */
    int purgeExpired() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE anime_tmdb SET title = NULL, synopsis = NULL, poster_path = NULL, language = NULL, fetched_at = NULL
                     WHERE fetched_at < now() - make_interval(secs => ?)""");
             PreparedStatement cand = c.prepareStatement("""
                     UPDATE anime_tmdb SET candidates = NULL
                     WHERE candidates IS NOT NULL AND updated_at < now() - make_interval(secs => ?)""")) {
            st.setLong(1, config.maxAge().toSeconds());
            cand.setLong(1, config.maxAge().toSeconds());
            cand.executeUpdate();
            int n = st.executeUpdate();
            if (n > 0) {
                LOG.infof("TMDB : %d fiche(s) de plus de 6 mois effacée(s) (conditions de l'API TMDB)", n);
            }
            return n;
        }
    }

    // --- Appariement -------------------------------------------------------------------------------

    /** Ce que l'on sait de l'animé : titres AniList (romaji, anglais, japonais), année, format, titre du dossier. */
    record Known(String folderTitle, List<String> titles, Integer year, String format, int localEpisodes) {
    }

    Known known(long animeId) throws SQLException, IOException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT a.title, a.year, m.provider_id, m.candidates::text, a.alternative_title,
                            (SELECT count(*) FROM episode e JOIN season s ON s.id = e.season_id WHERE s.anime_id = a.id)
                     FROM anime a LEFT JOIN anime_metadata_match m ON m.anime_id = a.id WHERE a.id = ?""")) {
            st.setLong(1, animeId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String folder = rs.getString(1);
                Integer year = (Integer) rs.getObject(2);
                String providerId = rs.getString(3);
                List<String> titles = new ArrayList<>();
                String format = null;
                if (providerId != null && rs.getString(4) != null) {
                    List<Map<String, Object>> candidates = json.readValue(rs.getString(4), new TypeReference<>() {
                    });
                    for (Map<String, Object> m : candidates) {
                        if (providerId.equals(m.get("providerId"))) {
                            for (String k : new String[]{"nativeTitle", "romaji", "english", "title"}) {
                                if (m.get(k) instanceof String t) {
                                    titles.add(t);
                                }
                            }
                            format = (String) m.get("format");
                        }
                    }
                }
                if (rs.getString(5) != null) {
                    titles.add(rs.getString(5));
                }
                TitleMatcher.Query folderQuery = TitleMatcher.clean(folder);
                titles.addAll(folderQuery.variants());
                if (year == null) {
                    year = folderQuery.year();
                }
                return new Known(folder, titles, year, format, rs.getInt(6));
            }
        }
    }

    String match(long animeId) throws SQLException, ProviderUnavailableException {
        Known k;
        try {
            k = known(animeId);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        if (k == null) {
            return "GONE";
        }
        String type = "MOVIE".equals(k.format()) ? "movie" : "tv";
        TmdbMatcher.Query q = TmdbMatcher.query(k.titles(), k.year(), type);
        Map<String, Result> found = new LinkedHashMap<>();
        TmdbMatcher.Decision d = null;
        // Une recherche par titre connu, jusqu'à un appariement sûr (japonais d'abord : titre original sur TMDB).
        for (String title : searchTitles(q)) {
            for (Result r : client.search(type, title)) {
                found.putIfAbsent(r.type() + ":" + r.id(), r);
            }
            d = TmdbMatcher.decide(q, List.copyOf(found.values()));
            if (d.status() == TitleMatcher.Status.MATCHED) {
                break;
            }
        }
        if (d == null) {
            d = TmdbMatcher.decide(q, List.of());
        }
        // Film d'un seul fichier sans fiche AniList : on tente aussi les films.
        if (d.status() == TitleMatcher.Status.UNMATCHED && k.format() == null && k.localEpisodes() <= 1) {
            TmdbMatcher.Query mq = new TmdbMatcher.Query(q.titles(), q.year(), "movie", q.sequel());
            for (Result r : client.search("movie", searchTitles(mq).get(0))) {
                found.putIfAbsent(r.type() + ":" + r.id(), r);
            }
            d = TmdbMatcher.decide(mq, List.copyOf(found.values()));
        }
        Result chosen = null;
        if (d.best() != null) {
            chosen = client.details(d.best().result().type(), d.best().result().id()).orElse(d.best().result());
        }
        saveAutomatic(animeId, d, chosen);
        return d.status().name();
    }

    /** Titres à chercher, dans l'ordre, sans doublon (au plus 4 recherches). */
    static List<String> searchTitles(TmdbMatcher.Query q) {
        List<String> out = new ArrayList<>();
        for (String t : q.titles()) {
            String base = TmdbMatcher.withoutSeason(t);
            if (!base.isBlank() && out.stream().noneMatch(o -> TitleMatcher.normalize(o).equals(TitleMatcher.normalize(base)))) {
                out.add(base);
            }
        }
        return out.subList(0, Math.min(4, out.size()));
    }

    private void saveAutomatic(long animeId, TmdbMatcher.Decision d, Result chosen) throws SQLException {
        TmdbMatcher.Scored top = d.best() != null ? d.best() : d.ranked().isEmpty() ? null : d.ranked().get(0);
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
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
                     WHERE NOT anime_tmdb.locked""")) {
            st.setLong(1, animeId);
            st.setString(2, d.status().name());
            st.setString(3, chosen == null ? null : chosen.type());
            st.setObject(4, chosen == null ? null : chosen.id());
            st.setObject(5, top == null ? null : Math.round(Math.min(1, top.titleScore()) * 1000) / 1000.0);
            st.setString(6, d.reason() == null ? null : d.reason().name());
            st.setString(7, candidatesJson(d.ranked()));
            bindFields(st, 8, chosen);
            st.executeUpdate();
        }
    }

    /** Champs récupérés : langue, titre (seulement s'il est traduit), synopsis, affiche, date. */
    void bindFields(PreparedStatement st, int from, Result r) throws SQLException {
        boolean any = r != null;
        String title = any && r.name() != null && !r.name().equals(r.originalName()) ? r.name() : null;
        st.setString(from, any ? client.languageCode() : null);
        st.setString(from + 1, title);
        st.setString(from + 2, any ? r.overview() : null);
        st.setString(from + 3, any ? r.posterPath() : null);
        st.setObject(from + 4, any ? OffsetDateTime.now(clock) : null);
    }

    /** Fiche de plus de 5 mois : on la redemande (sans refaire l'appariement), même verrouillée. */
    String refresh(long animeId) throws SQLException, ProviderUnavailableException {
        String type;
        long id;
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT tmdb_type, tmdb_id FROM anime_tmdb WHERE anime_id = ?")) {
            st.setLong(1, animeId);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                type = rs.getString(1);
                id = rs.getLong(2);
            }
        }
        Optional<Result> r = client.details(type, id);
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     UPDATE anime_tmdb SET language = ?, title = ?, synopsis = ?, poster_path = ?, fetched_at = ?,
                         next_attempt_at = ? WHERE anime_id = ? AND tmdb_type = ? AND tmdb_id = ?""")) {
            bindFields(st, 1, r.orElse(null));
            // Fiche supprimée chez TMDB : plus rien de conservé ; on ne la redemande que dans un mois.
            st.setObject(6, r.isPresent() ? null : OffsetDateTime.now(clock).plusDays(30));
            st.setLong(7, animeId);
            st.setString(8, type);
            st.setLong(9, id);
            st.executeUpdate();
        }
        return "REFRESHED";
    }

    private void postpone(long animeId, String error) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     INSERT INTO anime_tmdb (anime_id, status, attempts, next_attempt_at, last_error, updated_by)
                     VALUES (?, 'PENDING', 1, now() + interval '10 minutes', ?, 'auto')
                     ON CONFLICT (anime_id) DO UPDATE SET attempts = anime_tmdb.attempts + 1,
                         status = CASE WHEN anime_tmdb.attempts + 1 >= 5 AND anime_tmdb.tmdb_id IS NULL THEN 'UNMATCHED' ELSE anime_tmdb.status END,
                         reason = CASE WHEN anime_tmdb.attempts + 1 >= 5 AND anime_tmdb.tmdb_id IS NULL THEN 'ERROR' ELSE anime_tmdb.reason END,
                         next_attempt_at = now() + make_interval(mins => LEAST(1440, power(2, anime_tmdb.attempts + 1)::int)),
                         last_error = EXCLUDED.last_error, updated_at = now()""")) {
            st.setLong(1, animeId);
            st.setString(2, error.length() > 500 ? error.substring(0, 500) : error);
            st.executeUpdate();
        }
    }

    private Optional<Long> next(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql);
             ResultSet rs = st.executeQuery()) {
            return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
        }
    }

    String candidatesJson(List<TmdbMatcher.Scored> ranked) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TmdbMatcher.Scored s : ranked.subList(0, Math.min(5, ranked.size()))) {
            Map<String, Object> m = candidateMap(s.result());
            m.put("score", Math.round(Math.min(1, s.titleScore()) * 1000) / 1000.0);
            list.add(m);
        }
        try {
            return json.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> candidateMap(Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", r.type());
        m.put("tmdbId", r.id());
        m.put("name", r.name());
        m.put("originalName", r.originalName());
        m.put("year", r.year());
        m.put("animation", r.animation());
        m.put("hasFrenchOverview", r.overview() != null);
        m.put("url", r.url());
        return m;
    }
}
