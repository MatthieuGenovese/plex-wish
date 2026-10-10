package fr.plexwish.animeserver.media;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.accessToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.touch;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Analyse ffprobe (avec un faux ffprobe) : classification, durée des épisodes, fichiers modifiés, reprise, échecs,
 * délai maximal, admin, aucun chemin hors de l'API admin ; test à blanc du remux (faux ffmpeg).
 */
@QuarkusTest
class MediaProbeTest {

    static final Path ROOT = Path.of("target/test-library");

    @Inject
    AgroalDataSource ds;
    @Inject
    MediaProbeService service;
    @Inject
    RemuxTestService remuxTest;

    @BeforeEach
    void library() throws Exception {
        FakeTools.install();
        remuxTest.stop();
        truncateLibrary(ds);
        deleteTree(ROOT);
        touch(ROOT, "Air Gear/Air Gear - 1x05.avi");
        touch(ROOT, "Da Capo/Da Capo - 1x09.ogm");
        touch(ROOT, "Days/Days - S01E03.mkv");
        touch(ROOT, "Baka/Baka - S01E12.mp4");
        touch(ROOT, "Frieren/Frieren hevc10 - S01E01.mkv");
        touch(ROOT, "Broken/Broken broken - 01.mkv");
        assertEquals("SUCCESS", scan().getString("status"));
    }

    private int processAll() throws Exception {
        int n = 0;
        for (var w = service.nextPending(); w.isPresent(); w = service.nextPending()) {
            service.probe(w.get());
            if (++n > 50) {
                throw new AssertionError("l'analyse ne s'arrête pas");
            }
        }
        return n;
    }

