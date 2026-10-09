package fr.plexwish.animeserver.user;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.login;
import static fr.plexwish.animeserver.auth.AuthTestSupport.newIp;
import static fr.plexwish.animeserver.auth.AuthTestSupport.patchUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Invitations par lien à usage unique (D1.4). */
@QuarkusTest
class InvitationTest {

    static final String PASSWORD = "mot-de-passe-choisi";

    @Inject
    AgroalDataSource ds;

    /** Crée un compte sans mot de passe ; renvoie [id, jeton]. */
    static Object[] invite(String username) {
        Response r = given().auth().oauth2(adminToken()).contentType(ContentType.JSON)
                .body(Map.of("username", username, "role", "USER")).post("/api/admin/users");
        r.then().statusCode(201).body("passwordSet", equalTo(false))
                .body("invitation.url", startsWith("https://anime.test/invitation#"))
                .body("invitation.purpose", equalTo("INVITE")).body("invitationExpiresAt", notNullValue());
        String url = r.path("invitation.url");
        return new Object[]{((Integer) r.path("id")).longValue(), url.substring(url.indexOf('#') + 1)};
    }

    static Response check(String token, String ip) {
        return given().contentType(ContentType.JSON).header("X-Forwarded-For", ip).body(Map.of("token", token)).post("/api/invitation/check");
    }

    static Response accept(String token, String password, String ip) {
        return given().contentType(ContentType.JSON).header("X-Forwarded-For", ip)
                .body(Map.of("token", token, "password", password)).post("/api/invitation/accept");
    }

    @Test
    void invitedPersonChoosesTheirPasswordOnce() {
        String name = unique("invite");
        Object[] inv = invite(name);
        String token = (String) inv[1];
        String ip = newIp();
        // Pas de connexion possible avant d'avoir choisi son mot de passe.
        login(name, "n-importe-quoi-123", newIp()).then().statusCode(401);
        check(token, ip).then().statusCode(200).body("username", equalTo(name)).body("purpose", equalTo("INVITE"));
        accept(token, "court", ip).then().statusCode(400).body("error", equalTo("WEAK_PASSWORD"));
        accept(token, PASSWORD, ip).then().statusCode(200).body("username", equalTo(name));
        login(name, PASSWORD, newIp()).then().statusCode(200);
        // Usage unique.
        accept(token, "un-autre-mot-de-passe", newIp()).then().statusCode(410).body("error", equalTo("INVITATION_INVALID"));
        given().auth().oauth2(adminToken()).get("/api/admin/users").then().statusCode(200);
    }

    @Test
    void newLinkReplacesTheOldOneAndExpiryIsEnforced() throws Exception {
        String name = unique("relance");
        Object[] inv = invite(name);
        long id = (Long) inv[0];
        String first = (String) inv[1];
        String url = given().auth().oauth2(adminToken()).post("/api/admin/users/" + id + "/invitation")
                .then().statusCode(200).extract().path("url");
        String second = url.substring(url.indexOf('#') + 1);
        check(first, newIp()).then().statusCode(410);
        check(second, newIp()).then().statusCode(200);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE invitation SET expires_at = now() - interval '1 minute' WHERE user_id = " + id);
        }
        check(second, newIp()).then().statusCode(410);
        // Révocation par l'admin, puis désactivation du compte : les liens tombent aussi.
        url = given().auth().oauth2(adminToken()).post("/api/admin/users/" + id + "/invitation").then().extract().path("url");
        String third = url.substring(url.indexOf('#') + 1);
        given().auth().oauth2(adminToken()).delete("/api/admin/users/" + id + "/invitation").then().statusCode(204);
        check(third, newIp()).then().statusCode(410);
        url = given().auth().oauth2(adminToken()).post("/api/admin/users/" + id + "/invitation").then().extract().path("url");
        String fourth = url.substring(url.indexOf('#') + 1);
        patchUser(id, Map.of("enabled", false)).then().statusCode(200);
        check(fourth, newIp()).then().statusCode(410);
        given().auth().oauth2(adminToken()).post("/api/admin/users/" + id + "/invitation").then().statusCode(409);
    }

    @Test
    void resetLinkForAForgottenPasswordClosesAllSessions() {
        String name = unique("oubli");
        String token = (String) invite(name)[1];
        accept(token, PASSWORD, newIp()).then().statusCode(200);
        String cookie = login(name, PASSWORD, newIp()).then().statusCode(200).extract().cookie("refresh_token");
        long id = given().auth().oauth2(adminToken()).get("/api/admin/users").then().extract()
                .jsonPath().getLong("find { it.username == '" + name + "' }.id");
        String url = given().auth().oauth2(adminToken()).post("/api/admin/users/" + id + "/invitation")
                .then().statusCode(200).body("purpose", equalTo("RESET")).extract().path("url");
        String reset = url.substring(url.indexOf('#') + 1);
        accept(reset, "nouveau-mot-de-passe", newIp()).then().statusCode(200);
        login(name, PASSWORD, newIp()).then().statusCode(401);
        login(name, "nouveau-mot-de-passe", newIp()).then().statusCode(200);
        given().cookie("refresh_token", cookie).post("/api/auth/refresh").then().statusCode(401); // ancienne session fermée
        given().auth().oauth2(adminToken()).get("/api/admin/users").then()
                .body("find { it.username == '" + name + "' }.invitationExpiresAt", nullValue());
    }

    @Test
    void guessingLinksIsRateLimited() {
        String ip = newIp();
        for (int i = 0; i < 5; i++) {
            check("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" + i, ip).then().statusCode(410);
        }
        check("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB", ip).then().statusCode(429).body("error", equalTo("TOO_MANY_ATTEMPTS"));
        // Même un bon lien attend la fin du blocage depuis cette adresse.
        String token = (String) invite(unique("bloque"))[1];
        check(token, ip).then().statusCode(429);
        check(token, newIp()).then().statusCode(200);
    }

    @Test
    void theTokenIsNeverStoredInClear() throws Exception {
        String token = (String) invite(unique("hache"))[1];
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM invitation WHERE token_hash = '" + token + "'")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
        assertFalse(token.contains("#"));
    }
}
