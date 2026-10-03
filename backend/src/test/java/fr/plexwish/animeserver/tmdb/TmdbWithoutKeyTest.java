package fr.plexwish.animeserver.tmdb;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.touch;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Sans clé TMDB (configuration par défaut des tests) : l'application démarre, synopsis anglais, admin informé. */
@QuarkusTest
class TmdbWithoutKeyTest {

    @Inject
    AgroalDataSource ds;
    @Inject
    TmdbConfig config;
    @Inject
    TmdbWorker worker;

    @Test
    void startsAndFallsBackToAniList() throws Exception {
        assertFalse(config.configured());
        assertFalse(worker.running());
        Path root = Path.of("target/test-library");
        truncateLibrary(ds);
        deleteTree(root);
        touch(root, "Show/Show - 01.mkv");
        assertEquals("SUCCESS", scan().getString("status"));
        long id = count(ds, "SELECT id FROM anime WHERE title = 'Show'");
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("UPDATE anime SET synopsis = 'English.', synopsis_language = 'en', metadata_provider = 'ANILIST' WHERE id = " + id);
        }
        String admin = adminToken();
        given().auth().oauth2(admin).get("/api/anime/" + id).then().statusCode(200)
                .body("synopsis", equalTo("English.")).body("synopsisSource", equalTo("AniList")).body("tmdbUrl", nullValue());
        given().auth().oauth2(admin).get("/api/admin/tmdb/summary").then().statusCode(200).body("configured", equalTo(false));
        given().auth().oauth2(admin).queryParam("type", "tv").queryParam("tmdbId", 1)
                .get("/api/admin/anime/" + id + "/tmdb/preview").then().statusCode(503).body("error", equalTo("TMDB_NOT_CONFIGURED"));
    }
}
