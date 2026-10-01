package fr.plexwish.animeserver.stream.spike;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;

/** Sans DEV_SPIKE_STREAM_ENABLED, l'endpoint n'existe pas (404). */
@QuarkusTest
@TestProfile(SpikeStreamDisabledTest.Disabled.class)
class SpikeStreamDisabledTest {

    public static class Disabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("anime.spike.stream.enabled", "false");
        }
    }

    @Test
    void endpointsAreHidden() {
        given().get("/api/dev/files").then().statusCode(404);
        given().get("/api/dev/stream/1").then().statusCode(404);
        given().header("Range", "bytes=0-10").get("/api/dev/stream/1").then().statusCode(404);
    }
}
