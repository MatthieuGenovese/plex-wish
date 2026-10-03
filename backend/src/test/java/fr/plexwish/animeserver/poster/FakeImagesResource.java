package fr.plexwish.animeserver.poster;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** Démarre le faux serveur d'images ; seul 127.0.0.1 est autorisé, en http (tests uniquement). */
public class FakeImagesResource implements QuarkusTestResourceLifecycleManager {

    static FakeImages server;
    static final long MAX_BYTES = 4096;

    @Override
    public Map<String, String> start() {
        try {
            server = new FakeImages();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.of("anime.posters.allowed-hosts", "127.0.0.1",
                "anime.posters.allow-http", "true",
                "anime.posters.max-bytes", String.valueOf(MAX_BYTES),
                "anime.posters.tmdb-image-base", server.url("/t/p/w500"));
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}
