package fr.plexwish.animeserver.auth;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.ADMIN;
import static fr.plexwish.animeserver.auth.AuthTestSupport.ADMIN_PASSWORD;
import static fr.plexwish.animeserver.auth.AuthTestSupport.accessToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.login;
import static fr.plexwish.animeserver.auth.AuthTestSupport.newIp;
import static fr.plexwish.animeserver.auth.AuthTestSupport.patchUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.refresh;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Login, /api/me, refresh avec rotation et fenêtre de tolérance, logout, compte désactivé. */
@QuarkusTest
class AuthFlowTest {

    private static final String PASSWORD = "correct horse battery";

    @Inject
    AgroalDataSource dataSource;

    private static String refreshCookie(Response r) {
        return r.getDetailedCookie(AuthResource.COOKIE).getValue();
    }

    // --- Login -------------------------------------------------------------------------------

    @Test
    void loginReturnsAccessTokenAndHardenedRefreshCookie() {
        Response r = login(ADMIN, ADMIN_PASSWORD, newIp());
        r.then().statusCode(200)
                .body("accessToken", notNullValue())
                .body("tokenType", equalTo("Bearer"))
                .body("expiresIn", equalTo(900))
                .body("user.username", equalTo(ADMIN))
                .body("user.role", equalTo("ADMIN"))
                .body("user.passwordHash", nullValue())
                .body("refreshToken", nullValue()); // jamais dans le corps : seulement dans le cookie
        String setCookie = r.getHeader("Set-Cookie");
        assertNotNull(setCookie);
        org.hamcrest.MatcherAssert.assertThat(setCookie, containsString("HttpOnly"));
        org.hamcrest.MatcherAssert.assertThat(setCookie, containsString("Secure"));
        org.hamcrest.MatcherAssert.assertThat(setCookie, containsString("SameSite=Strict"));
        org.hamcrest.MatcherAssert.assertThat(setCookie, containsString("Path=/api/auth"));
        org.hamcrest.MatcherAssert.assertThat(setCookie, containsString("Max-Age=2592000"));
    }

