package fr.plexwish.animeserver.common;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** CORS_ORIGINS (dev) : seules les origines listées sont acceptées. */
@QuarkusTest
@TestProfile(CorsOriginsTest.WithOrigins.class)
class CorsOriginsTest {

    public static class WithOrigins implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.cors.origins", "http://localhost:4200");
        }
    }

    @Test
    void listedOriginIsAllowedOthersAreNot() {
        given().header("Origin", "http://localhost:4200").get("/api/status").then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", equalTo("http://localhost:4200"));
        given().header("Origin", "http://evil.example").get("/api/status").then()
                .header("Access-Control-Allow-Origin", nullValue());
    }
}
