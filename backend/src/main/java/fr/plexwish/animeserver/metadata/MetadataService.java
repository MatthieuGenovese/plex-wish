package fr.plexwish.animeserver.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.metadata.MetadataProvider.Candidate;
import fr.plexwish.animeserver.metadata.MetadataProvider.ProviderUnavailableException;
import fr.plexwish.animeserver.metadata.TitleMatcher.Decision;
import fr.plexwish.animeserver.metadata.TitleMatcher.Scored;
import fr.plexwish.animeserver.metadata.TitleMatcher.Status;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
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
    MetadataRepository repository;
    @Inject
    fr.plexwish.animeserver.common.db.Db db;
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
            // Une ligne par animé traité (le titre du dossier et le résultat, rien d'autre) : suivi dans les journaux.
            LOG.infof("Métadonnées : %s → %s", titleOf(animeId), status);
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

    private String titleOf(long animeId) {
        try {
            return repository.titleOf(animeId).orElse("animé " + animeId);
        } catch (SQLException e) {
            return "animé " + animeId;
        }
    }

    /** Animés sans état, ou à refaire et dont l'heure est venue ; les plus anciens d'abord. */
    public Optional<Long> nextPending() throws SQLException {
        return repository.nextPending(OffsetDateTime.now(clock));
    }

    /** Recherche, décision, enregistrement. Renvoie le statut enregistré. */
    String process(long animeId) throws SQLException, ProviderUnavailableException {
        Optional<MetadataRepository.AnimeToMatch> anime = repository.animeToMatch(animeId);
        if (anime.isEmpty()) {
            return "GONE";
        }
        String folderTitle = anime.get().folderTitle();
        Integer localEpisodes = anime.get().localEpisodes();
        TitleMatcher.Query query = TitleMatcher.clean(folderTitle);
        Map<String, Candidate> found = new LinkedHashMap<>();
        provider.search(query.main()).forEach(cand -> found.putIfAbsent(cand.providerId(), cand));
        Decision decision = TitleMatcher.decide(query, List.copyOf(found.values()), localEpisodes);
        // Recherches de repli, seulement si la première ne suffit pas : titre entre parenthèses, puis titre en
        // romanisation ASCII (la recherche AniList ne trouve rien pour « Chûnibyô » ou « Kyō »), puis titre avant « - ».
        for (String variant : TitleMatcher.fallbackSearches(query)) {
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
        db.runInTransaction(c -> {
            boolean written = repository.saveAutomatic(c, animeId, d.status().name(), provider.id(),
                    best == null ? null : best.candidate().providerId(), top == null ? null : round(top.titleScore()),
                    d.reason() == null ? null : d.reason().name(), candidatesJson(d.ranked()));
            if (written) {
                applyToAnime(c, animeId, folderTitle, best == null ? null : best.candidate());
            }
        });
    }

    /** Recopie la fiche dans l'animé (ou l'efface si {@code candidate} est null). Le titre du dossier ne change jamais. */
    void applyToAnime(Connection c, long animeId, String folderTitle, Candidate candidate) throws SQLException {
        repository.applyToAnime(c, animeId, candidate == null ? MetadataRepository.AnimeSheet.NONE
                : new MetadataRepository.AnimeSheet(alternativeTitle(folderTitle, candidate), candidate.synopsis(),
                candidate.synopsis() == null ? null : provider.synopsisLanguage(), candidate.posterUrl(),
                candidate.posterLargeUrl(), candidate.year(), provider.id(), candidate.providerId(), candidate.siteUrl()));
        saveGenres(c, animeId, candidate == null ? null : candidate.genres());
    }

    /** Remplace les genres d'un animé ; {@code genres} null = fiche retirée (genres effacés, à récupérer plus tard). */
    void saveGenres(Connection c, long animeId, List<String> genres) throws SQLException {
        repository.replaceGenres(c, animeId, genres, genres == null ? null : OffsetDateTime.now(clock));
    }

    /**
     * Rattrapage des genres pour les fiches appariées avant leur ajout (§24.6) : 50 fiches par requête AniList
     * (~26 requêtes pour 1 300 animés, même limiteur de débit). {@code Done} si un lot a été traité, {@code Idle}
     * s'il n'y a plus rien à faire.
     */
    public Step backfillGenres() throws SQLException {
        Map<String, Long> batch = repository.genresToBackfill(provider.id());
        if (batch.isEmpty()) {
            return new Idle();
        }
        Map<String, List<String>> found;
        try {
            found = provider.genres(List.copyOf(batch.keySet()));
        } catch (ProviderUnavailableException e) {
            Duration wait = e.retryAfter().orElse(config.unavailablePause());
            lastUnavailable = e.getMessage();
            pausedUntil = clock.instant().plus(wait);
            LOG.infof("Genres : %s, nouvel essai dans %d s", e.getMessage(), wait.toSeconds());
            return new Unavailable(e.getMessage(), wait);
        }
        db.runInTransaction(c -> {
            for (var e : batch.entrySet()) {
                // Fiche absente de la réponse (supprimée chez AniList) : aucun genre, mais plus redemandée.
                saveGenres(c, e.getValue(), found.getOrDefault(e.getKey(), List.of()));
            }
        });
        LOG.infof("Genres : %d fiche(s) complétée(s)", batch.size());
        return new Done(batch.values().iterator().next(), "GENRES");
    }

    /** Erreur propre à cet animé (réponse inattendue…) : nouvel essai plus tard, puis abandon en « non apparié ». */
    void recordError(long animeId, String error) throws SQLException {
        repository.recordError(animeId, provider.id(), error.length() > 500 ? error.substring(0, 500) : error,
                config.maxAttempts(), OffsetDateTime.now(clock));
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
        // Titres anglais et japonais : servent aussi à l'appariement TMDB (titre original japonais).
        m.put("english", c.english());
        m.put("nativeTitle", c.nativeTitle());
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
