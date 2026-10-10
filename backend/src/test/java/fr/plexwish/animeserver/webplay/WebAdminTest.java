package fr.plexwish.animeserver.webplay;

import fr.plexwish.animeserver.setup.AppSettings;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.accessToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Administration du lecteur web (10.3.4, faux outils) : état, réglages, « Préparer l'animé pour le navigateur »,
 * relancer, annuler ; pastilles « Prêt » / « En préparation » pour les utilisateurs. Réservé à l'admin, sans chemin.
 */
@QuarkusTest
class WebAdminTest {

    static final Path ROOT = Path.of("target/test-library");

    @Inject
    AgroalDataSource ds;
    @Inject
    WebPrepService prep;
    @Inject
    WebSettings webSettings;
    @Inject
    AppSettings settings;

    String admin;
    String user;

    @BeforeEach
    void setUp() throws Exception {
        WebPlaybackTest.installFakeTools();
        truncateLibrary(ds);
        sql("DELETE FROM web_job");
        sql("DELETE FROM app_setting WHERE key LIKE 'web.%'");
        settings.forget();
        deleteTree(ROOT);
        deleteTree(Path.of("target/test-web-cache"));
        prep.checkTools();
        webSettings.nightForTests(false);
        admin = adminToken();
        String name = unique("webadm");
        createUser(name, "web-admin-test-pw", "USER");
        user = accessToken(name, "web-admin-test-pw");
        for (String f : new String[]{"Days/Days - S01E01.mp4", "Days/Days - S01E02.mp4", "Air Gear/Air Gear - S01E01.avi"}) {
            Path p = ROOT.resolve(f);
            Files.createDirectories(p.getParent());
            Files.write(p, new byte[1000]);
        }
        assertEquals("SUCCESS", scan(true).getString("status"));
    }

    @AfterEach
    void tearDown() {
        webSettings.nightForTests(null);
    }

