package fr.plexwish.animeserver.common;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Régression : sous Windows, {@code mvnw quarkus:dev} définit la propriété système {@code library.jansi.path}.
 * Avec un {@code @ConfigMapping(prefix = "library")}, Quarkus refusait de démarrer (SRCFG00050, propriété inconnue
 * sous un préfixe mappé). Les forks Surefire n'ont pas cette propriété : on la remet ici, avec quelques autres
 * propriétés courantes de Maven / de la JVM, et l'application doit démarrer.
 */
@QuarkusTest
@TestProfile(MavenSystemPropertiesTest.WithMavenProperties.class)
class MavenSystemPropertiesTest {

    public static class WithMavenProperties implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "library.jansi.path", "C:\\Users\\dev\\.m2\\wrapper\\dists\\apache-maven\\lib\\jansi-native",
                    "library.path", "/tmp/native",
                    "auth.sample", "x",
                    "spike.sample", "x");
        }
    }

    @Test
    void applicationStartsDespiteMavenSystemProperties() {
        given().get("/api/status").then().statusCode(200).body("status", equalTo("UP"));
    }
}
