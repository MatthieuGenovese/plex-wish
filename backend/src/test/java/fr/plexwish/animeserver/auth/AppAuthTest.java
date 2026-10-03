package fr.plexwish.animeserver.auth;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.newIp;
import static fr.plexwish.animeserver.auth.AuthTestSupport.patchUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Clients natifs (ARCHITECTURE §5.1.1) : refresh token dans le corps, jamais de cookie, rotation, tolérance qui
 * renouvelle le token (réponse perdue), réutilisation détectée, navigateurs refusés, anti brute force partagé.
 */
@QuarkusTest
class AppAuthTest {

    static final String PASSWORD = "app-password-123";

    @Inject
    AgroalDataSource ds;

    private static Response appLogin(String login, String password, String ip) {
        return given().contentType(ContentType.JSON).header("X-Forwarded-For", ip)
                .body(Map.of("login", login, "password", password, "device", "Galaxy S24")).post("/api/auth/app/login");
    }

    private static Response appRefresh(String token) {
        return given().contentType(ContentType.JSON).body(Map.of("refreshToken", token)).post("/api/auth/app/refresh");
    }

    private static String user() {
        String name = unique("app");
        createUser(name, PASSWORD, "USER");
        return name;
    }

    @Test
    void loginReturnsRefreshTokenInBodyAndNoCookie() throws Exception {
        String name = user();
        Response r = appLogin(name, PASSWORD, newIp());
        r.then().statusCode(200).body("accessToken", notNullValue()).body("refreshToken", notNullValue())
                .body("tokenType", equalTo("Bearer")).body("user.username", equalTo(name));
        assertNull(r.getDetailedCookie(AuthResource.COOKIE), "jamais de cookie pour l'app");
        given().auth().oauth2(r.<String>path("accessToken")).get("/api/me").then().statusCode(200);
        assertEquals(1, count(ds, "SELECT count(*) FROM refresh_token t JOIN app_user u ON u.id = t.user_id"
                + " WHERE u.username = '" + name + "' AND t.client = 'ANDROID' AND t.device = 'Galaxy S24'"));
        // Le navigateur, lui, ne reçoit jamais le refresh token dans le corps.
        AuthTestSupport.login(name, PASSWORD, newIp()).then().statusCode(200).body("refreshToken", nullValue());
    }

    @Test
    void rotationAndReuseDetection() throws Exception {
        String name = user();
        String t1 = appLogin(name, PASSWORD, newIp()).path("refreshToken");
        String t2 = appRefresh(t1).then().statusCode(200).body("accessToken", notNullValue()).extract().path("refreshToken");
        assertNotEquals(t1, t2);
        // Réponse perdue : l'app réessaie avec l'ancien token dans la fenêtre de tolérance → nouveau token, pas de coupure.
        String t3 = appRefresh(t1).then().statusCode(200).extract().path("refreshToken");
        assertNotEquals(t2, t3);
        String t4 = appRefresh(t3).then().statusCode(200).extract().path("refreshToken");
        // Hors de la fenêtre : réutilisation = vol probable, toutes les sessions coupées.
        sql("UPDATE refresh_token SET revoked_at = now() - interval '1 hour' WHERE revoked_reason = 'ROTATED'"
                + " AND user_id = (SELECT id FROM app_user WHERE username = '" + name + "')");
        appRefresh(t1).then().statusCode(401).body("error", equalTo("INVALID_REFRESH_TOKEN"));
        appRefresh(t4).then().statusCode(401);
    }

    @Test
    void logoutAndDisabledUserRevoke() {
        String name = user();
        String t = appLogin(name, PASSWORD, newIp()).path("refreshToken");
        given().contentType(ContentType.JSON).body(Map.of("refreshToken", t)).post("/api/auth/app/logout").then().statusCode(204);
        appRefresh(t).then().statusCode(401);

        String other = user();
        String t2 = appLogin(other, PASSWORD, newIp()).path("refreshToken");
        long id = given().auth().oauth2(AuthTestSupport.adminToken()).get("/api/admin/users").then().extract()
                .jsonPath().getLong("find { it.username == '" + other + "' }.id");
        patchUser(id, Map.of("enabled", false)).then().statusCode(200);
        appRefresh(t2).then().statusCode(401);
        appLogin(other, PASSWORD, newIp()).then().statusCode(401).body("error", equalTo("INVALID_CREDENTIALS"));
    }

    @Test
    void browsersAreRefused() {
        String name = user();
        given().contentType(ContentType.JSON).header("Origin", "https://anime.test").header("X-Forwarded-For", newIp())
                .body(Map.of("login", name, "password", PASSWORD)).post("/api/auth/app/login")
                .then().statusCode(403).body("error", equalTo("NATIVE_CLIENT_ONLY")).body(not(containsString("refreshToken")));
        String t = appLogin(name, PASSWORD, newIp()).path("refreshToken");
        given().contentType(ContentType.JSON).header("Origin", "https://evil.example").body(Map.of("refreshToken", t))
                .post("/api/auth/app/refresh").then().statusCode(403);
        appRefresh(t).then().statusCode(200); // pas consommé par la tentative refusée
    }

    @Test
    void bruteForceLockoutGivesTheDelay() {
        String name = user();
        String ip = newIp();
        for (int i = 0; i < 10; i++) {
            appLogin(name, "wrong-" + i, ip);
        }
        appLogin(name, PASSWORD, ip).then().statusCode(429).body("error", equalTo("TOO_MANY_ATTEMPTS"))
                .body("message", containsString("Réessayez dans"));
    }

    @Test
    void validation() {
        given().contentType(ContentType.JSON).body(Map.of("login", "x")).post("/api/auth/app/login").then().statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of()).post("/api/auth/app/refresh").then().statusCode(400);
        appRefresh("not-a-token").then().statusCode(401);
    }

    private void sql(String sql) throws Exception {
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
