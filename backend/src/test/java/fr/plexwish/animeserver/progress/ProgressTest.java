package fr.plexwish.animeserver.progress;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Progression par utilisateur : création, mise à jour, indépendance, seuil de 90 %, « continuer à regarder ». */
@QuarkusTest
class ProgressTest {

    static final Path ROOT = Path.of("target/test-library");

    @Inject
    AgroalDataSource ds;

    String alice;
    String bob;

    @BeforeEach
    void library() throws Exception {
        truncateLibrary(ds);
        deleteTree(ROOT);
        for (String p : List.of("Show/Show - S01E01.mkv", "Show/Show - S01E02.mkv", "Show/Show - S01E03.mkv",
                "Other/Other - S00E01.mkv")) {
            touch(ROOT, p);
        }
        assertEquals("SUCCESS", scan().getString("status"));
        alice = token("alice");
        bob = token("bob");
    }

    private static String token(String prefix) {
        String name = unique(prefix);
        createUser(name, "progress-password", "USER");
        return accessToken(name, "progress-password");
    }

    private long episode(String anime, int season, int number) throws Exception {
        return count(ds, "SELECT e.id FROM episode e JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id"
                + " WHERE a.title = '" + anime + "' AND s.season_number = " + season + " AND e.episode_number = " + number);
    }

    private long ep(int n) throws Exception {
        return episode("Show", 1, n);
    }

    private static ValidatableResponse put(String token, long episodeId, Object position, Object duration) {
        return given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("positionSeconds", position, "durationSeconds", duration))
                .put("/api/episodes/" + episodeId + "/progress").then().log().ifValidationFails();
    }

    @Test
    void createThenUpdate() throws Exception {
        put(alice, ep(1), 120, 1400).statusCode(200)
                .body("episodeId", equalTo((int) ep(1))).body("positionSeconds", equalTo(120))
                .body("durationSeconds", equalTo(1400)).body("completed", equalTo(false)).body("updatedAt", notNullValue());
        put(alice, ep(1), 600, 1400).statusCode(200).body("positionSeconds", equalTo(600));
        given().auth().oauth2(alice).get("/api/me/progress").then().statusCode(200)
                .body("$", hasSize(1)).body("[0].positionSeconds", equalTo(600));
        assertEquals(1, count(ds, "SELECT count(*) FROM playback_progress"));
    }

    @Test
    void usersAreIndependent() throws Exception {
        put(alice, ep(1), 600, 1400).statusCode(200);
        put(bob, ep(1), 50, 1400).statusCode(200);
        put(bob, ep(2), 1390, 1400).statusCode(200);
        given().auth().oauth2(alice).get("/api/me/progress").then()
                .body("episodeId", contains((int) ep(1))).body("positionSeconds", contains(600));
        given().auth().oauth2(bob).get("/api/me/progress").then()
                .body("episodeId", contains((int) ep(2), (int) ep(1))).body("positionSeconds", contains(1390, 50));
        given().auth().oauth2(alice).get("/api/me/continue-watching").then().body("episodeId", contains((int) ep(1)));
        given().auth().oauth2(bob).get("/api/me/continue-watching").then().body("episodeId", contains((int) ep(1)));
    }

    @Test
    void completedBeyondNinetyPercent() throws Exception {
        put(alice, ep(1), 1260, 1400).statusCode(200).body("completed", equalTo(false)); // 90 % pile
        put(alice, ep(1), 1261, 1400).statusCode(200).body("completed", equalTo(true));
        put(alice, ep(1), 100, 1400).statusCode(200).body("completed", equalTo(false)); // revenu au début : à reprendre
        // Position au-delà de la durée (arrondi du lecteur) : ramenée à la durée.
        put(alice, ep(2), 1405, 1400).statusCode(200).body("positionSeconds", equalTo(1400)).body("completed", equalTo(true));
    }

    @Test
    void continueWatchingListsStartedUnfinishedEpisodesMostRecentFirst() throws Exception {
        long special = episode("Other", 0, 1);
        put(alice, ep(2), 1350, 1400).statusCode(200);   // terminé : absent
        put(alice, ep(3), 0, 1400).statusCode(200);      // pas commencé : absent
        put(alice, special, 30, 600).statusCode(200);
        Thread.sleep(5);
        put(alice, ep(1), 600, 1400).statusCode(200);    // le plus récent : en premier

        given().auth().oauth2(alice).get("/api/me/continue-watching").then().statusCode(200)
                .body("episodeId", contains((int) ep(1), (int) special))
                .body("[0].animeTitle", equalTo("Show"))
                .body("[0].seasonNumber", equalTo(1)).body("[0].seasonLabel", equalTo("Saison 1"))
                .body("[0].episodeNumber", equalTo(1))
                .body("[0].positionSeconds", equalTo(600)).body("[0].durationSeconds", equalTo(1400))
                .body("[0].animeId", notNullValue()).body("[0].seasonId", notNullValue())
                .body("[1].seasonLabel", equalTo("Spéciaux"));
        given().auth().oauth2(alice).queryParam("limit", 1).get("/api/me/continue-watching").then()
                .body("episodeId", contains((int) ep(1)));
        // Filtre par animé (pour la fiche d'un animé).
        long show = count(ds, "SELECT id FROM anime WHERE title = 'Show'");
        given().auth().oauth2(alice).queryParam("animeId", show).get("/api/me/progress").then()
                .body("episodeId", contains((int) ep(1), (int) ep(3), (int) ep(2)));
        given().auth().oauth2(bob).get("/api/me/continue-watching").then().body("$", empty());
    }

    @Test
    void unavailableEpisodesAreHiddenAndRefused() throws Exception {
        put(alice, ep(1), 600, 1400).statusCode(200);
        Files.delete(ROOT.resolve("Show/Show - S01E01.mkv"));
        scan();
        given().auth().oauth2(alice).get("/api/me/continue-watching").then().body("$", empty());
        given().auth().oauth2(alice).get("/api/me/progress").then().body("$", empty());
        put(alice, ep(1), 700, 1400).statusCode(404).body("error", equalTo("EPISODE_NOT_FOUND"));
        // Le fichier revient : la progression aussi (elle n'a pas été effacée).
        touch(ROOT, "Show/Show - S01E01.mkv");
        scan();
        given().auth().oauth2(alice).get("/api/me/continue-watching").then().body("positionSeconds", contains(600));
    }

    @Test
    void validationAndAuthentication() throws Exception {
        put(alice, ep(1), -1, 1400).statusCode(400);
        put(alice, ep(1), 10, 0).statusCode(400);
        put(alice, ep(1), 10, 100_000).statusCode(400);
        given().auth().oauth2(alice).contentType(ContentType.JSON).body(Map.of("positionSeconds", 10))
                .put("/api/episodes/" + ep(1) + "/progress").then().statusCode(400);
        put(alice, 999_999, 10, 100).statusCode(404);
        given().contentType(ContentType.JSON).body(Map.of("positionSeconds", 10, "durationSeconds", 100))
                .put("/api/episodes/" + ep(1) + "/progress").then().statusCode(401);
        given().get("/api/me/progress").then().statusCode(401);
        given().get("/api/me/continue-watching").then().statusCode(401);
        given().auth().oauth2(alice).queryParam("limit", 0).get("/api/me/continue-watching").then().statusCode(400);
        assertEquals(0, count(ds, "SELECT count(*) FROM playback_progress"));
    }
}