    private void sql(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private String probe(String file, String column) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT p." + column + " FROM media_probe p JOIN media_file f ON f.id = p.media_file_id"
                     + " WHERE f.file_name = '" + file + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String userToken() {
        String name = unique("mediauser");
        createUser(name, "media-user-password", "USER");
        return accessToken(name, "media-user-password");
    }

    private static io.restassured.specification.RequestSpecification admin() {
        return given().auth().oauth2(adminToken());
    }

    @Test
    void probesClassifiesAndFillsEpisodeDurations() throws Exception {
        assertEquals(6, processAll());
        assertEquals("REMUX", probe("Air Gear - 1x05.avi", "android_class"));
        assertEquals("REMUX", probe("Da Capo - 1x09.ogm", "android_class"));
        assertEquals("DIRECT", probe("Days - S01E03.mkv", "android_class"));
        assertEquals("DIRECT", probe("Baka - S01E12.mp4", "android_class"));
        assertEquals("t", probe("Baka - S01E12.mp4", "browser_playable"));
        assertEquals("HEVC 10 bits : selon le navigateur", probe("Frieren hevc10 - S01E01.mkv", "browser_reasons"));
        assertEquals("10", probe("Frieren hevc10 - S01E01.mkv", "video_bit_depth"));
        assertEquals("mpeg4", probe("Air Gear - 1x05.avi", "video_codec"));
        assertTrue(probe("Days - S01E03.mkv", "subtitles").contains("hdmv_pgs_subtitle"));
        // Échec : raison sans chemin du serveur.
        assertEquals("FAILED", probe("Broken broken - 01.mkv", "status"));
        String error = probe("Broken broken - 01.mkv", "error");
        assertTrue(error.contains("code 1") && error.contains("Invalid data"), error);
        assertFalse(error.contains("test-library") || error.contains("Broken broken"), error);
        // Durée des épisodes (arrondie) : visible dans les API de la bibliothèque, sans aucun chemin.
        assertEquals(1455, count(ds, "SELECT e.duration_seconds FROM episode e JOIN media_file f ON f.id = e.media_file_id"
                + " WHERE f.file_name = 'Air Gear - 1x05.avi'"));
        long animeId = count(ds, "SELECT id FROM anime WHERE title = 'Air Gear'");
        long seasonId = count(ds, "SELECT id FROM season WHERE anime_id = " + animeId);
        long episodeId = count(ds, "SELECT id FROM episode WHERE season_id = " + seasonId);
        String user = userToken();
        for (String path : List.of("/api/anime/" + animeId, "/api/seasons/" + seasonId + "/episodes", "/api/episodes/" + episodeId,
                "/api/anime?size=50")) {
            Response r = given().auth().oauth2(user).get(path);
            assertEquals(200, r.statusCode(), path);
            String body = r.asString();
            assertFalse(body.contains("test-library") || body.contains("Air Gear/") || body.contains(".avi")
                    || body.contains("relativePath") || body.contains("ffprobe"), path + " : " + body);
        }
        given().auth().oauth2(user).get("/api/seasons/" + seasonId + "/episodes").then().body("[0].durationSeconds", equalTo(1455));
        // Admin : interdit aux utilisateurs.
        given().auth().oauth2(user).get("/api/admin/media/summary").then().statusCode(403);
        given().get("/api/admin/media/summary").then().statusCode(401);
        admin().get("/api/admin/media/summary").then().statusCode(200)
                .body("files", equalTo(6)).body("analyzed", equalTo(5)).body("failed", equalTo(1)).body("pending", equalTo(0))
                .body("android.DIRECT", equalTo(3)).body("android.REMUX", equalTo(2)).body("android.TRANSCODE", equalTo(0))
                .body("browserPlayable", equalTo(2)) // MP4 et MKV H.264 + AAC (lecteur web, phase 10).body("remuxFiles", equalTo(2)).body("remuxBytes", equalTo(6))
                .body("episodesWithDuration", equalTo(5)).body("ffprobeVersion", equalTo("ffprobe version fake-1.0"));
        admin().queryParam("filter", "REMUX").get("/api/admin/media/files").then().statusCode(200)
                .body("total", equalTo(2)).body("items.extension", org.hamcrest.Matchers.containsInAnyOrder("avi", "ogm"))
                .body("items.find { it.extension == 'avi' }.video", equalTo("MPEG-4 ASP Advanced Simple Profile 640×480"))
                .body("items.find { it.extension == 'avi' }.audio", equalTo("MP3 2 ch"));
        admin().queryParam("filter", "failed").get("/api/admin/media/files").then().body("total", equalTo(1));
        admin().queryParam("filter", "browser-ko").get("/api/admin/media/files").then().body("total", equalTo(3));
        admin().queryParam("filter", "nope").get("/api/admin/media/files").then().statusCode(400);
    }

    @Test
    void onlyNewOrModifiedFilesAreProbedAgain() throws Exception {
        processAll();
        int calls = FakeTools.log("ffprobe").size();
        assertEquals(0, processAll()); // rien de nouveau : aucun appel
        assertEquals(calls, FakeTools.log("ffprobe").size());
        // Fichier modifié (taille) puis rescan : de nouveau à analyser, et lui seul.
        Files.write(ROOT.resolve("Days/Days - S01E03.mkv"), new byte[100]);
        scan();
        assertEquals(1, processAll());
        assertEquals("100", probe("Days - S01E03.mkv", "probed_size"));
        // Admin : « relancer l'analyse » des échecs.
        admin().post("/api/admin/media/reprobe-failed").then().statusCode(200).body("queued", equalTo(1));
        assertEquals(1, processAll());
    }

    @Test
    void interruptedRunResumesWhereItStopped() throws Exception {
        // Arrêt après deux fichiers (redémarrage du conteneur) : seuls les autres restent à faire.
        service.probe(service.nextPending().orElseThrow());
        service.probe(service.nextPending().orElseThrow());
        assertEquals(2, count(ds, "SELECT count(*) FROM media_probe"));
        assertEquals(4, processAll());
        // Règles modifiées (version) : reclassement sans nouvelle analyse.
        sql("UPDATE media_probe SET rules_version = 0, android_class = 'TRANSCODE' WHERE status = 'OK'");
        int calls = FakeTools.log("ffprobe").size();
        assertEquals(5, service.reclassifyOutdated(100));
        assertEquals("REMUX", probe("Air Gear - 1x05.avi", "android_class"));
        assertEquals(calls, FakeTools.log("ffprobe").size());
    }

    @Test
    void timeoutAndUnreadableOutputAreFailures() throws Exception {
        touch(ROOT, "Slow/Slow slowprobe - 01.mkv");
        touch(ROOT, "Garbage/Garbage garbage - 01.mkv");
        scan();
        long start = System.nanoTime();
        processAll();
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 20);
        assertEquals("délai dépassé (2 s)", probe("Slow slowprobe - 01.mkv", "error"));
        assertEquals("sortie ffprobe illisible", probe("Garbage garbage - 01.mkv", "error"));
    }

