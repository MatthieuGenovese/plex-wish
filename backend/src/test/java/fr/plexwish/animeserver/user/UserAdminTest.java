package fr.plexwish.animeserver.user;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.ADMIN;
import static fr.plexwish.animeserver.auth.AuthTestSupport.ADMIN_PASSWORD;
import static fr.plexwish.animeserver.auth.AuthTestSupport.accessToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.login;
import static fr.plexwish.animeserver.auth.AuthTestSupport.newIp;
import static fr.plexwish.animeserver.auth.AuthTestSupport.patchUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;

/** /api/admin/users : liste, création, activation/désactivation, rôle, reset de mot de passe. */
@QuarkusTest
class UserAdminTest {

    private static io.restassured.response.Response create(Map<String, Object> body) {
        return given().auth().oauth2(adminToken()).contentType(ContentType.JSON).body(body).post("/api/admin/users");
    }

    private static Map<String, Object> user(String name, String password) {
        Map<String, Object> m = new HashMap<>();
        m.put("username", name);
        m.put("password", password);
        m.put("role", "USER");
        return m;
    }

    @Test
    void createAndListWithoutEverExposingHashes() {
        String name = unique("list");
        create(user(name, "0123456789ab")).then().statusCode(201)
                .header("Location", org.hamcrest.Matchers.containsString("/api/admin/users/"))
                .body("username", equalTo(name)).body("enabled", equalTo(true)).body("$", not(hasKey("passwordHash")));
        given().auth().oauth2(adminToken()).get("/api/admin/users").then().statusCode(200)
                .body("username", hasItem(name))
                .body("[0]", not(hasKey("passwordHash")));
    }

    @Test
    void usernameIsUniqueIgnoringCase() {
        String name = unique("dup");
        create(user(name, "0123456789ab")).then().statusCode(201);
        create(user(name.toUpperCase(), "0123456789ab")).then().statusCode(409).body("error", equalTo("USERNAME_TAKEN"));
    }

    @Test
    void inputValidation() {
        create(user("a", "0123456789ab")).then().statusCode(400).body("error", equalTo("VALIDATION_ERROR"));
        create(user("bad name!", "0123456789ab")).then().statusCode(400);
        create(user(unique("short"), "123")).then().statusCode(400).body("error", equalTo("WEAK_PASSWORD"));
        // 73 octets : au-delà, bcrypt tronquerait en silence.
        create(user(unique("long"), "é".repeat(37))).then().statusCode(400).body("error", equalTo("PASSWORD_TOO_LONG"));
        Map<String, Object> badMail = user(unique("mail"), "0123456789ab");
        badMail.put("email", "not-an-email");
        create(badMail).then().statusCode(400);
        Map<String, Object> noRole = user(unique("role"), "0123456789ab");
        noRole.remove("role");
        create(noRole).then().statusCode(400);
    }

    @Test
    void roleCanBeChanged() {
        String name = unique("promo");
        long id = createUser(name, "0123456789ab", "USER");
        patchUser(id, Map.of("role", "ADMIN")).then().statusCode(200).body("role", equalTo("ADMIN"));
        given().auth().oauth2(accessToken(name, "0123456789ab")).get("/api/admin/users").then().statusCode(200);
        patchUser(id, Map.of("role", "USER")).then().statusCode(200).body("role", equalTo("USER"));
    }

    @Test
    void adminCannotLockThemselvesOut() {
        long myId = ((Integer) login(ADMIN, ADMIN_PASSWORD, newIp()).path("user.id")).longValue();
        patchUser(myId, Map.of("enabled", false)).then().statusCode(409).body("error", equalTo("SELF_LOCKOUT"));
        patchUser(myId, Map.of("role", "USER")).then().statusCode(409);
        login(ADMIN, ADMIN_PASSWORD, newIp()).then().statusCode(200).body("user.role", equalTo("ADMIN"));
    }

    @Test
    void unknownUserIs404AndWeakResetPasswordIs400() {
        patchUser(999_999, Map.of("enabled", false)).then().statusCode(404).body("error", equalTo("USER_NOT_FOUND"));
        long id = createUser(unique("weak"), "0123456789ab", "USER");
        patchUser(id, Map.of("password", "short")).then().statusCode(400).body("error", equalTo("WEAK_PASSWORD"));
    }
}
