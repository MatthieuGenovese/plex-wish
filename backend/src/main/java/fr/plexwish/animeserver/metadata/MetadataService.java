package fr.plexwish.animeserver.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.metadata.MetadataProvider.Candidate;
import fr.plexwish.animeserver.metadata.MetadataProvider.ProviderUnavailableException;
import fr.plexwish.animeserver.metadata.TitleMatcher.Decision;
import fr.plexwish.animeserver.metadata.TitleMatcher.Scored;
import fr.plexwish.animeserver.metadata.TitleMatcher.Status;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Récupération des métadonnées, un animé à la fois (ARCHITECTURE §15). Tout l'état est en base
 * ({@code anime_metadata_match}) : la tâche reprend où elle en était après un arrêt, et un animé déjà traité
 * n'est jamais redemandé (idempotence). Une fiche verrouillée (correction manuelle) n'est jamais modifiée ici.
 * La bibliothèque, le scan et la lecture n'en dépendent jamais.
 */
@ApplicationScoped
public class MetadataService {

    private static final Logger LOG = Logger.getLogger(MetadataService.class);

    /** Résultat d'un pas de la tâche de fond. */
    public sealed interface Step permits Done, Idle, Unavailable {
    }

    public record Done(long animeId, String status) implements Step {
    }

    public record Idle() implements Step {
    }

    public record Unavailable(String message, Duration retryAfter) implements Step {
    }

    @Inject
    AgroalDataSource dataSource;
    @Inject
    MetadataProvider provider;
    @Inject
    MetadataConfig config;
    @Inject
    ObjectMapper json;

    Clock clock = Clock.systemUTC();

    /** Dernière indisponibilité du fournisseur (affichage admin). */
    private volatile String lastUnavailable;
    private volatile Instant pausedUntil;

    public Optional<String> lastUnavailable() {
        return Optional.ofNullable(lastUnavailable);
    }

    public Optional<Instant> pausedUntil() {
        Instant p = pausedUntil;
        return p != null && p.isAfter(clock.instant()) ? Optional.of(p) : Optional.empty();
    }

    /** Traite l'animé suivant à apparier, s'il y en a un. Ne lève jamais d'exception pour un problème réseau. */
    public Step processNext() throws SQLException {
        Optional<Long> next = nextPending();
        if (next.isEmpty()) {
            return new Idle();
        }
        long animeId = next.get();
        try {
            String status = process(animeId);
            lastUnavailable = null;
            return new Done(animeId, status);
        } catch (ProviderUnavailableException e) {
            // Pas la faute de cet animé : il reste « à faire », la tâche entière se met en pause.
            Duration wait = e.retryAfter().orElse(config.unavailablePause());
            lastUnavailable = e.getMessage();
            pausedUntil = clock.instant().plus(wait);
            LOG.infof("Métadonnées : %s, nouvel essai dans %d s", e.getMessage(), wait.toSeconds());
            return new Unavailable(e.getMessage(), wait);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Métadonnées : échec pour l'animé %d", animeId);
            recordError(animeId, e.toString());
            return new Done(animeId, "ERROR");
        }
    }

