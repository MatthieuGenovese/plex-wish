package fr.plexwish.animeserver.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AniList (API GraphQL publique, sans clé : https://docs.anilist.co). Fournit titres romaji / anglais / natif,
 * synonymes, format, nombre d'épisodes, année, synopsis (en anglais) et affiches (CDN s4.anilist.co).
 * Limite de débit : 429 + Retry-After ; indisponibilité (panne annoncée) : 403. Dans les deux cas, on réessaie plus tard.
 */
@ApplicationScoped
public class AniListProvider implements MetadataProvider {

    private static final Logger LOG = Logger.getLogger(AniListProvider.class);

    private static final String FIELDS = """
            id title { romaji english native } synonyms format episodes seasonYear startDate { year }
            description(asHtml: false) coverImage { large extraLarge } siteUrl""";
    static final String SEARCH = "query ($search: String) { Page(page: 1, perPage: 10) { media(search: $search, type: ANIME, sort: SEARCH_MATCH) { "
            + FIELDS + " } } }";
    static final String BY_ID = "query ($id: Int) { Media(id: $id, type: ANIME) { " + FIELDS + " } }";

    private final URI endpoint;
    private final ObjectMapper json;
    private final RateLimiter limiter;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault())
            .build();

    @Inject
    public AniListProvider(MetadataConfig config, ObjectMapper json) {
        this(URI.create(config.anilistUrl()), json,
                new RateLimiter(config.minInterval(), Clock.systemUTC(), d -> Thread.sleep(d.toMillis())), Clock.systemUTC());
    }

    AniListProvider(URI endpoint, ObjectMapper json, RateLimiter limiter, Clock clock) {
        this.endpoint = endpoint;
        this.json = json;
        this.limiter = limiter;
        this.clock = clock;
    }

    @Override
    public String id() {
        return "ANILIST";
    }

    @Override
    public String displayName() {
        return "AniList";
    }

    @Override
    public String synopsisLanguage() {
        return "en";
    }

    public RateLimiter limiter() {
        return limiter;
    }

    @Override
    public List<Candidate> search(String title) throws ProviderUnavailableException {
        JsonNode data = call(SEARCH, Map.of("search", title));
        List<Candidate> out = new ArrayList<>();
        if (data != null) {
            for (JsonNode m : data.path("Page").path("media")) {
                out.add(candidate(m));
            }
        }
        return out;
    }

    @Override
    public Optional<Candidate> byId(String providerId) throws ProviderUnavailableException {
        int id;
        try {
            id = Integer.parseInt(providerId);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        JsonNode data = call(BY_ID, Map.of("id", id));
        return data == null || data.path("Media").isMissingNode() || data.path("Media").isNull()
                ? Optional.empty() : Optional.of(candidate(data.path("Media")));
    }

    /** Un appel GraphQL ; null si la fiche n'existe pas (404). */
    private JsonNode call(String query, Map<String, Object> variables) throws ProviderUnavailableException {
        HttpResponse<String> response;
        try {
            limiter.acquire();
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(Map.of("query", query, "variables", variables)), StandardCharsets.UTF_8))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("interrompu", null);
        } catch (IOException e) {
            throw new ProviderUnavailableException("AniList injoignable : " + e.getClass().getSimpleName(), null);
        }
        respectQuota(response);
        int status = response.statusCode();
        if (status == 429) {
            Duration wait = retryAfter(response).orElse(Duration.ofMinutes(1));
            limiter.pauseUntil(clock.instant().plus(wait));
            throw new ProviderUnavailableException("AniList : limite de débit atteinte", wait);
        }
        if (status == 404) {
            return null;
        }
        if (status != 200) {
            // 403 = API suspendue (panne annoncée par AniList), 5xx = panne : on réessaiera plus tard.
            throw new ProviderUnavailableException("AniList : HTTP " + status, retryAfter(response).orElse(null));
        }
        try {
            JsonNode body = json.readTree(response.body());
            return body.path("data");
        } catch (IOException e) {
            throw new ProviderUnavailableException("AniList : réponse illisible", null);
        }
    }

    /** Quota épuisé (X-RateLimit-Remaining = 0) : pause jusqu'à X-RateLimit-Reset, ou une minute. */
    private void respectQuota(HttpResponse<String> response) {
        Optional<String> remaining = response.headers().firstValue("X-RateLimit-Remaining");
        if (remaining.isPresent() && remaining.get().trim().equals("0")) {
            Instant until = response.headers().firstValue("X-RateLimit-Reset")
                    .flatMap(AniListProvider::parseLong).map(Instant::ofEpochSecond)
                    .orElse(clock.instant().plus(Duration.ofMinutes(1)));
            LOG.infof("AniList : quota épuisé, pause jusqu'à %s", until);
            limiter.pauseUntil(until);
        }
    }

    private static Optional<Duration> retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After").flatMap(AniListProvider::parseLong)
                .map(s -> Duration.ofSeconds(Math.max(1, s)));
    }

    private static Optional<Long> parseLong(String s) {
        try {
            return Optional.of(Long.parseLong(s.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Candidate candidate(JsonNode m) {
        List<String> synonyms = new ArrayList<>();
        m.path("synonyms").forEach(s -> synonyms.add(s.asText()));
        // Integer partout : un ternaire « int : null » déballerait null (NullPointerException sur les fiches sans année).
        Integer year = integer(m.path("seasonYear"));
        if (year == null) {
            year = integer(m.path("startDate").path("year"));
        }
        return new Candidate(m.path("id").asText(), text(m.path("title").path("romaji")), text(m.path("title").path("english")),
                text(m.path("title").path("native")), synonyms, year, text(m.path("format")),
                integer(m.path("episodes")),
                cleanSynopsis(text(m.path("description"))),
                text(m.path("coverImage").path("large")), text(m.path("coverImage").path("extraLarge")), text(m.path("siteUrl")));
    }

    private static Integer integer(JsonNode n) {
        return n != null && n.isInt() ? Integer.valueOf(n.asInt()) : null;
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() || n.asText().isBlank() ? null : n.asText();
    }

    /** Les synopsis AniList contiennent un peu de HTML (<br>, <i>) : texte brut, paragraphes conservés. */
    static String cleanSynopsis(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("\r", "").replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
        return s.isEmpty() ? null : s;
    }
}
