package fr.plexwish.animeserver.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.ADMIN;
import static fr.plexwish.animeserver.auth.AuthTestSupport.ADMIN_PASSWORD;
import static fr.plexwish.animeserver.auth.AuthTestSupport.newIp;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Contrôle du header Origin contre PUBLIC_URL (profil test : https://anime.test). */
@QuarkusTest
class OriginCheckTest {

    private static io.restassured.response.Response login(String origin) {
        var req = given().contentType(ContentType.JSON).header("X-Forwarded-For", newIp())
                .body(Map.of("login", ADMIN, "password", ADMIN_PASSWORD));
        if (origin != null) {
            req = req.header("Origin", origin);
        }
        return req.post("/api/auth/login");
    }

    @Test
    void writesFromAnotherOriginAreRefused() {
        login("https://evil.example").then().statusCode(403);
        login("null").then().statusCode(403);
        login("https://anime.test.evil.example").then().statusCode(403);
        given().header("Origin", "https://evil.example").post("/api/auth/refresh").then().statusCode(403);
        given().header("Origin", "https://evil.example").post("/api/auth/logout").then().statusCode(403);
    }

    @Test
    void publicUrlOriginAndRequestsWithoutOriginAreAccepted() {
        // Cas réel derrière nginx : requête reçue en http, Origin annoncée en https.
        login("https://anime.test").then().statusCode(200);
        login(null).then().statusCode(200); // client non navigateur (curl, future app Android)
    }

    @Test
    void readsFromPublicUrlWork() {
        given().header("Origin", "https://anime.test").get("/api/status").then().statusCode(200);
    }
}