    @Test
    void commandsAreFixedArgumentsWithoutShell() throws Exception {
        processAll();
        String line = FakeTools.log("ffprobe").stream().filter(l -> l.contains("Air Gear")).findFirst().orElseThrow();
        assertTrue(line.startsWith("-v error -hide_banner -show_format -show_streams -of json -i file:/"), line);
        assertTrue(line.endsWith("/Air Gear/Air Gear - 1x05.avi"), line);
    }

    @Test
    void progressUsesTheAnalyzedDurationWhenThePlayerIsWrong() throws Exception {
        processAll();
        long episodeId = count(ds, "SELECT e.id FROM episode e JOIN media_file f ON f.id = e.media_file_id WHERE f.file_name = 'Air Gear - 1x05.avi'");
        String user = userToken();
        // Lecteur qui annonce 3 s pour un épisode de 24 min (AVI mal lu) : la durée analysée fait foi.
        given().auth().oauth2(user).contentType("application/json").body("{\"positionSeconds\":600,\"durationSeconds\":3}")
                .put("/api/episodes/" + episodeId + "/progress").then().statusCode(200)
                .body("positionSeconds", equalTo(600)).body("durationSeconds", equalTo(1455)).body("completed", equalTo(false));
        // Écart faible (arrondi du lecteur) : la valeur du lecteur est gardée.
        given().auth().oauth2(user).contentType("application/json").body("{\"positionSeconds\":1400,\"durationSeconds\":1452}")
                .put("/api/episodes/" + episodeId + "/progress").then().statusCode(200)
                .body("durationSeconds", equalTo(1452)).body("completed", equalTo(true));
    }

    // --- Test à blanc du remux ---------------------------------------------------------------------------------------

    private void waitRemuxTest() throws Exception {
        Instant limit = Instant.now().plusSeconds(30);
        while (remuxTest.running()) {
            if (Instant.now().isAfter(limit)) {
                throw new AssertionError("test à blanc toujours en cours");
            }
            Thread.sleep(50);
        }
    }

