package fr.plexwish.animeserver.tmdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.metadata.MetadataProvider.ProviderUnavailableException;
import fr.plexwish.animeserver.metadata.RateLimiter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client de l'API TMDB v3 (recherche et fiche, en français). Les séries pour adultes sont exclues de la recherche
 * (include_adult=false). La clé n'apparaît jamais dans un message d'erreur ni dans un log.
 */
@ApplicationScoped
public class TmdbClient {

    /** Genre TMDB « Animation » : distingue l'animé de son adaptation en prises de vue réelles. */
    static final int ANIMATION_GENRE = 16;

    /** Un résultat TMDB (série ou film). */
    public record Result(String type, long id, String name, String originalName, Integer year, String overview,
                         String posterPath, String originalLanguage, List<Integer> genres) {
        public boolean animation() {
            return genres != null && genres.contains(ANIMATION_GENRE);
        }

        public String url() {
            return "https://www.themoviedb.org/" + type + "/" + id;
        }
    }

    private final TmdbConfig config;
    private final ObjectMapper json;
    private final RateLimiter limiter;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault()).followRedirects(HttpClient.Redirect.NEVER).build();

    @Inject
    public TmdbClient(TmdbConfig config, ObjectMapper json) {
        this(config, json, new RateLimiter(config.minInterval(), Clock.systemUTC(), d -> Thread.sleep(d.toMillis())),
                Clock.systemUTC());
    }

    TmdbClient(TmdbConfig config, ObjectMapper json, RateLimiter limiter, Clock clock) {
        this.config = config;
        this.json = json;
        this.limiter = limiter;
        this.clock = clock;
    }

    public List<Result> search(String type, String query) throws ProviderUnavailableException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("query", query);
        params.put("include_adult", "false");
        params.put("language", config.language());
        params.put("page", "1");
        JsonNode body = get("/search/" + type, params);
        List<Result> out = new ArrayList<>();
        if (body != null) {
            for (JsonNode r : body.path("results")) {
                if (!r.path("adult").asBoolean(false)) {
                    out.add(result(type, r));
                }
            }
        }
        return out;
    }

    public Optional<Result> details(String type, long id) throws ProviderUnavailableException {
        JsonNode body = get("/" + type + "/" + id, Map.of("language", config.language()));
        return body == null ? Optional.empty() : Optional.of(result(type, body));
    }

    /** Code de langue enregistré avec les champs (« fr » pour « fr-FR »). */
    public String languageCode() {
        String l = config.language();
        int dash = l.indexOf('-');
        return (dash > 0 ? l.substring(0, dash) : l).toLowerCase(java.util.Locale.ROOT);
    }

    private JsonNode get(String path, Map<String, String> params) throws ProviderUnavailableException {
        StringBuilder url = new StringBuilder(config.apiUrl().replaceAll("/+$", "")).append(path).append('?');
        params.forEach((k, v) -> url.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));
        HttpRequest.Builder request;
        Optional<String> token = config.readToken().filter(t -> !t.isBlank());
        if (token.isPresent()) {
            request = HttpRequest.newBuilder(URI.create(url.toString())).header("Authorization", "Bearer " + token.get());
        } else {
            String key = config.apiKey().filter(k -> !k.isBlank()).orElseThrow(
                    () -> new ProviderUnavailableException("TMDB non configuré (pas de clé)", null));
            request = HttpRequest.newBuilder(URI.create(url + "api_key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)));
        }
        HttpResponse<String> response;
        try {
            limiter.acquire();
            response = http.send(request.timeout(Duration.ofSeconds(20)).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("interrompu", null);
        } catch (IOException e) {
            // Jamais l'URL dans le message : elle peut contenir la clé (api_key).
            throw new ProviderUnavailableException("TMDB injoignable : " + e.getClass().getSimpleName(), null);
        }
        int status = response.statusCode();
        if (status == 429) {
            Duration wait = response.headers().firstValue("Retry-After").map(TmdbClient::seconds).orElse(Duration.ofSeconds(10));
            limiter.pauseUntil(clock.instant().plus(wait));
            throw new ProviderUnavailableException("TMDB : limite de débit atteinte", wait);
        }
        if (status == 401) {
            throw new ProviderUnavailableException("TMDB : clé refusée (401), vérifier TMDB_READ_TOKEN", Duration.ofHours(1));
        }
        if (status == 404) {
            return null;
        }
        if (status != 200) {
            throw new ProviderUnavailableException("TMDB : HTTP " + status, null);
        }
        try {
            return json.readTree(response.body());
        } catch (IOException e) {
            throw new ProviderUnavailableException("TMDB : réponse illisible", null);
        }
    }

    private static Duration seconds(String s) {
        try {
            return Duration.ofSeconds(Math.max(1, Long.parseLong(s.trim())));
        } catch (NumberFormatException e) {
            return Duration.ofSeconds(10);
        }
    }

    private static Result result(String type, JsonNode r) {
        boolean tv = "tv".equals(type);
        String date = text(r.path(tv ? "first_air_date" : "release_date"));
        Integer year = date != null && date.length() >= 4 && date.substring(0, 4).chars().allMatch(Character::isDigit)
                ? Integer.valueOf(date.substring(0, 4)) : null;
        List<Integer> genres = new ArrayList<>();
        r.path("genre_ids").forEach(g -> genres.add(g.asInt()));
        r.path("genres").forEach(g -> genres.add(g.path("id").asInt()));
        return new Result(type, r.path("id").asLong(), text(r.path(tv ? "name" : "title")),
                text(r.path(tv ? "original_name" : "original_title")), year, text(r.path("overview")),
                text(r.path("poster_path")), text(r.path("original_language")), genres);
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() || n.asText().isBlank() ? null : n.asText().trim();
    }
}
