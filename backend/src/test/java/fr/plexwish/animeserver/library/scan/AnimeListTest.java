package fr.plexwish.animeserver.library.scan;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.touch;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** GET /api/anime : pagination, tri, recherche sans casse ni accents (la vraie liste fait ~1 300 animés). */
@QuarkusTest
class AnimeListTest {

    static final Path ROOT = Path.of("target/test-library");

    @Inject
    AgroalDataSource ds;

    @BeforeEach
    void library() throws Exception {
        truncateLibrary(ds);
        deleteTree(ROOT);
        for (String title : List.of("Chûnibyô Demo Koi ga Shitai!", "Kyō no Go no Ni", "100%_Pascal-sensei",
                "Abc", "Bleach", "Naruto", "One Piece")) {
            touch(ROOT, title + "/" + title + " - S01E01.mkv");
        }
        touch(ROOT, "Naruto/Naruto - S01E02.mkv");
        assertEquals("SUCCESS", scan().getString("status"));
    }

    @Test
    void pagesAreStableAndComplete() {
        String token = adminToken();
        given().auth().oauth2(token).queryParam("size", 3).get("/api/anime").then().statusCode(200)
                .body("total", equalTo(7)).body("page", equalTo(0)).body("size", equalTo(3))
                .body("items.title", contains("100%_Pascal-sensei", "Abc", "Bleach"));
        given().auth().oauth2(token).queryParam("size", 3).queryParam("page", 2).get("/api/anime").then()
                .body("items.title", contains("One Piece"));
        given().auth().oauth2(token).queryParam("size", 3).queryParam("page", 9).get("/api/anime").then()
                .statusCode(200).body("total", equalTo(7)).body("items", empty());
        given().auth().oauth2(token).queryParam("size", 201).get("/api/anime").then().statusCode(400);
        given().auth().oauth2(token).queryParam("page", -1).get("/api/anime").then().statusCode(400);
    }

    @Test
    void searchIgnoresCaseAndAccents() {
        String token = adminToken();
        given().auth().oauth2(token).queryParam("q", "chunibyo").get("/api/anime").then()
                .body("total", equalTo(1)).body("items.title", contains("Chûnibyô Demo Koi ga Shitai!"));
        given().auth().oauth2(token).queryParam("q", "KYO NO").get("/api/anime").then()
                .body("items.title", contains("Kyō no Go no Ni"));
        given().auth().oauth2(token).queryParam("q", "  naru ").get("/api/anime").then()
                .body("items.title", contains("Naruto")).body("items[0].episodeCount", equalTo(2));
        // Les jokers de LIKE sont pris littéralement.
        given().auth().oauth2(token).queryParam("q", "%").get("/api/anime").then()
                .body("items.title", contains("100%_Pascal-sensei"));
        given().auth().oauth2(token).queryParam("q", "_").get("/api/anime").then()
                .body("items.title", contains("100%_Pascal-sensei"));
        given().auth().oauth2(token).queryParam("q", "zzz").get("/api/anime").then()
                .body("total", equalTo(0)).body("items", empty());
    }

    @Test
    void recentFirstThenTitle() throws Exception {
        touch(ROOT, "Bleach/Bleach - S01E02.mkv");
        scan();
        given().auth().oauth2(adminToken()).queryParam("sort", "recent").queryParam("size", 2).get("/api/anime").then()
                .body("items.title", contains("Bleach", "100%_Pascal-sensei"));
    }
}
