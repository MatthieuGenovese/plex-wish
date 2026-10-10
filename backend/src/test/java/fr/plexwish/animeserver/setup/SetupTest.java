package fr.plexwish.animeserver.setup;

import fr.plexwish.animeserver.auth.AuthTestSupport;
import fr.plexwish.animeserver.tmdb.TmdbCredentials;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assistant de premier lancement et portes d'entrée (D1.3). La base partagée est remise en « installation non
 * terminée, aucun administrateur » le temps de chaque test, puis rétablie.
 */
@QuarkusTest
class SetupTest {

    static final String LAN_IP = "192.168.1.20";
    static final String INTERNET_IP = "203.0.113.5";
    static final String PASSWORD = "mot-de-passe-de-test-long";

    @Inject
    AgroalDataSource ds;
    @Inject
    SetupState setup;
    @Inject
    TmdbCredentials tmdb;
    @Inject
    SetupConfig config;

    private final List<Long> demoted = new ArrayList<>();
    private String createdAdmin;

    @BeforeEach
    void notInstalled() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT id FROM app_user WHERE role = 'ADMIN'")) {
                while (rs.next()) {
                    demoted.add(rs.getLong(1));
                }
            }
            st.executeUpdate("UPDATE app_user SET role = 'USER' WHERE role = 'ADMIN'");
            st.executeUpdate("DELETE FROM app_setting WHERE key LIKE 'setup.%'");
        }
        setup.reload();
        Files.deleteIfExists(Path.of(config.secretsDir(), SecretStore.TMDB));
    }

    @AfterEach
    void restore() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            if (createdAdmin != null) {
                st.executeUpdate("DELETE FROM refresh_token WHERE user_id IN (SELECT id FROM app_user WHERE username = '" + createdAdmin + "')");
                st.executeUpdate("DELETE FROM app_user WHERE username = '" + createdAdmin + "'");
            }
            for (long id : demoted) {
                st.executeUpdate("UPDATE app_user SET role = 'ADMIN' WHERE id = " + id);
            }
            st.executeUpdate("DELETE FROM app_setting WHERE key LIKE 'disk.%' OR key = 'remux.cap_gb'");
        }
        setup.markCompleted("fin du test");
        setup.reload();
        Files.deleteIfExists(Path.of(config.secretsDir(), SecretStore.TMDB));
    }

    static RequestSpecification from(String entry, String ip) {
        return given().header(Entry.HEADER, entry).header("X-Forwarded-For", ip).contentType(ContentType.JSON);
    }

    private String createAdmin() {
        createdAdmin = AuthTestSupport.unique("chef");
        return from("lan", LAN_IP).body(Map.of("username", createdAdmin, "password", PASSWORD))
                .post("/api/setup/admin").then().statusCode(200).extract().path("accessToken");
    }

    @Test
    void beforeTheEndOnlyTheWizardAnswersFromTheLocalNetwork() {
        from("public", INTERNET_IP).get("/api/setup/status").then().statusCode(200)
                .body("installed", equalTo(false)).body("entry", equalTo("public")).body("adminExists", equalTo(false));
        // Le site entier répond « installation en cours », quelle que soit la porte, même avec un jeton valide.
        from("public", INTERNET_IP).get("/api/anime").then().statusCode(503).body("error", equalTo("SETUP_REQUIRED"));
        from("lan", LAN_IP).post("/api/auth/login").then().statusCode(503);
        // L'assistant : jamais par Internet, jamais depuis une adresse publique.
        Map<String, String> admin = Map.of("username", "intrus", "password", PASSWORD);
        from("public", LAN_IP).body(admin).post("/api/setup/admin").then().statusCode(403).body("error", equalTo("SETUP_LOCAL_ONLY"));
        from("lan", INTERNET_IP).body(admin).post("/api/setup/admin").then().statusCode(403);
        from("public", INTERNET_IP).get("/api/setup/checks").then().statusCode(403);
        from("lan", LAN_IP).get("/api/setup/checks").then().statusCode(200)
                .body("id", hasItems("media", "posters", "remux", "web", "ffmpeg", "disk", "database", "secrets"));
    }

    @Test
    void theAdministratorIsCreatedOnceWithTheChosenPassword() {
        from("lan", LAN_IP).body(Map.of("username", "court", "password", "court")).post("/api/setup/admin")
                .then().statusCode(400).body("error", equalTo("WEAK_PASSWORD"));
        String token = createAdmin();
        from("lan", LAN_IP).get("/api/setup/status").then().body("adminExists", equalTo(true));
        // Deuxième création : refusée. Reprise de l'assistant : connexion de cet admin.
        from("lan", LAN_IP).body(Map.of("username", "second", "password", PASSWORD)).post("/api/setup/admin")
                .then().statusCode(409).body("error", equalTo("ADMIN_EXISTS"));
        from("lan", LAN_IP).body(Map.of("login", createdAdmin, "password", PASSWORD)).post("/api/setup/login")
                .then().statusCode(200).body("user.role", equalTo("ADMIN"));
        // Les étapes suivantes exigent le jeton de l'admin.
        from("lan", LAN_IP).get("/api/setup/disk").then().statusCode(401);
        from("lan", LAN_IP).auth().oauth2(token).get("/api/setup/disk").then().statusCode(200).body("saved", equalTo(false));
    }

    @Test
    void concurrentCreationsOnlyOneWins() throws Exception {
        createdAdmin = "course";
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            calls.add(() -> from("lan", LAN_IP).body(Map.of("username", "course", "password", PASSWORD))
                    .post("/api/setup/admin").statusCode());
        }
        int ok = 0;
        for (Future<Integer> f : pool.invokeAll(calls)) {
            ok += f.get() == 200 ? 1 : 0;
        }
        pool.shutdown();
        assertEquals(1, ok, "un seul compte administrateur créé");
    }

    @Test
    void settingsThenFinish() throws Exception {
        String token = createAdmin();
        RequestSpecification admin = from("lan", LAN_IP).auth().oauth2(token);
        from("lan", LAN_IP).auth().oauth2(token).body(Map.of("warnGb", 20, "criticalGb", 20, "remuxCapGb", 30))
                .put("/api/setup/disk").then().statusCode(400).body("error", equalTo("DISK_THRESHOLDS_INVALID"));
        from("lan", LAN_IP).auth().oauth2(token).body(Map.of("warnGb", 40, "criticalGb", 15, "remuxCapGb", 30))
                .put("/api/setup/disk").then().statusCode(200).body("saved", equalTo(true)).body("current.remuxCapGb", equalTo(30));
        // Cache du lecteur web (phase 10) : plafond réglable, volume mesuré (WEB_CACHE_PATH peut être sur un autre volume).
        from("lan", LAN_IP).auth().oauth2(token).body(Map.of("warnGb", 40, "criticalGb", 15, "remuxCapGb", 30, "webCapGb", 0))
                .put("/api/setup/disk").then().statusCode(400).body("error", equalTo("DISK_THRESHOLDS_INVALID"));
        from("lan", LAN_IP).auth().oauth2(token).body(Map.of("warnGb", 40, "criticalGb", 15, "remuxCapGb", 30, "webCapGb", 25))
                .put("/api/setup/disk").then().statusCode(200).body("current.webCapGb", equalTo(25))
                .body("proposed.webCapGb", org.hamcrest.Matchers.greaterThan(0))
                .body("webCache.totalBytes", org.hamcrest.Matchers.notNullValue());
        // Sans webCapGb (ancien client) : le plafond web ne change pas.
        from("lan", LAN_IP).auth().oauth2(token).body(Map.of("warnGb", 40, "criticalGb", 15, "remuxCapGb", 30))
                .put("/api/setup/disk").then().statusCode(200).body("current.webCapGb", equalTo(25));

        // Clé TMDB : rangée en secret (600), jamais renvoyée, utilisée tout de suite.
        String key = "eyJhbGciOiJIUzI1NiJ9.cle-tmdb-de-test-0123456789";
        Response r = admin.body(Map.of("token", key)).put("/api/setup/tmdb");
        r.then().statusCode(200).body("configured", equalTo(true)).body("source", equalTo("interface"));
        assertFalse(r.asString().contains(key));
        Path f = Path.of(config.secretsDir(), SecretStore.TMDB);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
        assertEquals(key, tmdb.current().orElseThrow().bearer());

        // Page ouverte à l'adresse locale du NAS (même origine que la requête) : acceptée ; page d'un autre site : refusée.
        from("lan", LAN_IP).auth().oauth2(token).header("Origin", "http://evil.example")
                .body(Map.of("warnGb", 40, "criticalGb", 15, "remuxCapGb", 30)).put("/api/setup/disk")
                .then().statusCode(403); // filtre CORS de Quarkus ou OriginCheck : refusé dans les deux cas
        from("lan", LAN_IP).auth().oauth2(token).header("Origin", "http://localhost:" + io.restassured.RestAssured.port)
                .body(Map.of("warnGb", 40, "criticalGb", 15, "remuxCapGb", 30)).put("/api/setup/disk").then().statusCode(200);
    }

    @Test
    void afterTheEndTheWizardIsClosedAndTheLocalDoorOnlyPointsToThePublicAddress() {
        String token = createAdmin();
        from("lan", LAN_IP).auth().oauth2(token).post("/api/setup/finish").then().statusCode(200).body("installed", equalTo(true));
        from("lan", LAN_IP).body(Map.of("username", "tard", "password", PASSWORD)).post("/api/setup/admin")
                .then().statusCode(404).body("error", equalTo("SETUP_DONE"));
        from("lan", LAN_IP).auth().oauth2(token).get("/api/anime").then().statusCode(403)
                .body("error", equalTo("USE_PUBLIC_URL")).body("message", startsWith("Le site s'ouvre à son adresse publique"));
        from("public", INTERNET_IP).auth().oauth2(token).get("/api/anime").then().statusCode(200);
        from("lan", LAN_IP).get("/api/setup/status").then().statusCode(200).body("installed", equalTo(true));
    }

    @Test
    void sameOriginRule() {
        assertTrue(fr.plexwish.animeserver.auth.OriginCheckAccess.sameOrigin("http://192.168.1.10:8080", "192.168.1.10:8080"));
        assertFalse(fr.plexwish.animeserver.auth.OriginCheckAccess.sameOrigin("http://evil.example", "192.168.1.10:8080"));
        assertFalse(fr.plexwish.animeserver.auth.OriginCheckAccess.sameOrigin("http://192.168.1.10:8080", null));
    }
}
