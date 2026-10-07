package fr.plexwish.animeserver.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.login;
import static fr.plexwish.animeserver.auth.AuthTestSupport.newIp;
import static fr.plexwish.animeserver.auth.AuthTestSupport.refresh;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Changement de son mot de passe (S4, ARCHITECTURE §24.5) : web et app, anti brute force, autres sessions fermées. */
@QuarkusTest
class PasswordChangeTest {

    static final String OLD = "old-password-123";
    static final String NEW = "new-password-456";

    private static Response web(String access, String cookie, String ip, String current, String next) {
        var req = given().contentType(ContentType.JSON).header("X-Forwarded-For", ip)
                .body(Map.of("currentPassword", current, "newPassword", next));
        if (access != null) {
            req = req.auth().oauth2(access);
        }
        if (cookie != null) {
            req = req.cookie(AuthResource.COOKIE, cookie);
        }
        return req.post("/api/auth/password");
    }

    private static Response appLogin(String name, String password) {
        return given().contentType(ContentType.JSON).header("X-Forwarded-For", newIp())
                .body(Map.of("login", name, "password", password, "device", "Test")).post("/api/auth/app/login");
    }

    private static Response appRefresh(String token) {
        return given().contentType(ContentType.JSON).body(Map.of("refreshToken", token)).post("/api/auth/app/refresh");
    }

    private static String user() {
        String name = unique("pwd");
        createUser(name, OLD, "USER");
        return name;
    }

    @Test
    void webChangeKeepsThisBrowserAndClosesOtherSessions() {
        String name = user();
        Response a = login(name, OLD, newIp());
        Response b = login(name, OLD, newIp());
        String app = appLogin(name, OLD).path("refreshToken");
        String cookieA = a.getDetailedCookie(AuthResource.COOKIE).getValue();
        String cookieB = b.getDetailedCookie(AuthResource.COOKIE).getValue();

        web(a.path("accessToken"), cookieA, newIp(), OLD, NEW).then().statusCode(200).body("closedSessions", equalTo(2));

        refresh(cookieA).then().statusCode(200);            // ce navigateur reste connecté
        refresh(cookieB).then().statusCode(401);            // l'autre navigateur est déconnecté
        appRefresh(app).then().statusCode(401);             // l'app aussi
        login(name, OLD, newIp()).then().statusCode(401);
        login(name, NEW, newIp()).then().statusCode(200);
    }

    @Test
    void appChangeKeepsTheAppSession() {
        String name = user();
        Response app = appLogin(name, OLD);
        String browserCookie = login(name, OLD, newIp()).getDetailedCookie(AuthResource.COOKIE).getValue();
        Map<String, Object> body = new HashMap<>(Map.of("currentPassword", OLD, "newPassword", NEW, "refreshToken", app.path("refreshToken")));
        given().contentType(ContentType.JSON).auth().oauth2(app.<String>path("accessToken")).body(body)
                .post("/api/auth/app/password").then().statusCode(200).body("closedSessions", equalTo(1));
        appRefresh(app.path("refreshToken")).then().statusCode(200);
        refresh(browserCookie).then().statusCode(401);
        // Réservé à l'app : refusé avec un en-tête Origin (navigateur).
        given().contentType(ContentType.JSON).header("Origin", "https://anime.test").auth().oauth2(app.<String>path("accessToken"))
                .body(Map.of("currentPassword", NEW, "newPassword", "another-password-789"))
                .post("/api/auth/app/password").then().statusCode(403).body("error", equalTo("NATIVE_CLIENT_ONLY"));
    }

    @Test
    void withoutCurrentSessionEverySessionIsClosed() {
        String name = user();
        Response a = login(name, OLD, newIp());
        String cookieA = a.getDetailedCookie(AuthResource.COOKIE).getValue();
        web(a.path("accessToken"), null, newIp(), OLD, NEW).then().statusCode(200).body("closedSessions", equalTo(1));
        refresh(cookieA).then().statusCode(401);
    }

    @Test
    void rulesAreTheSameAsForTheAdmin() {
        String name = user();
        Response a = login(name, OLD, newIp());
        String access = a.path("accessToken");
        String cookie = a.getDetailedCookie(AuthResource.COOKIE).getValue();
        web(access, cookie, newIp(), OLD, "short").then().statusCode(400).body("error", equalTo("WEAK_PASSWORD"));
        web(access, cookie, newIp(), OLD, "é".repeat(40)).then().statusCode(400).body("error", equalTo("PASSWORD_TOO_LONG"));
        web(access, cookie, newIp(), OLD, OLD).then().statusCode(400).body("error", equalTo("SAME_PASSWORD"));
        web(null, cookie, newIp(), OLD, NEW).then().statusCode(401);
        // Rien n'a changé : l'ancien mot de passe marche, la session aussi.
        login(name, OLD, newIp()).then().statusCode(200);
        refresh(cookie).then().statusCode(200);
    }

    @Test
    void wrongCurrentPasswordCountsInTheBruteForceLimiter() {
        String name = user();
        String access = login(name, OLD, newIp()).path("accessToken");
        String ip = newIp();
        for (int i = 0; i < 4; i++) {
            web(access, null, ip, "wrong-password-" + i, NEW).then().statusCode(400).body("error", equalTo("WRONG_PASSWORD"));
        }
        web(access, null, ip, "wrong-password-5", NEW).then().statusCode(400);
        // 5 échecs : ce couple (IP, identifiant) est bloqué, pour le changement comme pour la connexion.
        web(access, null, ip, OLD, NEW).then().statusCode(429).body("error", equalTo("TOO_MANY_ATTEMPTS"));
        login(name, OLD, ip).then().statusCode(429);
        // Depuis une autre IP, l'utilisateur légitime n'est pas bloqué.
        web(access, null, newIp(), OLD, NEW).then().statusCode(200);
    }
}
