package fr.plexwish.animeserver.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Le contrôle d'Origin ne dépend pas de la configuration CORS : même filtre CORS désactivé,
 * une écriture venant d'une autre origine que PUBLIC_URL est refusée.
 */
@QuarkusTest
@TestProfile(OriginCheckWithoutCorsTest.NoCors.class)
class OriginCheckWithoutCorsTest {

    public static class NoCors implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.cors.enabled", "false");
        }
    }

    @Test
    void originCheckStandsOnItsOwn() {
        given().contentType(ContentType.JSON).header("Origin", "https://evil.example")
                .body(Map.of("login", "admin", "password", "whatever-password"))
                .post("/api/auth/login").then()
                .statusCode(403).body("error", equalTo("ORIGIN_NOT_ALLOWED"));
        given().header("Origin", "https://evil.example").get("/api/status").then().statusCode(200);
        given().contentType(ContentType.JSON).header("Origin", "https://anime.test")
                .body(Map.of("login", "admin", "password", "admin-test-password"))
                .post("/api/auth/login").then().statusCode(200);
    }
}
