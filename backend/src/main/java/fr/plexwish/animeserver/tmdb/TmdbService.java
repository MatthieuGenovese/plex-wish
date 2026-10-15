package fr.plexwish.animeserver.tmdb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.metadata.MetadataProvider.ProviderUnavailableException;
import fr.plexwish.animeserver.metadata.TitleMatcher;
import fr.plexwish.animeserver.tmdb.TmdbClient.Result;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
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
    TmdbRepository repository;
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
        Optional<Long> pending = repository.nextToMatch(!metadataConfig.enabled());
        Optional<Long> stale = pending.isPresent() ? Optional.empty() : repository.nextToRefresh(config.refreshAfter().toSeconds());
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
        int n = repository.purgeExpired(config.maxAge().toSeconds());
        if (n > 0) {
            LOG.infof("TMDB : %d fiche(s) de plus de 6 mois effacée(s) (conditions de l'API TMDB)", n);
        }
        return n;
    }

    // --- Appariement -------------------------------------------------------------------------------

    /** Ce que l'on sait de l'animé : titres AniList (romaji, anglais, japonais), année, format, titre du dossier. */
    record Known(String folderTitle, List<String> titles, Integer year, String format, int localEpisodes) {
    }

    Known known(long animeId) throws SQLException, IOException {
        Optional<TmdbRepository.KnownRow> row = repository.known(animeId);
        if (row.isEmpty()) {
            return null;
        }
        TmdbRepository.KnownRow r = row.get();
        String folder = r.folderTitle();
        Integer year = r.year();
        String providerId = r.providerId();
        List<String> titles = new ArrayList<>();
        String format = null;
        if (providerId != null && r.candidatesJson() != null) {
            List<Map<String, Object>> candidates = json.readValue(r.candidatesJson(), new TypeReference<>() {
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
        if (r.alternativeTitle() != null) {
            titles.add(r.alternativeTitle());
        }
        TitleMatcher.Query folderQuery = TitleMatcher.clean(folder);
        titles.addAll(folderQuery.variants());
        if (year == null) {
            year = folderQuery.year();
        }
        return new Known(folder, titles, year, format, r.localEpisodes());
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
        repository.saveAutomatic(animeId, d.status().name(), chosen == null ? null : chosen.type(),
                chosen == null ? null : chosen.id(),
                top == null ? null : Math.round(Math.min(1, top.titleScore()) * 1000) / 1000.0,
                d.reason() == null ? null : d.reason().name(), candidatesJson(d.ranked()), fields(chosen));
    }

    /** Champs récupérés : langue, titre (seulement s'il est traduit), synopsis, affiche, date. */
    TmdbRepository.Fields fields(Result r) {
        if (r == null) {
            return TmdbRepository.Fields.NONE;
        }
        String title = r.name() != null && !r.name().equals(r.originalName()) ? r.name() : null;
        return new TmdbRepository.Fields(client.languageCode(), title, r.overview(), r.posterPath(), OffsetDateTime.now(clock));
    }

    /** Fiche de plus de 5 mois : on la redemande (sans refaire l'appariement), même verrouillée. */
    String refresh(long animeId) throws SQLException, ProviderUnavailableException {
        TmdbRepository.Reference ref = repository.reference(animeId);
        Optional<Result> r = client.details(ref.type(), ref.id());
        // Fiche supprimée chez TMDB : plus rien de conservé ; on ne la redemande que dans un mois.
        repository.refreshFields(animeId, ref, fields(r.orElse(null)), r.isPresent() ? null : OffsetDateTime.now(clock).plusDays(30));
        return "REFRESHED";
    }

    private void postpone(long animeId, String error) throws SQLException {
        repository.postpone(animeId, error.length() > 500 ? error.substring(0, 500) : error);
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
