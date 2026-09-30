package fr.plexwish.animeserver.auth;

import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;

/**
 * Outils communs aux tests d'authentification. En profil test, 127.0.0.1 est un proxy de confiance :
 * chaque test choisit son IP « client » via X-Forwarded-For, ce qui isole les compteurs anti brute force.
 */
public final class AuthTestSupport {

    public static final String ADMIN = "admin";
    public static final String ADMIN_PASSWORD = "admin-test-password";
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 50_000));

    private AuthTestSupport() {
    }

    /** IP unique par appel (plage de documentation 198.18.0.0/15). */
    public static String newIp() {
        int n = SEQ.incrementAndGet();
        return "198.18." + ((n >> 8) & 0xff) + "." + (n & 0xff);
    }

    public static String unique(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }

    public static Response login(String login, String password, String ip) {
        return given().contentType(ContentType.JSON)
                .header("X-Forwarded-For", ip)
                .body(Map.of("login", login, "password", password))
                .post("/api/auth/login");
    }

    public static String accessToken(String login, String password) {
        return login(login, password, newIp()).then().statusCode(200).extract().path("accessToken");
    }

    public static String adminToken() {
        return accessToken(ADMIN, ADMIN_PASSWORD);
    }

    /** Crée un utilisateur via l'API admin et renvoie son id. */
    public static long createUser(String username, String password, String role) {
        Integer id = given().auth().oauth2(adminToken()).contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", password, "role", role))
                .post("/api/admin/users")
                .then().statusCode(201).extract().path("id");
        return id.longValue();
    }

    public static Response patchUser(long id, Map<String, Object> body) {
        return given().auth().oauth2(adminToken()).contentType(ContentType.JSON)
                .body(body).patch("/api/admin/users/" + id);
    }

    public static Response refresh(String cookie) {
        var req = given();
        if (cookie != null) {
            req = req.cookie(AuthResource.COOKIE, cookie);
        }
        return req.post("/api/auth/refresh");
    }
}
