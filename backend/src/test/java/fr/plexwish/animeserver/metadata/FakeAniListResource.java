package fr.plexwish.animeserver.metadata;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** Démarre le faux AniList pour les tests Quarkus et y pointe l'application. */
public class FakeAniListResource implements QuarkusTestResourceLifecycleManager {

    public static FakeAniList server;

    @Override
    public Map<String, String> start() {
        try {
            server = new FakeAniList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.of("anime.metadata.anilist-url", server.url());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}