    @Test
    void loginByEmailIgnoringCase() {
        String name = unique("mail");
        given().auth().oauth2(AuthTestSupport.adminToken()).contentType(ContentType.JSON)
                .body(Map.of("username", name, "email", name + "@Example.org", "password", PASSWORD, "role", "USER"))
                .post("/api/admin/users").then().statusCode(201);
        login(name.toUpperCase() + "@EXAMPLE.ORG", PASSWORD, newIp()).then().statusCode(200)
                .body("user.username", equalTo(name));
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameNeutralAnswer() {
        String wrong = login(ADMIN, "not the password", newIp()).then().statusCode(401)
                .body("error", equalTo("INVALID_CREDENTIALS")).extract().asString();
        String unknown = login("nobody-" + System.nanoTime(), "not the password", newIp()).then().statusCode(401)
                .extract().asString();
        assertEquals(wrong, unknown);
    }

    @Test
    void invalidLoginPayloadIs400() {
        given().contentType(ContentType.JSON).body(Map.of("password", "x"))
                .post("/api/auth/login").then().statusCode(400).body("error", equalTo("VALIDATION_ERROR"));
    }

    // --- /api/me -----------------------------------------------------------------------------

    @Test
    void meRequiresAValidToken() {
        given().auth().oauth2(accessToken(ADMIN, ADMIN_PASSWORD)).get("/api/me").then()
                .statusCode(200).body("username", equalTo(ADMIN)).body("role", equalTo("ADMIN"));
        given().get("/api/me").then().statusCode(401);
        given().auth().oauth2("not.a.jwt").get("/api/me").then().statusCode(401);
        // Token signé avec un autre secret : refusé.
        String forged = io.smallrye.jwt.build.Jwt.issuer("anime-server").subject("1").upn(ADMIN)
                .groups("ADMIN").signWithSecret("another-secret-another-secret-12345");
        given().auth().oauth2(forged).get("/api/me").then().statusCode(401);
    }

    // --- Refresh -----------------------------------------------------------------------------

    @Test
    void refreshRotatesTheToken() {
        String first = refreshCookie(login(ADMIN, ADMIN_PASSWORD, newIp()));
        Response r = refresh(first);
        r.then().statusCode(200).body("accessToken", notNullValue());
        String second = refreshCookie(r);
        assertNotEquals(first, second);
        refresh(second).then().statusCode(200);
    }

    @Test
    void reuseInsideGraceWindowGivesAccessTokenWithoutTouchingTheCookie() {
        String first = refreshCookie(login(ADMIN, ADMIN_PASSWORD, newIp()));
        String second = refreshCookie(refresh(first));
        // Deuxième onglet qui présente encore l'ancien cookie juste après la rotation.
        Response concurrent = refresh(first);
        concurrent.then().statusCode(200).body("accessToken", notNullValue());
        assertEquals(null, concurrent.getHeader("Set-Cookie"));
        // Et la session courante n'a pas été coupée.
        refresh(second).then().statusCode(200);
    }

    @Test
    void reuseOutsideGraceWindowRevokesEverySessionOfTheUser() throws SQLException {
        String name = unique("reuse");
        createUser(name, PASSWORD, "USER");
        String otherDevice = refreshCookie(login(name, PASSWORD, newIp()));
        String first = refreshCookie(login(name, PASSWORD, newIp()));
        String second = refreshCookie(refresh(first));

        ageRevocation(first, 60); // la rotation date d'il y a 60 s (> fenêtre de 20 s)

        refresh(first).then().statusCode(401).body("error", equalTo("INVALID_REFRESH_TOKEN"));
        refresh(second).then().statusCode(401);
        refresh(otherDevice).then().statusCode(401);
        login(name, PASSWORD, newIp()).then().statusCode(200); // le compte lui-même reste utilisable
    }

    @Test
    void refreshWithoutOrWithUnknownCookieIs401AndClearsTheCookie() {
        refresh(null).then().statusCode(401);
        refresh("garbage").then().statusCode(401)
                .header("Set-Cookie", containsString("Max-Age=0"));
    }

    // --- Logout ------------------------------------------------------------------------------

    @Test
    void logoutRevokesTheRefreshTokenAndClearsTheCookie() {
        String cookie = refreshCookie(login(ADMIN, ADMIN_PASSWORD, newIp()));
        given().cookie(AuthResource.COOKIE, cookie).post("/api/auth/logout").then()
                .statusCode(204)
                .header("Set-Cookie", containsString("Max-Age=0"));
        refresh(cookie).then().statusCode(401);
        // Logout sans cookie : idempotent.
        given().post("/api/auth/logout").then().statusCode(204);
    }

    // --- Comptes désactivés / mot de passe réinitialisé -----------------------------------------

    @Test
    void disabledUserCannotLoginRefreshOrUseMe() {
        String name = unique("disabled");
        long id = createUser(name, PASSWORD, "USER");
        Response session = login(name, PASSWORD, newIp());
        String access = session.path("accessToken");
        String cookie = refreshCookie(session);

        patchUser(id, Map.of("enabled", false)).then().statusCode(200).body("enabled", equalTo(false));

        login(name, PASSWORD, newIp()).then().statusCode(401).body("error", equalTo("INVALID_CREDENTIALS"));
        refresh(cookie).then().statusCode(401);
        given().auth().oauth2(access).get("/api/me").then().statusCode(401);

        patchUser(id, Map.of("enabled", true)).then().statusCode(200);
        login(name, PASSWORD, newIp()).then().statusCode(200);
    }

    @Test
    void passwordResetByAdminEndsExistingSessions() {
        String name = unique("reset");
        long id = createUser(name, PASSWORD, "USER");
        String cookie = refreshCookie(login(name, PASSWORD, newIp()));
        patchUser(id, Map.of("password", "a brand new password")).then().statusCode(200);
        refresh(cookie).then().statusCode(401);
        login(name, PASSWORD, newIp()).then().statusCode(401);
        login(name, "a brand new password", newIp()).then().statusCode(200);
    }

    @Test
    void cookieIsNeverReturnedInClearInTheDatabase() throws SQLException {
        String cookie = refreshCookie(login(ADMIN, ADMIN_PASSWORD, newIp()));
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT count(*) FROM refresh_token WHERE token_hash = ? OR token_hash = ?")) {
            st.setString(1, cookie);
            st.setString(2, RefreshTokenService.hash(cookie));
            var rs = st.executeQuery();
            rs.next();
            assertEquals(1, rs.getInt(1)); // trouvé par son hash uniquement
        }
        org.hamcrest.MatcherAssert.assertThat(RefreshTokenService.hash(cookie), not(equalToIgnoringCase(cookie)));
    }

    private void ageRevocation(String cookie, int seconds) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "UPDATE refresh_token SET revoked_at = revoked_at - make_interval(secs => ?) WHERE token_hash = ?")) {
            st.setInt(1, seconds);
            st.setString(2, RefreshTokenService.hash(cookie));
            assertEquals(1, st.executeUpdate());
        }
    }
}
