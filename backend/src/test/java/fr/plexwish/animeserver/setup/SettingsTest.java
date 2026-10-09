package fr.plexwish.animeserver.setup;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** Administration > Réglages : état des sauvegardes lu dans status.json (écrit par le conteneur backup). */
@QuarkusTest
class SettingsTest {

    @Test
    void backupStatusIsShown() throws Exception {
        Path dir = Path.of("target/test-backups");
        Files.createDirectories(dir);
        Files.deleteIfExists(dir.resolve("status.json"));
        given().auth().oauth2(adminToken()).get("/api/admin/settings").then().statusCode(200).body("backup", nullValue());
        Files.writeString(dir.resolve("status.json"), """
                {"lastRun":"2026-10-09T01:00:04Z","ok":true,"verified":true,"file":"anime-db-20261009-030000.dump","sizeBytes":123456,"message":"restaurée et vérifiée"}
                """);
        given().auth().oauth2(adminToken()).get("/api/admin/settings").then().statusCode(200)
                .body("backup.ok", equalTo(true)).body("backup.file", equalTo("anime-db-20261009-030000.dump"))
                .body("tmdb.configured", equalTo(false));
        Files.delete(dir.resolve("status.json"));
    }
}
