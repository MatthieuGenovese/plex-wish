package fr.plexwish.animeserver.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Faux serveur AniList pour les tests (aucun appel réseau réel) : réponses de recherche et par id programmées,
 * pannes et limites de débit simulées, journal des requêtes reçues.
 */
public final class FakeAniList {

    static final ObjectMapper JSON = new ObjectMapper();

    /** Réponse forcée (statut, en-têtes, corps) pour simuler pannes et 429. */
    public record Forced(int status, Map<String, String> headers, String body) {
    }

    private final HttpServer server;
    private final Map<String, List<ObjectNode>> searches = new HashMap<>();
    private final Map<Integer, ObjectNode> byId = new HashMap<>();
    private final Deque<Forced> forced = new ArrayDeque<>();
    private final Map<Integer, Forced> failById = new HashMap<>();
    private volatile Forced always;
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());
    /** Dernière requête GraphQL reçue (pour vérifier ses filtres). */
    volatile String lastQuery;

    public FakeAniList() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    public void stop() {
        server.stop(0);
    }

    public synchronized void reset() {
        searches.clear();
        byId.clear();
        forced.clear();
        failById.clear();
        always = null;
        log.clear();
    }

    /** Fiche AniList minimale. */
    public static ObjectNode media(int id, String romaji, String english, Integer year, String format, Integer episodes,
                                   String... synonyms) {
        ObjectNode m = JSON.createObjectNode();
        m.put("id", id);
        ObjectNode title = m.putObject("title");
        title.put("romaji", romaji);
        if (english != null) title.put("english", english); else title.putNull("english");
        title.put("native", "日本語");
        ArrayNode syn = m.putArray("synonyms");
        for (String s : synonyms) syn.add(s);
        m.put("format", format);
        if (episodes != null) m.put("episodes", episodes); else m.putNull("episodes");
        if (year != null) m.put("seasonYear", year); else m.putNull("seasonYear");
        m.putObject("startDate").put("year", year);
        m.put("description", "Synopsis of " + romaji + ".<br><br>\n<i>(Source: test)</i>");
        ObjectNode cover = m.putObject("coverImage");
        cover.put("large", "https://s4.anilist.co/file/anilistcdn/media/anime/cover/medium/bx" + id + ".jpg");
        cover.put("extraLarge", "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx" + id + ".jpg");
        m.put("siteUrl", "https://anilist.co/anime/" + id);
        m.putArray("genres").add("Adventure").add("Fantasy");
        return m;
    }

    public synchronized void onSearch(String search, ObjectNode... results) {
        searches.put(search, List.of(results));
        for (ObjectNode r : results) {
            byId.put(r.get("id").asInt(), r);
        }
    }

    public synchronized void onId(ObjectNode media) {
        byId.put(media.get("id").asInt(), media);
    }

    /** Les prochaines réponses, dans l'ordre, avant de revenir au comportement normal. */
    public synchronized void force(Forced... responses) {
        forced.addAll(List.of(responses));
    }

    /** Réponse forcée pour une fiche donnée (par identifiant) ; null pour revenir à la normale. */
    public synchronized void failId(int id, Forced response) {
        if (response == null) failById.remove(id); else failById.put(id, response);
    }

    /** Toutes les réponses (panne prolongée) ; null pour revenir à la normale. */
    public void always(Forced response) {
        always = response;
    }

    /** Requêtes reçues : "search:<titre>" ou "id:<n>". */
    public List<String> requests() {
        synchronized (log) {
            return List.copyOf(log);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
        JsonNode vars = body.path("variables");
        lastQuery = body.path("query").asText();
        String key = vars.has("search") ? "search:" + vars.get("search").asText()
                : vars.has("ids") ? "ids:" + vars.get("ids") : "id:" + vars.path("id").asText();
        log.add(key);
        Forced f;
        synchronized (this) {
            f = always != null ? always : forced.poll();
            if (f == null && !vars.has("search") && !vars.has("ids")) {
                f = failById.get(vars.path("id").asInt());
            }
        }
        if (f != null) {
            f.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            send(ex, f.status(), f.body());
            return;
        }
        ex.getResponseHeaders().add("X-RateLimit-Limit", "30");
        ex.getResponseHeaders().add("X-RateLimit-Remaining", "29");
        ObjectNode response = JSON.createObjectNode();
        ObjectNode data = response.putObject("data");
        if (vars.has("ids")) {
            // Genres par lots (rattrapage, §24.6) : seulement id et genres, comme la vraie requête.
            ArrayNode media = data.putObject("Page").putArray("media");
            synchronized (this) {
                for (JsonNode id : vars.get("ids")) {
                    ObjectNode m = byId.get(id.asInt());
                    if (m != null) {
                        ObjectNode small = media.addObject();
                        small.put("id", id.asInt());
                        small.set("genres", m.get("genres"));
                    }
                }
            }
            send(ex, 200, JSON.writeValueAsString(response));
        } else if (vars.has("search")) {
            ArrayNode media = data.putObject("Page").putArray("media");
            synchronized (this) {
                searches.getOrDefault(vars.get("search").asText(), List.of()).forEach(media::add);
            }
            send(ex, 200, JSON.writeValueAsString(response));
        } else {
            ObjectNode m;
            synchronized (this) {
                m = byId.get(vars.path("id").asInt());
            }
            if (m == null) {
                send(ex, 404, "{\"errors\":[{\"message\":\"Not Found.\",\"status\":404}],\"data\":{\"Media\":null}}");
            } else {
                data.set("Media", m);
                send(ex, 200, JSON.writeValueAsString(response));
            }
        }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
