package fr.plexwish.animeserver.tmdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faux serveur TMDB pour les tests (aucun appel réseau réel) : recherches et fiches programmées, pannes et limites
 * de débit simulées, journal des requêtes (chemin, paramètres, en-tête Authorization).
 */
public final class FakeTmdb {

    static final ObjectMapper JSON = new ObjectMapper();

    public record Forced(int status, Map<String, String> headers, String body) {
    }

    /** Une requête reçue. */
    public record Request(String path, Map<String, String> params, String authorization) {
    }

    private final HttpServer server;
    private final Map<String, List<ObjectNode>> searches = new HashMap<>();
    private final Map<String, ObjectNode> sheets = new HashMap<>();
    private final Deque<Forced> forced = new ArrayDeque<>();
    private volatile Forced always;
    private final List<Request> log = Collections.synchronizedList(new ArrayList<>());

    public FakeTmdb() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/3";
    }

    public void stop() {
        server.stop(0);
    }

    public synchronized void reset() {
        searches.clear();
        sheets.clear();
        forced.clear();
        always = null;
        log.clear();
    }

    /** Une série TMDB (genre 16 = Animation si {@code animation}). */
    public static ObjectNode tv(long id, String name, String originalName, Integer year, boolean animation, String overview) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", id);
        n.put("name", name);
        n.put("original_name", originalName);
        n.put("first_air_date", year == null ? "" : year + "-04-01");
        n.put("overview", overview == null ? "" : overview);
        n.put("poster_path", "/p" + id + ".jpg");
        n.put("original_language", "ja");
        n.put("adult", false);
        ArrayNode genres = n.putArray("genre_ids");
        genres.add(animation ? 16 : 18);
        return n;
    }

    public static ObjectNode movie(long id, String title, String originalTitle, Integer year, String overview) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", id);
        n.put("title", title);
        n.put("original_title", originalTitle);
        n.put("release_date", year == null ? "" : year + "-07-01");
        n.put("overview", overview == null ? "" : overview);
        n.put("poster_path", "/m" + id + ".jpg");
        n.put("adult", false);
        n.putArray("genre_ids").add(16);
        return n;
    }

    /** Résultats d'une recherche (type tv ou movie) ; chaque résultat devient aussi une fiche. */
    public synchronized void onSearch(String type, String query, ObjectNode... results) {
        searches.put(type + ":" + query, List.of(results));
        for (ObjectNode r : results) {
            sheet(type, r);
        }
    }

    /** Fiche renvoyée par /{type}/{id} (les genres au format de la fiche : [{id, name}]). */
    public synchronized void sheet(String type, ObjectNode result) {
        ObjectNode s = result.deepCopy();
        ArrayNode genres = s.putArray("genres");
        result.path("genre_ids").forEach(g -> genres.addObject().put("id", g.asInt()).put("name", "g"));
        s.remove("genre_ids");
        sheets.put(type + ":" + result.get("id").asLong(), s);
    }

    public synchronized void removeSheet(String type, long id) {
        sheets.remove(type + ":" + id);
    }

    public synchronized void force(Forced... responses) {
        forced.addAll(List.of(responses));
    }

    public void always(Forced response) {
        always = response;
    }

    public List<Request> requests() {
        synchronized (log) {
            return List.copyOf(log);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().replaceFirst("^/3", "");
        Map<String, String> params = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    params.put(kv.substring(0, eq), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        log.add(new Request(path, params, ex.getRequestHeaders().getFirst("Authorization")));
        Forced f;
        synchronized (this) {
            f = always != null ? always : forced.poll();
        }
        if (f != null) {
            f.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            send(ex, f.status(), f.body());
            return;
        }
        if (path.startsWith("/search/")) {
            String type = path.substring("/search/".length());
            ObjectNode body = JSON.createObjectNode();
            body.put("page", 1);
            ArrayNode results = body.putArray("results");
            synchronized (this) {
                searches.getOrDefault(type + ":" + params.get("query"), List.of()).forEach(results::add);
            }
            send(ex, 200, JSON.writeValueAsString(body));
            return;
        }
        String[] parts = path.split("/");
        ObjectNode s = null;
        if (parts.length == 3) {
            synchronized (this) {
                s = sheets.get(parts[1] + ":" + parts[2]);
            }
        }
        if (s == null) {
            send(ex, 404, "{\"success\":false,\"status_code\":34,\"status_message\":\"The resource you requested could not be found.\"}");
        } else {
            send(ex, 200, JSON.writeValueAsString(s));
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
