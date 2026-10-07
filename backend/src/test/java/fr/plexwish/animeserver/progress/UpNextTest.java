package fr.plexwish.animeserver.progress;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
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
import static org.junit.jupiter.api.Assertions.assertEquals;

/** « À suivre » (S1, ARCHITECTURE §24) : une entrée par animé, épisode suivant d'un épisode terminé. */
@QuarkusTest
class UpNextTest {

    static final Path ROOT = Path.of("target/test-library");

    @Inject
    AgroalDataSource ds;

    String user;

    @BeforeEach
    void library() throws Exception {
        truncateLibrary(ds);
        deleteTree(ROOT);
        for (String p : List.of("Show/Show - S01E01.mkv", "Show/Show - S01E02.mkv", "Show/Show - S01E03.mkv",
                "Show/Show - S02E01.mkv", "Show/Show - S02E02.mkv", "Show/Show - S00E01.mkv", "Show/Show - S00E02.mkv",
                "Other/Other - S01E01.mkv", "Other/Other - S01E02.mkv", "Third/Third - S01E01.mkv")) {
            touch(ROOT, p);
        }
        assertEquals("SUCCESS", scan().getString("status"));
        String name = unique("upnext");
        createUser(name, "upnext-password", "USER");
        user = accessToken(name, "upnext-password");
    }

    private long ep(String anime, int season, int number) throws Exception {
        return count(ds, "SELECT e.id FROM episode e JOIN season s ON s.id = e.season_id JOIN anime a ON a.id = s.anime_id"
                + " WHERE a.title = '" + anime + "' AND s.season_number = " + season + " AND e.episode_number = " + number);
    }

    private void watch(long episodeId, int position) throws Exception {
        given().auth().oauth2(user).contentType(ContentType.JSON).body(Map.of("positionSeconds", position, "durationSeconds", 1400))
                .put("/api/episodes/" + episodeId + "/progress").then().statusCode(200);
        Thread.sleep(5); // updated_at strictement croissant
    }

    private void finish(long episodeId) throws Exception {
        watch(episodeId, 1400);
    }

    private io.restassured.response.ValidatableResponse cw() {
        return given().auth().oauth2(user).get("/api/me/continue-watching").then().statusCode(200);
    }

    @Test
    void finishedEpisodeProposesTheNextOneFromTheStart() throws Exception {
        finish(ep("Show", 1, 1));
        cw().body("episodeId", contains((int) ep("Show", 1, 2))).body("kind", contains("NEXT"))
                .body("[0].positionSeconds", equalTo(0)).body("[0].durationSeconds", equalTo(0))
                .body("[0].episodeNumber", equalTo(2)).body("[0].seasonLabel", equalTo("Saison 1"));
    }

    @Test
    void endOfSeasonGoesToNextSeasonButNeverIntoSpecials() throws Exception {
        finish(ep("Show", 1, 3));
        cw().body("episodeId", contains((int) ep("Show", 2, 1))).body("[0].seasonNumber", equalTo(2));
        finish(ep("Show", 2, 2)); // dernier épisode des saisons normales : animé fini
        cw().body("$", empty());
        finish(ep("Show", 0, 1)); // Spéciaux : on continue dans les Spéciaux
        cw().body("episodeId", contains((int) ep("Show", 0, 2))).body("[0].seasonLabel", equalTo("Spéciaux"));
    }

    @Test
    void oneEntryPerAnimeLastActivityDecides() throws Exception {
        watch(ep("Show", 1, 1), 300);
        watch(ep("Other", 1, 1), 200);
        watch(ep("Show", 1, 2), 500); // même animé, plus récent : remplace l'épisode 1
        cw().body("episodeId", contains((int) ep("Show", 1, 2), (int) ep("Other", 1, 1)))
                .body("kind", contains("RESUME", "RESUME")).body("positionSeconds", contains(500, 200));
    }

    @Test
    void skipsEpisodesAlreadyFinishedAndResumesAStartedNextEpisode() throws Exception {
        finish(ep("Show", 1, 2));
        watch(ep("Show", 1, 3), 700);
        finish(ep("Show", 1, 1)); // revu l'épisode 1 : le 2 est déjà vu, le 3 est commencé
        cw().body("episodeId", contains((int) ep("Show", 1, 3))).body("kind", contains("RESUME"))
                .body("[0].positionSeconds", equalTo(700)).body("[0].durationSeconds", equalTo(1400));
    }

    @Test
    void limitCountsOnlyAnimeStillInProgress() throws Exception {
        finish(ep("Third", 1, 1)); // fini, absent
        watch(ep("Other", 1, 1), 100);
        watch(ep("Show", 1, 1), 100);
        given().auth().oauth2(user).queryParam("limit", 1).get("/api/me/continue-watching").then()
                .body("episodeId", contains((int) ep("Show", 1, 1)));
        cw().body("animeTitle", contains("Show", "Other"));
    }

    @Test
    void unavailableNextEpisodeIsSkipped() throws Exception {
        finish(ep("Show", 1, 1));
        long third = ep("Show", 1, 3);
        Files.delete(ROOT.resolve("Show/Show - S01E02.mkv"));
        scan();
        cw().body("episodeId", contains((int) third));
    }

    // --- S5 : bouton principal de la fiche (resume dans GET /api/anime/{id}, ARCHITECTURE §24.3) ---

    private io.restassured.response.ValidatableResponse detail(String anime) throws Exception {
        long id = count(ds, "SELECT id FROM anime WHERE title = '" + anime + "'");
        return given().auth().oauth2(user).get("/api/anime/" + id).then().statusCode(200);
    }

    @Test
    void detailProposesStartThenResumeThenNextThenRewatch() throws Exception {
        detail("Other").body("resume.kind", equalTo("START")).body("resume.episodeId", equalTo((int) ep("Other", 1, 1)))
                .body("resume.seasonNumber", equalTo(1)).body("resume.episodeNumber", equalTo(1))
                .body("resume.positionSeconds", equalTo(0));
        watch(ep("Other", 1, 1), 300);
        detail("Other").body("resume.kind", equalTo("RESUME")).body("resume.positionSeconds", equalTo(300))
                .body("resume.durationSeconds", equalTo(1400)).body("resume.seasonId", org.hamcrest.Matchers.notNullValue());
        finish(ep("Other", 1, 1));
        detail("Other").body("resume.kind", equalTo("NEXT")).body("resume.episodeId", equalTo((int) ep("Other", 1, 2)));
        finish(ep("Other", 1, 2));
        detail("Other").body("resume.kind", equalTo("REWATCH")).body("resume.episodeId", equalTo((int) ep("Other", 1, 1)));
    }

    @Test
    void detailStartsWithRegularSeasonsAndIsPerUser() throws Exception {
        detail("Show").body("resume.kind", equalTo("START")).body("resume.episodeId", equalTo((int) ep("Show", 1, 1)));
        finish(ep("Show", 2, 2)); // fin des saisons normales
        detail("Show").body("resume.kind", equalTo("REWATCH")).body("resume.episodeId", equalTo((int) ep("Show", 1, 1)));
        String other = accessToken(createdUser(), "upnext-password");
        long show = count(ds, "SELECT id FROM anime WHERE title = 'Show'");
        given().auth().oauth2(other).get("/api/anime/" + show).then().body("resume.kind", equalTo("START"));
    }

    private static String createdUser() {
        String name = unique("upnext");
        createUser(name, "upnext-password", "USER");
        return name;
    }
}
