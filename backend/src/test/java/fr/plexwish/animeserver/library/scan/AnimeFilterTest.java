package fr.plexwish.animeserver.library.scan;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.accessToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.touch;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Filtres de GET /api/anime (S2, ARCHITECTURE §24.2) : années, progression de l'utilisateur, lisible dans un navigateur. */
@QuarkusTest
class AnimeFilterTest {

    static final Path ROOT = Path.of("target/test-library");

    @Inject
    AgroalDataSource ds;

    String user;
    String other;

    @BeforeEach
    void library() throws Exception {
        truncateLibrary(ds);
        deleteTree(ROOT);
        for (String p : List.of("Alpha/Alpha - S01E01.mkv", "Alpha/Alpha - S01E02.mkv", "Beta/Beta - S01E01.mkv",
                "Gamma/Gamma - S01E01.mkv", "Gamma/Gamma - S01E02.mkv", "Delta/Delta - S01E01.mkv")) {
            touch(ROOT, p);
        }
        assertEquals("SUCCESS", scan().getString("status"));
        sql("UPDATE anime SET year = 1998 WHERE title = 'Alpha'", "UPDATE anime SET year = 2010 WHERE title = 'Beta'",
                "UPDATE anime SET year = 2023 WHERE title = 'Gamma'"); // Delta : sans année
        user = token();
        other = token();
    }

    private static String token() {
        String name = unique("filter");
        createUser(name, "filter-password", "USER");
        return accessToken(name, "filter-password");
    }

    private void sql(String... statements) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String s : statements) {
                st.execute(s);
            }
        }
    }

    private long ep(String anime, int number) throws Exception {
        return count(ds, "SELECT e.id FROM episode e JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id"
                + " WHERE a.title = '" + anime + "' AND e.episode_number = " + number);
    }

    private void progress(String token, long episodeId, int position) {
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("positionSeconds", position, "durationSeconds", 1400))
                .put("/api/episodes/" + episodeId + "/progress").then().statusCode(200);
    }

    private RequestSpecification as(String token) {
        return given().auth().oauth2(token);
    }

    @Test
    void years() {
        as(user).queryParam("yearFrom", 2000).get("/api/anime").then().statusCode(200)
                .body("total", equalTo(2)).body("items.title", contains("Beta", "Gamma"));
        as(user).queryParam("yearTo", 2010).get("/api/anime").then().body("items.title", contains("Alpha", "Beta"));
        as(user).queryParam("yearFrom", 2010).queryParam("yearTo", 2010).get("/api/anime").then()
                .body("items.title", contains("Beta"));
        as(user).queryParam("yearFrom", 2011).queryParam("yearTo", 2010).get("/api/anime").then().statusCode(400)
                .body("error", equalTo("INVALID_FILTER"));
        as(user).queryParam("yearFrom", 1800).get("/api/anime").then().statusCode(400);
    }

    @Test
    void watchStateIsPerUser() throws Exception {
        progress(user, ep("Alpha", 1), 1400);
        progress(user, ep("Alpha", 2), 1400);  // Alpha : tout vu
        progress(user, ep("Gamma", 1), 300);   // Gamma : en cours
        progress(user, ep("Beta", 1), 0);      // position 0 : pas commencé
        as(user).queryParam("watch", "seen").get("/api/anime").then().body("items.title", contains("Alpha"));
        as(user).queryParam("watch", "inProgress").get("/api/anime").then().body("items.title", contains("Gamma"));
        as(user).queryParam("watch", "unseen").get("/api/anime").then()
                .body("total", equalTo(2)).body("items.title", contains("Beta", "Delta"));
        // Gamma 1 vu, Gamma 2 pas commencé : toujours « en cours ».
        progress(user, ep("Gamma", 1), 1400);
        as(user).queryParam("watch", "inProgress").get("/api/anime").then().body("items.title", contains("Gamma"));
        // Un autre compte n'a rien vu.
        as(other).queryParam("watch", "unseen").get("/api/anime").then().body("total", equalTo(4));
        as(user).queryParam("watch", "later").get("/api/anime").then().statusCode(400).body("error", equalTo("INVALID_FILTER"));
    }

    @Test
    void browserPlayable() throws Exception {
        // Alpha : 2 épisodes lisibles ; Beta : non lisible ; Gamma : 1 lisible + 1 pas analysé ; Delta : pas analysé.
        sql("""
                INSERT INTO media_probe (media_file_id, probed_size, status, browser_playable)
                SELECT e.media_file_id, 3, 'OK', a.title = 'Alpha' OR e.episode_number = 1 AND a.title = 'Gamma'
                FROM episode e JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id
                WHERE a.title IN ('Alpha', 'Beta') OR (a.title = 'Gamma' AND e.episode_number = 1)""");
        as(user).queryParam("browser", true).get("/api/anime").then().body("items.title", contains("Alpha"));
        as(user).queryParam("browser", false).get("/api/anime").then().body("items.title", contains("Beta", "Delta", "Gamma"));
    }

    @Test
    void filtersCombineWithSearchSortAndPaging() throws Exception {
        progress(user, ep("Alpha", 1), 200);
        as(user).queryParam("q", "a").queryParam("yearFrom", 1990).queryParam("watch", "unseen").queryParam("size", 1)
                .get("/api/anime").then().statusCode(200).body("total", equalTo(2)).body("items.title", contains("Beta"))
                .body("items[0].episodeCount", equalTo(1)).body("items[0].year", equalTo(2010));
        as(user).queryParam("sort", "recent").queryParam("watch", "inProgress").get("/api/anime").then()
                .body("items.title", contains("Alpha")).body("items[0].episodeCount", equalTo(2));
        // Sans filtre : inchangé (4 animés).
        as(user).get("/api/anime").then().body("total", equalTo(4));
    }
}
