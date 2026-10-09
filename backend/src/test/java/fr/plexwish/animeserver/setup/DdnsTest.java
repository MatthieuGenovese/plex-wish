package fr.plexwish.animeserver.setup;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Nom DuckDNS (D1.3) : jeton vérifié avant d'être gardé, rangé en secret, jamais renvoyé ; faux DuckDNS local. */
@QuarkusTest
@TestProfile(DdnsTest.DuckDnsAddress.class)
class DdnsTest {

    public static class DuckDnsAddress implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("anime.auth.public-url", "https://mon-anime.duckdns.org");
        }
    }

    @Inject
    SetupConfig config;

    private static Response put(String token) {
        return given().auth().oauth2(adminToken()).contentType(ContentType.JSON).body(Map.of("token", token))
                .put("/api/admin/settings/ddns");
    }

    @Test
    void tokenCheckedStoredAndNeverReturned() throws Exception {
        Path file = Path.of(config.secretsDir(), SecretStore.DDNS);
        Files.deleteIfExists(file);
        put("pas-un-jeton").then().statusCode(400).body("error", equalTo("DDNS_TOKEN_INVALID"));
        put("ffffffff-0000-4a4a-8b8b-0123456789ab").then().statusCode(400).body("error", equalTo("DDNS_REJECTED"));
        assertFalse(Files.exists(file), "un jeton refusé par DuckDNS n'est pas gardé");

        Response ok = put(FakeDuckDns.GOOD_TOKEN);
        ok.then().statusCode(200).body("configured", equalTo(true)).body("lastOk", equalTo(true))
                .body("ip", equalTo("198.51.100.7")).body("domain", equalTo("mon-anime.duckdns.org"));
        assertFalse(ok.asString().contains(FakeDuckDns.GOOD_TOKEN));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertTrue(FakeDuckDns.DOMAINS.contains("mon-anime"));

        String overview = given().auth().oauth2(adminToken()).get("/api/admin/settings").then().statusCode(200)
                .body("ddns.configured", equalTo(true)).extract().asString();
        assertFalse(overview.contains(FakeDuckDns.GOOD_TOKEN));

        given().auth().oauth2(adminToken()).delete("/api/admin/settings/ddns").then().statusCode(200).body("configured", equalTo(false));
        assertFalse(Files.exists(file));
    }
}
