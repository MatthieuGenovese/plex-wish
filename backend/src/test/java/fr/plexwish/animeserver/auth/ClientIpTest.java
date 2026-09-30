package fr.plexwish.animeserver.auth;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Derrière un proxy de confiance (ici 127.0.0.1, qui joue nginx), la vraie IP vient de X-Forwarded-For. */
@QuarkusTest
class ClientIpTest {

    @Test
    void forwardedIpIsUsedWhenTheProxyIsTrusted() {
        String token = adminToken();
        given().auth().oauth2(token).header("X-Forwarded-For", "203.0.113.7")
                .get("/api/admin/debug/client-ip").then().statusCode(200).body("ip", equalTo("203.0.113.7"));
        given().auth().oauth2(token).header("X-Forwarded-For", "198.51.100.23")
                .get("/api/admin/debug/client-ip").then().body("ip", equalTo("198.51.100.23"));
        // Sans header : l'IP de la connexion.
        given().auth().oauth2(token).get("/api/admin/debug/client-ip").then().body("ip", equalTo("127.0.0.1"));
    }
}
