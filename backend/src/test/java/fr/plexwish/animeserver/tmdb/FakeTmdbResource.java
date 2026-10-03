package fr.plexwish.animeserver.tmdb;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** Démarre le faux TMDB pour les tests Quarkus, avec un jeton de test (jamais un vrai). */
public class FakeTmdbResource implements QuarkusTestResourceLifecycleManager {

    static final String TOKEN = "tmdb-test-read-token-5f1c9a";
    static FakeTmdb server;

    @Override
    public Map<String, String> start() {
        try {
            server = new FakeTmdb();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.of("anime.tmdb.api-url", server.url(), "anime.tmdb.read-token", TOKEN);
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}
