package fr.plexwish.animeserver.poster;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Faux serveur d'images (TMDB et AniList) : réponses programmées par chemin, compteur d'appels. */
public final class FakeImages {

    public record Reply(int status, Map<String, String> headers, byte[] body, boolean chunked) {
        public static Reply image(String type, byte[] body) {
            return new Reply(200, Map.of("Content-Type", type), body, false);
        }
    }

    private final HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final Map<String, Integer> hits = new ConcurrentHashMap<>();

    public FakeImages() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public void stop() {
        server.stop(0);
    }

    public void reset() {
        replies.clear();
        hits.clear();
    }

    public void on(String path, Reply reply) {
        replies.put(path, reply);
    }

    public int hits(String path) {
        return hits.getOrDefault(path, 0);
    }

    public int totalHits() {
        return hits.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Petite image valide : en-tête du format, puis du remplissage propre à {@code seed}. */
    public static byte[] jpeg(int size, int seed) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) seed);
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        b[3] = (byte) 0xE0;
        return b;
    }

    public static byte[] png(int size, int seed) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) seed);
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, b, 0, sig.length);
        return b;
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        hits.merge(path, 1, Integer::sum);
        Reply r = replies.getOrDefault(path, new Reply(404, new HashMap<>(), new byte[0], false));
        r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.sendResponseHeaders(r.status(), r.chunked() ? 0 : r.body().length == 0 ? -1 : r.body().length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(r.body());
        }
    }
}