    /** Animés sans état, ou à refaire et dont l'heure est venue ; les plus anciens d'abord. */
    Optional<Long> nextPending() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT a.id FROM anime a LEFT JOIN anime_metadata_match m ON m.anime_id = a.id
                     WHERE m.anime_id IS NULL
                        OR (m.status = 'PENDING' AND NOT m.locked AND (m.next_attempt_at IS NULL OR m.next_attempt_at <= ?))
                     ORDER BY m.next_attempt_at NULLS FIRST, a.id LIMIT 1""")) {
            st.setObject(1, OffsetDateTime.now(clock));
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    /** Recherche, décision, enregistrement. Renvoie le statut enregistré. */
    String process(long animeId) throws SQLException, ProviderUnavailableException {
        String folderTitle;
        Integer localEpisodes;
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT a.title, (SELECT count(*) FROM episode e JOIN season s ON s.id = e.season_id
                                      WHERE s.anime_id = a.id AND s.season_number > 0)
                     FROM anime a WHERE a.id = ?""")) {
            st.setLong(1, animeId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return "GONE";
                }
                folderTitle = rs.getString(1);
                localEpisodes = rs.getInt(2);
            }
        }
        TitleMatcher.Query query = TitleMatcher.clean(folderTitle);
        Map<String, Candidate> found = new LinkedHashMap<>();
        provider.search(query.main()).forEach(cand -> found.putIfAbsent(cand.providerId(), cand));
        Decision decision = TitleMatcher.decide(query, List.copyOf(found.values()), localEpisodes);
        // Titre alternatif entre parenthèses : seconde recherche seulement si la première ne suffit pas.
        for (String variant : query.variants().subList(1, query.variants().size())) {
            if (decision.status() == Status.MATCHED) {
                break;
            }
            provider.search(variant).forEach(cand -> found.putIfAbsent(cand.providerId(), cand));
            decision = TitleMatcher.decide(query, List.copyOf(found.values()), localEpisodes);
        }
        saveAutomatic(animeId, folderTitle, decision);
        return decision.status().name();
    }

    /** Enregistre une décision automatique, sauf si une correction manuelle a verrouillé l'animé entre-temps. */
    private void saveAutomatic(long animeId, String folderTitle, Decision d) throws SQLException {
        Scored best = d.best();
        Scored top = best != null ? best : d.ranked().isEmpty() ? null : d.ranked().get(0);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                boolean written;
                try (PreparedStatement st = c.prepareStatement("""
                        INSERT INTO anime_metadata_match (anime_id, status, provider, provider_id, score, reason, candidates,
                                                          locked, attempts, next_attempt_at, last_error, updated_at, updated_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, FALSE, 0, NULL, NULL, now(), 'auto')
                        ON CONFLICT (anime_id) DO UPDATE SET status = EXCLUDED.status, provider = EXCLUDED.provider,
                            provider_id = EXCLUDED.provider_id, score = EXCLUDED.score, reason = EXCLUDED.reason,
                            candidates = EXCLUDED.candidates, attempts = 0, next_attempt_at = NULL, last_error = NULL,
                            updated_at = now(), updated_by = 'auto'
                        WHERE NOT anime_metadata_match.locked
                        RETURNING anime_id""")) {
                    st.setLong(1, animeId);
                    st.setString(2, d.status().name());
                    st.setString(3, provider.id());
                    st.setString(4, best == null ? null : best.candidate().providerId());
                    st.setObject(5, top == null ? null : round(top.titleScore()));
                    st.setString(6, d.reason() == null ? null : d.reason().name());
                    st.setString(7, candidatesJson(d.ranked()));
                    try (ResultSet rs = st.executeQuery()) {
                        written = rs.next();
                    }
                }
                if (written) {
                    applyToAnime(c, animeId, folderTitle, best == null ? null : best.candidate());
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Recopie la fiche dans l'animé (ou l'efface si {@code candidate} est null). Le titre du dossier ne change jamais. */
    void applyToAnime(Connection c, long animeId, String folderTitle, Candidate candidate) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("""
                UPDATE anime SET alternative_title = ?, synopsis = ?, synopsis_language = ?, poster_url = ?,
                    poster_large_url = ?, year = ?, metadata_provider = ?, metadata_provider_id = ?, metadata_url = ?
                WHERE id = ?""")) {
            st.setString(1, candidate == null ? null : alternativeTitle(folderTitle, candidate));
            st.setString(2, candidate == null ? null : candidate.synopsis());
            st.setString(3, candidate == null || candidate.synopsis() == null ? null : provider.synopsisLanguage());
            st.setString(4, candidate == null ? null : candidate.posterUrl());
            st.setString(5, candidate == null ? null : candidate.posterLargeUrl());
            st.setObject(6, candidate == null ? null : candidate.year());
            st.setString(7, candidate == null ? null : provider.id());
            st.setString(8, candidate == null ? null : candidate.providerId());
            st.setString(9, candidate == null ? null : candidate.siteUrl());
            st.setLong(10, animeId);
            st.executeUpdate();
        }
    }

    /** Erreur propre à cet animé (réponse inattendue…) : nouvel essai plus tard, puis abandon en « non apparié ». */
    void recordError(long animeId, String error) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     INSERT INTO anime_metadata_match (anime_id, status, provider, attempts, next_attempt_at, last_error, updated_by)
                     VALUES (?, 'PENDING', ?, 1, ?, ?, 'auto')
                     ON CONFLICT (anime_id) DO UPDATE SET attempts = anime_metadata_match.attempts + 1,
                         status = CASE WHEN anime_metadata_match.attempts + 1 >= ? THEN 'UNMATCHED' ELSE 'PENDING' END,
                         reason = CASE WHEN anime_metadata_match.attempts + 1 >= ? THEN 'ERROR' ELSE NULL END,
                         next_attempt_at = ? + make_interval(mins => LEAST(1440, power(2, anime_metadata_match.attempts + 1)::int)),
                         last_error = EXCLUDED.last_error, updated_at = now(), updated_by = 'auto'
                     WHERE NOT anime_metadata_match.locked""")) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            st.setLong(1, animeId);
            st.setString(2, provider.id());
            st.setObject(3, now.plusMinutes(2));
            st.setString(4, error.length() > 500 ? error.substring(0, 500) : error);
            st.setInt(5, config.maxAttempts());
            st.setInt(6, config.maxAttempts());
            st.setObject(7, now);
            st.executeUpdate();
        }
    }

    /** Titre affiché en second : l'anglais (ou le romaji) s'il diffère vraiment du nom du dossier. */
    static String alternativeTitle(String folderTitle, Candidate c) {
        String folder = TitleMatcher.normalize(folderTitle);
        for (String t : new String[]{c.english(), c.romaji()}) {
            if (t != null && !TitleMatcher.normalize(t).equals(folder)) {
                return t;
            }
        }
        return null;
    }

    String candidatesJson(List<Scored> ranked) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Scored s : ranked.subList(0, Math.min(5, ranked.size()))) {
            Map<String, Object> m = candidateMap(s.candidate());
            m.put("score", round(s.titleScore()));
            list.add(m);
        }
        try {
            return json.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> candidateMap(Candidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("providerId", c.providerId());
        m.put("title", c.displayTitle());
        m.put("romaji", c.romaji());
        m.put("year", c.year());
        m.put("format", c.format());
        m.put("episodes", c.episodes());
        m.put("posterUrl", c.posterUrl());
        m.put("siteUrl", c.siteUrl());
        return m;
    }

    static double round(double score) {
        return Math.round(Math.min(1, score) * 1000) / 1000.0;
    }

}