    private void sql(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private long episode(String fileName) throws Exception {
        return count(ds, "SELECT e.id FROM episode e JOIN media_file f ON f.id = e.media_file_id WHERE f.file_name = '" + fileName + "'");
    }

    private long fileId(String fileName) throws Exception {
        return count(ds, "SELECT id FROM media_file WHERE file_name = '" + fileName + "'");
    }

    @Test
    void onlyTheAdminSeesAndChangesIt() {
        given().auth().oauth2(user).get("/api/admin/web").then().statusCode(403);
        given().get("/api/admin/web").then().statusCode(401);
        given().auth().oauth2(admin).get("/api/admin/web").then().statusCode(200)
                .body("settings.maxHeight", equalTo(720)).body("settings.preventive", equalTo(true))
                .body("settings.preventiveVideo", equalTo(false)).body("usable", equalTo(true));
        given().auth().oauth2(admin).contentType("application/json").body(Map.of("maxHeight", 900, "preventive", true, "preventiveVideo", false))
                .put("/api/admin/web/settings").then().statusCode(400);
        given().auth().oauth2(admin).contentType("application/json").body(Map.of("maxHeight", 1080, "preventive", false, "preventiveVideo", true))
                .put("/api/admin/web/settings").then().statusCode(200).body("maxHeight", equalTo(1080));
        given().auth().oauth2(admin).get("/api/admin/web").then().body("settings.preventive", equalTo(false));
        given().auth().oauth2(user).contentType("application/json").body(Map.of("maxHeight", 720, "preventive", true, "preventiveVideo", false))
                .put("/api/admin/web/settings").then().statusCode(403);
    }

    @Test
    void prepareAnAnimeThenRetryAndCancel() throws Exception {
        long animeId = count(ds, "SELECT a.id FROM anime a WHERE a.title = 'Air Gear'");
        given().auth().oauth2(admin).post("/api/admin/web/anime/" + animeId + "/prepare").then().statusCode(200)
                .body("episodes", equalTo(1)).body("queued", equalTo(1));
        long avi = fileId("Air Gear - S01E01.avi");
        assertEquals(1, count(ds, "SELECT count(*) FROM web_job WHERE kind = 'BASE' AND priority = 1 AND media_file_id = " + avi));
        // Préparation de base faite pour l'admin : la conversion suit d'elle-même (même la vidéo).
        assertEquals(true, prep.processNext());
        assertEquals(1, count(ds, "SELECT count(*) FROM web_job WHERE kind = 'CONV' AND priority = 1 AND status = 'QUEUED'"));
        given().auth().oauth2(admin).get("/api/admin/web").then().statusCode(200)
                .body("queued", equalTo(1)).body("queue[0].kind", equalTo("CONV"))
                .body("queue[0].episode.animeTitle", equalTo("Air Gear")).body("queue[0].episode.episodeNumber", equalTo(1))
                .body(not(containsString("test-library")));

        // Échec définitif simulé : « Relancer » remet en file avec ses essais à zéro.
        sql("UPDATE web_job SET status = 'FAILED', attempts = 3, error = 'conversion impossible', next_attempt_at = NULL WHERE kind = 'CONV'");
        given().auth().oauth2(admin).get("/api/admin/web").then().body("failures[0].error", equalTo("conversion impossible"))
                .body("failures[0].attempts", equalTo(3));
        given().auth().oauth2(admin).post("/api/admin/web/jobs/" + avi + "/CONV/retry").then().statusCode(204);
        assertEquals(1, count(ds, "SELECT count(*) FROM web_job WHERE kind = 'CONV' AND status = 'QUEUED' AND attempts = 0"));
        // Annuler : la conversion disparaît de la file.
        given().auth().oauth2(admin).delete("/api/admin/web/jobs/" + avi + "/CONV").then().statusCode(204);
        assertEquals(0, count(ds, "SELECT count(*) FROM web_job WHERE kind = 'CONV'"));
        given().auth().oauth2(admin).delete("/api/admin/web/jobs/" + avi + "/CONV").then().statusCode(404);
        given().auth().oauth2(admin).post("/api/admin/web/jobs/" + avi + "/AUTRE/retry").then().statusCode(404);
        given().auth().oauth2(admin).post("/api/admin/web/anime/999999/prepare").then().statusCode(404);
        given().auth().oauth2(user).post("/api/admin/web/anime/" + animeId + "/prepare").then().statusCode(403);
    }

    @Test
    void badgesTellWhatIsReadyOrBeingPrepared() throws Exception {
        long e1 = episode("Days - S01E01.mp4");
        long e2 = episode("Days - S01E02.mp4");
        long avi = episode("Air Gear - S01E01.avi");
        given().auth().oauth2(user).queryParam("caps", "h264,aac").get("/api/episodes/" + e1 + "/web-playback").then().statusCode(202);
        prep.processNext();
        given().auth().oauth2(user).queryParam("caps", "h264,aac").get("/api/episodes/" + e2 + "/web-playback").then().statusCode(202);
        // Préparé la nuit : pas « en préparation » le jour.
        given().auth().oauth2(admin).post("/api/admin/web/anime/" + count(ds, "SELECT id FROM anime WHERE title = 'Air Gear'") + "/prepare");
        sql("UPDATE web_job SET priority = 2 WHERE media_file_id = " + fileId("Air Gear - S01E01.avi"));
        given().auth().oauth2(user).queryParam("episodes", e1 + "," + e2 + "," + avi).get("/api/web-status").then().statusCode(200)
                .body(String.valueOf(e1), equalTo("READY")).body(String.valueOf(e2), equalTo("PREPARING"))
                .body("$", not(hasKey(String.valueOf(avi))));
        given().auth().oauth2(user).queryParam("episodes", "1;DROP").get("/api/web-status").then().statusCode(400);
        given().queryParam("episodes", String.valueOf(e1)).get("/api/web-status").then().statusCode(401);
    }
}
