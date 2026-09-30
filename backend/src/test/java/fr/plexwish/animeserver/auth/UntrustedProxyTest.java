package fr.plexwish.animeserver.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.ADMIN;
import static fr.plexwish.animeserver.auth.AuthTestSupport.ADMIN_PASSWORD;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * La connexion ne vient PAS d'un proxy de confiance : un X-Forwarded-For forgé par le client est ignoré,
 * y compris par l'anti brute force (changer de fausse IP à chaque essai ne contourne pas le blocage).
 */
@QuarkusTest
@TestProfile(UntrustedProxyTest.Untrusted.class)
class UntrustedProxyTest {

    public static class Untrusted implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.proxy.trusted-proxies", "10.255.255.1");
        }
    }

    private static io.restassured.response.Response login(String login, String password, String spoofedIp) {
        return given().contentType(ContentType.JSON).header("X-Forwarded-For", spoofedIp)
                .body(Map.of("login", login, "password", password)).post("/api/auth/login");
    }

    @Test
    void spoofedForwardedForIsIgnored() {
        String token = login(ADMIN, ADMIN_PASSWORD, "1.2.3.4").then().statusCode(200).extract().path("accessToken");
        given().auth().oauth2(token).header("X-Forwarded-For", "203.0.113.7")
                .get("/api/admin/debug/client-ip").then().statusCode(200).body("ip", equalTo("127.0.0.1"));

        // 20 échecs avec une fausse IP différente à chaque fois : tous comptés sur 127.0.0.1.
        for (int i = 0; i < 20; i++) {
            login("nobody-" + i, "wrong", "192.0.2." + i).then().statusCode(401);
        }
        login(ADMIN, ADMIN_PASSWORD, "192.0.2.200").then().statusCode(429);
    }
}