    @Test
    void remuxDryRunTriesEachCommandWithoutWritingAnything() throws Exception {
        touch(ROOT, "Fail/Fail remuxfail - 01.avi");
        touch(ROOT, "Unpack/Unpack unpackfail - 01.avi");
        touch(ROOT, "Warn/Warn warn - 01.ogm");
        scan();
        processAll();
        admin().post("/api/admin/media/remux-test/start").then().statusCode(200);
        waitRemuxTest();
        // 5 fichiers à remuxer (AVI, OGM ; MPEG-4) × 2 commandes.
        assertEquals(10, count(ds, "SELECT count(*) FROM remux_test_result"));
        assertEquals(0, count(ds, "SELECT count(*) FROM remux_test_result r JOIN media_file f ON f.id = r.media_file_id"
                + " WHERE f.file_name = 'Air Gear - 1x05.avi' AND NOT r.ok"));
        // Échec réel : code de sortie non nul, message sans chemin.
        String msg = probe("Fail remuxfail - 01.avi", "media_file_id");
        assertEquals(2, count(ds, "SELECT count(*) FROM remux_test_result WHERE media_file_id = " + msg + " AND NOT ok AND exit_code = 234"));
        String text = count(ds, "SELECT count(*) FROM remux_test_result WHERE media_file_id = " + msg
                + " AND message LIKE '%Timestamps are unset%' AND message NOT LIKE '%test-library%'") == 2 ? "ok" : "ko";
        assertEquals("ok", text);
        // Seule la variante « unpack » échoue sur ce fichier.
        String unpack = probe("Unpack unpackfail - 01.avi", "media_file_id");
        assertEquals(1, count(ds, "SELECT count(*) FROM remux_test_result WHERE media_file_id = " + unpack + " AND variant = 'GENPTS' AND ok"));
        assertEquals(1, count(ds, "SELECT count(*) FROM remux_test_result WHERE media_file_id = " + unpack + " AND variant = 'GENPTS_UNPACK' AND NOT ok"));
        // Avertissement sans code d'erreur : succès, avertissement gardé.
        String warn = probe("Warn warn - 01.ogm", "media_file_id");
        assertEquals(2, count(ds, "SELECT count(*) FROM remux_test_result WHERE media_file_id = " + warn + " AND ok AND message LIKE '%Headers mismatch%'"));
        // Commande : arguments fixes, sortie nulle, aucun fichier écrit.
        List<String> calls = FakeTools.log("ffmpeg").stream().filter(l -> l.contains("-i file:")).toList();
        assertEquals(10, calls.size());
        for (String c : calls) {
            assertTrue(c.startsWith("-nostdin -hide_banner -v warning -fflags +genpts -i file:/"), c);
            assertTrue(c.endsWith("-f matroska -y /dev/null"), c);
            assertTrue(c.contains(" -map 0 -c copy "), c);
        }
        assertEquals(5, calls.stream().filter(c -> c.contains("-bsf:v mpeg4_unpack_bframes")).count());
        admin().get("/api/admin/media/remux-test").then().statusCode(200)
                .body("running", equalTo(false)).body("total", equalTo(5)).body("done", equalTo(5))
                .body("perVariant.GENPTS[0]", equalTo(4)).body("perVariant.GENPTS[1]", equalTo(1))
                .body("perVariant.GENPTS_UNPACK[1]", equalTo(2));
        // Relancé : déjà fait, rien à refaire.
        admin().post("/api/admin/media/remux-test/start").then().statusCode(200);
        waitRemuxTest();
        assertEquals(10, FakeTools.log("ffmpeg").stream().filter(l -> l.contains("-i file:")).count());
        given().auth().oauth2(userToken()).post("/api/admin/media/remux-test/start").then().statusCode(403);
    }

    @Test
    void remuxDryRunCanBeStoppedAndDelayed() throws Exception {
        deleteTree(ROOT);
        touch(ROOT, "Slow/Slow slowremux - 01.avi");
        scan(true);
        processAll();
        admin().post("/api/admin/media/remux-test/start").then().statusCode(200).body("running", equalTo(true));
        Thread.sleep(500);
        admin().post("/api/admin/media/remux-test/start").then().statusCode(409);
        admin().post("/api/admin/media/remux-test/stop").then().statusCode(200);
        waitRemuxTest();
        assertEquals(0, count(ds, "SELECT count(*) FROM remux_test_result")); // fichier interrompu : pas de résultat
        // Démarrage différé (la nuit) : rien ne se passe avant l'heure.
        long before = FakeTools.log("ffmpeg").stream().filter(l -> l.contains("-i file:")).count();
        String at = Instant.now().plusSeconds(3600).toString();
        admin().queryParam("startAt", at).post("/api/admin/media/remux-test/start").then().statusCode(200)
                .body("startAt", org.hamcrest.Matchers.notNullValue());
        Thread.sleep(300);
        assertEquals(before, FakeTools.log("ffmpeg").stream().filter(l -> l.contains("-i file:")).count());
        admin().post("/api/admin/media/remux-test/stop").then().statusCode(200);
        waitRemuxTest();
        admin().queryParam("startAt", Instant.now().plusSeconds(90_000).toString()).post("/api/admin/media/remux-test/start")
                .then().statusCode(400);
        admin().post("/api/admin/media/remux-test/reset").then().statusCode(400);
        admin().queryParam("confirm", true).post("/api/admin/media/remux-test/reset").then().statusCode(200);
    }
}
