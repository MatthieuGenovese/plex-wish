package fr.plexwish.animeserver.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.List;
import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.login;
import static fr.plexwish.animeserver.auth.AuthTestSupport.newIp;
import static fr.plexwish.animeserver.auth.AuthTestSupport.patchUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aucun mot de passe, token ou hash dans les logs, même en DEBUG avec le journal d'accès HTTP activé.
 * On déroule les scénarios sensibles puis on cherche chaque secret dans le fichier de log.
 */
@QuarkusTest
@TestProfile(LogLeakTest.VerboseLogs.class)
class LogLeakTest {

    @jakarta.inject.Inject
    fr.plexwish.animeserver.stream.StreamSigner streamSigner;

    public static class VerboseLogs implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.log.level", "DEBUG",
                    "quarkus.log.category.\"fr.plexwish\".level", "DEBUG",
                    "quarkus.log.category.\"com.github.dockerjava\".level", "INFO",
                    "quarkus.log.category.\"org.testcontainers\".level", "INFO",
                    // Format du journal d'accès : celui de application.properties (sans query string).
                    "quarkus.http.access-log.enabled", "true");
        }
    }

    /** Collecte tout ce qui passe par le gestionnaire de logs (même JVM que l'application). */
    static final class Capture extends Handler {
        final StringBuilder text = new StringBuilder();
        int debugRecords;

        @Override
        public synchronized void publish(LogRecord r) {
            // Le client HTTP des tests (RestAssured / Apache HttpClient) journalise ce qu'IL envoie :
            // ce n'est pas l'application, on l'ignore.
            if (r.getLoggerName() != null && r.getLoggerName().startsWith("org.apache.http")) {
                return;
            }
            if (r.getLevel().intValue() < Level.INFO.intValue()) {
                debugRecords++;
            }
            String msg = r.getMessage();
            try {
                msg = new SimpleFormatter().formatMessage(r);
            } catch (RuntimeException ignored) {
                // message brut
            }
            text.append(r.getLoggerName()).append(' ').append(msg).append('\n');
            if (r.getThrown() != null) {
                text.append(r.getThrown()).append('\n');
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    @Test
    void secretsNeverReachTheLogs() {
        Capture capture = new Capture();
        capture.setLevel(Level.ALL);
        Logger root = Logger.getLogger("");
        root.addHandler(capture);
        try {
            scenario(capture);
        } finally {
            root.removeHandler(capture);
        }
    }

    private void scenario(Capture capture) {
        String name = unique("leak");
        String password = "leak-password-" + System.nanoTime();
        String newPassword = "new-leak-password-" + System.nanoTime();
        long id = createUser(name, password, "USER");

        Response ok = login(name, password, newIp());
        String access = ok.path("accessToken");
        String refresh = ok.getDetailedCookie(AuthResource.COOKIE).getValue();
        Response rotated = given().cookie(AuthResource.COOKIE, refresh).post("/api/auth/refresh");
        String refresh2 = rotated.getDetailedCookie(AuthResource.COOKIE).getValue();
        given().auth().oauth2(access).get("/api/me").then().statusCode(200);

        login(name, "wrong-" + password, newIp()).then().statusCode(401);
        given().contentType(ContentType.JSON).body(Map.of("login", name, "password", "x".repeat(300)))
                .post("/api/auth/login").then().statusCode(400); // erreur de validation sur le mot de passe
        patchUser(id, Map.of("password", newPassword)).then().statusCode(200);
        given().cookie(AuthResource.COOKIE, refresh2).post("/api/auth/logout").then().statusCode(204);
        String adminAccess = adminToken();

        // URL de lecture signée : la signature ne doit apparaître nulle part, même avec le journal d'accès.
        String exp = String.valueOf(java.time.Instant.now().plusSeconds(600).getEpochSecond());
        String sig = streamSigner.signature(424242, id, Long.parseLong(exp));
        given().get("/api/stream/424242?u=" + id + "&exp=" + exp + "&sig=" + sig).then().statusCode(404);

        String logs;
        synchronized (capture) {
            logs = capture.text.toString();
        }
        assertTrue(logs.contains(name), "le test doit bien lire les logs de l'application");
        assertTrue(logs.contains("POST /api/auth/login"), "le journal d'accès HTTP doit être capturé");
        assertTrue(logs.contains("GET /api/stream/424242"), "la lecture doit apparaître dans le journal d'accès");
        assertTrue(capture.debugRecords > 0, "les logs DEBUG doivent être actifs");
        for (String secret : List.of(password, "wrong-" + password, newPassword, "x".repeat(300), access, adminAccess,
                refresh, refresh2, RefreshTokenService.hash(refresh), "admin-test-password", "$2a$12$", "$2y$12$", sig)) {
            assertFalse(logs.contains(secret), "secret trouvé dans les logs : " + secret.substring(0, Math.min(12, secret.length())) + "…");
        }
    }
}
