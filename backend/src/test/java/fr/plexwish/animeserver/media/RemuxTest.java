package fr.plexwish.animeserver.media;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static fr.plexwish.animeserver.auth.AuthTestSupport.accessToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remux à la demande (faux ffmpeg / ffprobe) : « préparation en cours » (202) puis URL signée de la copie, variante du
 * test à blanc d'abord, repli, vérification de la copie, échecs et « relancer », file et priorité, purge des copies
 * les moins lues, cache plein, cache sans chemin ni titre, signature, travail de fond en pause.
 */
@QuarkusTest
class RemuxTest {

    static final Path ROOT = Path.of("target/test-library");
    static final Path CACHE = Path.of("target/test-remux-cache");

    @Inject
    AgroalDataSource ds;
    @Inject
    MediaProbeService probe;
    @Inject
    RemuxService remux;
    @Inject
    RemuxTestService remuxTest;

    String user;

    @BeforeEach
    void setUp() throws Exception {
        FakeTools.install();
        remuxTest.stop();
        truncateLibrary(ds);
        deleteTree(ROOT);
        deleteTree(CACHE);
        remux.maxBytesForTests(null);
        remux.checkTools();
        assertTrue(remux.usable());
        String name = unique("remuxuser");
        createUser(name, "remux-user-password", "USER");
        user = accessToken(name, "remux-user-password");
    }

    @AfterEach
    void tearDown() {
        remux.maxBytesForTests(null);
        remuxTest.stop();
    }

    private void file(String rel) throws Exception {
        Path p = ROOT.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[1000]);
    }

    private void library(String... files) throws Exception {
        for (String f : files) {
            file(f);
        }
        assertEquals("SUCCESS", scan(true).getString("status"));
        for (var w = probe.nextPending(); w.isPresent(); w = probe.nextPending()) {
            probe.probe(w.get());
        }
    }

    private long episode(String fileName) throws Exception {
        return count(ds, "SELECT e.id FROM episode e JOIN media_file f ON f.id = e.media_file_id WHERE f.file_name = '" + fileName + "'");
    }

    private long fileId(String fileName) throws Exception {
        return count(ds, "SELECT id FROM media_file WHERE file_name = '" + fileName + "'");
    }

    private Response streamUrl(String fileName) throws Exception {
        return given().auth().oauth2(user).get("/api/episodes/" + episode(fileName) + "/stream-url");
    }

    private void sql(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private String job(String fileName, String column) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT j." + column + " FROM remux_job j JOIN media_file f ON f.id = j.media_file_id"
                     + " WHERE f.file_name = '" + fileName + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private List<String> cacheFiles() throws Exception {
        if (!Files.isDirectory(CACHE)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(CACHE)) {
            return s.filter(Files::isRegularFile).map(p -> CACHE.relativize(p).toString().replace('\\', '/')).toList();
        }
    }

    private List<String> remuxCalls() throws Exception {
        return FakeTools.log("ffmpeg").stream().filter(l -> l.contains("-progress pipe:1")).toList();
    }

    @Test
    void aviIsPreparedThenServedFromTheCacheNeverAsTheOriginal() throws Exception {
        library("Air Gear/Air Gear - 1x05.avi", "Days/Days - S01E03.mkv");
        // Lisible tel quel : URL de l'original, comme avant.
        streamUrl("Days - S01E03.mkv").then().statusCode(200).body("url", org.hamcrest.Matchers.startsWith("/api/stream/"));
        // AVI : préparation en cours (202), distincte d'une erreur.
        Response r = streamUrl("Air Gear - 1x05.avi");
        assertEquals(202, r.statusCode(), r.asString());
        r.then().body("state", equalTo("PREPARING")).body("position", equalTo(0)).body("retryAfterSeconds", greaterThan(1))
                .body("estimatedSeconds", greaterThan(0)).body("url", nullValue());
        assertTrue(r.header("Retry-After") != null);
        assertFalse(r.asString().contains("remux-cache") || r.asString().contains("test-library"), r.asString());
        assertEquals("QUEUED", job("Air Gear - 1x05.avi", "status"));
        // Deux demandes du même épisode : un seul travail.
        streamUrl("Air Gear - 1x05.avi").then().statusCode(202);
        assertEquals(1, count(ds, "SELECT count(*) FROM remux_job"));

        assertTrue(remux.processNext());
        assertFalse(remux.processNext());
        assertEquals(1, remuxCalls().size());
        String call = remuxCalls().get(0);
        assertTrue(call.startsWith("-nostdin -hide_banner -v warning -progress pipe:1 -nostats -fflags +genpts -i file:/"), call);
        assertTrue(call.contains(" -map 0 -c copy -f matroska -y file:/"), call);
        assertEquals("READY", job("Air Gear - 1x05.avi", "status"));
        assertEquals("GENPTS", job("Air Gear - 1x05.avi", "variant"));
        assertEquals("1454.967", job("Air Gear - 1x05.avi", "duration_seconds"));
        // Cache : empreinte seulement, ni titre ni nom de fichier ; pas de fichier temporaire restant.
        List<String> files = cacheFiles();
        assertEquals(1, files.size(), files.toString());
        assertTrue(files.get(0).matches("[0-9a-f]{2}/[0-9a-f]{64}\\.mkv"), files.get(0));

        Response ready = streamUrl("Air Gear - 1x05.avi");
        ready.then().statusCode(200).body("mimeType", equalTo("video/x-matroska")).body("fileSize", equalTo(1000));
        String url = ready.path("url");
        long id = fileId("Air Gear - 1x05.avi");
        assertTrue(url.startsWith("/api/stream/" + id + "/remux?u="), url);
        assertFalse(ready.asString().contains("remux-cache") || ready.asString().contains(".mkv"), ready.asString());
        // La copie se lit par Range, sans jeton ; la date de lecture est enregistrée.
        Response bytes = RestAssured.given().header("Range", "bytes=0-7").get(url);
        assertEquals(206, bytes.statusCode());
        assertEquals("FIXTURE=", bytes.asString());
        assertTrue(job("Air Gear - 1x05.avi", "last_read_at") != null);
        // Signature liée à la copie : falsifiée ou celle de l'original → 403.
        RestAssured.given().get(url.replaceAll("sig=[^&]+", "sig=abc")).then().statusCode(403);
        String original = url.replace("/remux?", "?");
        RestAssured.given().get(original).then().statusCode(403);
        RestAssured.given().urlEncodingEnabled(false).get("/api/stream/" + id + "/..%2F..%2Fremux").then()
                .statusCode(org.hamcrest.Matchers.anyOf(equalTo(404), equalTo(400), equalTo(403)));
    }

    @Test
    void dryRunWinnerFirstOtherwiseSimplestThenFallback() throws Exception {
        library("A/A genptsfail - 01.avi", "B/B - 01.avi", "C/C remuxfail - 01.avi");
        // A : pas de test à blanc → genpts, échec, repli genpts + unpack.
        // B : le test à blanc dit que seule la variante « unpack » marche → elle passe en premier.
        sql("INSERT INTO remux_test_result (media_file_id, variant, ok, elapsed_ms, bytes) VALUES ("
                + fileId("B - 01.avi") + ", 'GENPTS_UNPACK', true, 1, 1000), (" + fileId("B - 01.avi") + ", 'GENPTS', false, 1, 1000)");
        for (String f : List.of("A genptsfail - 01.avi", "B - 01.avi", "C remuxfail - 01.avi")) {
            streamUrl(f).then().statusCode(202);
        }
        while (remux.processNext()) {
            // un à la fois
        }
        assertEquals("READY", job("A genptsfail - 01.avi", "status"));
        assertEquals("GENPTS_UNPACK", job("A genptsfail - 01.avi", "variant"));
        assertEquals("GENPTS_UNPACK", job("B - 01.avi", "variant"));
        List<String> calls = remuxCalls();
        String firstB = calls.stream().filter(c -> c.contains("B - 01.avi")).findFirst().orElseThrow();
        assertTrue(firstB.contains("mpeg4_unpack_bframes"), firstB);
        assertEquals(1, calls.stream().filter(c -> c.contains("B - 01.avi")).count());
        // C : les deux commandes échouent → « remux impossible » avec la raison, pas de boucle.
        assertEquals("FAILED", job("C remuxfail - 01.avi", "status"));
        String error = job("C remuxfail - 01.avi", "error");
        assertTrue(error.contains("genpts : code 234") && error.contains("Timestamps are unset"), error);
        assertFalse(error.contains("test-library"), error);
        streamUrl("C remuxfail - 01.avi").then().statusCode(409).body("error", equalTo("REMUX_FAILED"));
        assertFalse(remux.processNext()); // pas de nouvel essai avant le délai
        // Admin : rapport des échecs et « relancer ».
        given().auth().oauth2(adminToken()).queryParam("status", "FAILED").get("/api/admin/media/remux/jobs").then().statusCode(200)
                .body("size()", equalTo(1)).body("[0].path", equalTo("C/C remuxfail - 01.avi"));
        given().auth().oauth2(adminToken()).post("/api/admin/media/remux/jobs/" + fileId("C remuxfail - 01.avi") + "/retry")
                .then().statusCode(200);
        assertEquals("QUEUED", job("C remuxfail - 01.avi", "status"));
        given().auth().oauth2(user).post("/api/admin/media/remux/jobs/1/retry").then().statusCode(403);
    }

    @Test
    void copyIsVerifiedBeforeBeingServed() throws Exception {
        library("A/A badcopy - 01.avi", "B/B noaudio - 01.avi");
        streamUrl("A badcopy - 01.avi").then().statusCode(202);
        streamUrl("B noaudio - 01.avi").then().statusCode(202);
        while (remux.processNext()) {
            // un à la fois
        }
        assertEquals("FAILED", job("A badcopy - 01.avi", "status"));
        assertTrue(job("A badcopy - 01.avi", "error").contains("durée 100 s au lieu de 1455 s"), job("A badcopy - 01.avi", "error"));
        assertTrue(job("B noaudio - 01.avi", "error").contains("pas de piste audio"));
        assertEquals(List.of(), cacheFiles()); // ni copie ni fichier temporaire
    }

    @Test
    void timeoutLeavesNothingBehind() throws Exception {
        library("A/A slowremux - 01.avi");
        streamUrl("A slowremux - 01.avi").then().statusCode(202);
        remux.processNext();
        assertEquals("FAILED", job("A slowremux - 01.avi", "status"));
        assertTrue(job("A slowremux - 01.avi", "error").contains("délai dépassé"));
        assertEquals(List.of(), cacheFiles());
    }

    @Test
    void secondEpisodeWaitsInTheQueueWithAnEstimate() throws Exception {
        library("A/A - 01.avi", "B/B - 01.ogm");
        streamUrl("A - 01.avi").then().statusCode(202).body("position", equalTo(0));
        Response second = streamUrl("B - 01.ogm");
        second.then().statusCode(202).body("position", equalTo(1));
        long first = ((Number) given().auth().oauth2(user).get("/api/episodes/" + episode("A - 01.avi") + "/stream-url")
                .path("estimatedSeconds")).longValue();
        assertTrue(((Number) second.path("estimatedSeconds")).longValue() > first);
        // Préparation de l'admin : passe après les demandes des utilisateurs.
        library("C/C - 01.avi");
        long animeC = count(ds, "SELECT id FROM anime WHERE title = 'C'");
        given().auth().oauth2(adminToken()).post("/api/admin/media/remux/anime/" + animeC + "/prepare").then().statusCode(200)
                .body("queued", equalTo(1));
        streamUrl("A - 01.avi"); // (déjà en file)
        assertTrue(remux.processNext());
        assertTrue(remux.processNext());
        assertEquals("QUEUED", job("C - 01.avi", "status")); // traité en dernier
        assertEquals("READY", job("B - 01.ogm", "status"));
        // OGM : commande la plus simple (genpts), l'avertissement ne compte pas comme un échec.
        assertEquals("GENPTS", job("B - 01.ogm", "variant"));
    }

    @Test
    void leastRecentlyReadCopiesArePurgedButNeverOneInUse() throws Exception {
        library("A/A - 01.avi", "B/B - 01.avi", "C/C - 01.avi", "D/D - 01.avi");
        for (String f : List.of("A - 01.avi", "B - 01.avi", "C - 01.avi")) {
            streamUrl(f).then().statusCode(202);
            remux.processNext();
        }
        assertEquals(3, cacheFiles().size());
        // 3 copies de 1000 octets ; la 4e demande ~2050 octets de place dans un cache de 5000.
        remux.maxBytesForTests(5000L);
        sql("UPDATE remux_job SET last_read_at = now() - interval '1 day' WHERE media_file_id = " + fileId("A - 01.avi"));
        sql("UPDATE remux_job SET last_read_at = now() - interval '10 minutes' WHERE media_file_id = " + fileId("B - 01.avi"));
        sql("UPDATE remux_job SET last_read_at = now() - interval '20 hours' WHERE media_file_id = " + fileId("C - 01.avi"));
        streamUrl("D - 01.avi").then().statusCode(202);
        remux.processNext();
        assertEquals("READY", job("D - 01.avi", "status"));
        assertEquals(null, job("A - 01.avi", "status")); // la moins récemment lue, effacée
        assertEquals("READY", job("B - 01.avi", "status")); // en cours de lecture : gardée
        assertEquals(3, cacheFiles().size());
        // Cache plein, tout est en cours de lecture : réponse claire (503), la demande reste en file.
        remux.maxBytesForTests(3500L);
        sql("UPDATE remux_job SET last_read_at = now()");
        streamUrl("A - 01.avi").then().statusCode(202);
        remux.processNext();
        streamUrl("A - 01.avi").then().statusCode(503).body("error", equalTo("REMUX_CACHE_FULL"))
                .body("message", org.hamcrest.Matchers.containsString("Réessayez"));
        assertEquals("QUEUED", job("A - 01.avi", "status"));
        assertEquals("CACHE_FULL", job("A - 01.avi", "blocked"));
        assertEquals(3, cacheFiles().size());
        // Plus gros que tout le cache : échec explicite.
        remux.maxBytesForTests(1000L);
        sql("UPDATE remux_job SET next_attempt_at = NULL, blocked = NULL");
        remux.processNext();
        assertTrue(job("A - 01.avi", "error").contains("trop gros pour le cache"));
        // Admin : « vider le cache » garde les copies en cours de lecture.
        sql("UPDATE remux_job SET last_read_at = now() - interval '1 day' WHERE media_file_id = " + fileId("C - 01.avi"));
        given().auth().oauth2(adminToken()).queryParam("confirm", true).post("/api/admin/media/remux/clear").then().statusCode(200)
                .body("removed", equalTo(1)).body("keptInUse", equalTo(2));
        given().auth().oauth2(adminToken()).get("/api/admin/media/remux").then().statusCode(200)
                .body("ready", equalTo(2)).body("usedBytes", equalTo(2000)).body("cachePath", org.hamcrest.Matchers.endsWith("test-remux-cache"));
    }

    @Test
    void modifiedSourceInvalidatesTheCopyAndStartupCleansUp() throws Exception {
        library("A/A - 01.avi");
        streamUrl("A - 01.avi").then().statusCode(202);
        remux.processNext();
        String oldKey = job("A - 01.avi", "cache_key");
        Files.write(ROOT.resolve("A/A - 01.avi"), new byte[1200]);
        scan(true);
        streamUrl("A - 01.avi").then().statusCode(202); // nouvelle source : copie refaite
        assertFalse(cacheFiles().stream().anyMatch(f -> f.contains(oldKey)));
        remux.processNext();
        String newKey = job("A - 01.avi", "cache_key");
        // Restes d'un arrêt : fichier temporaire et copie inconnue effacés au démarrage ; copie connue gardée.
        Path junk = CACHE.resolve("ab/" + "ab".repeat(32) + ".mkv");
        Files.createDirectories(junk.getParent());
        Files.write(junk, new byte[10]);
        Files.write(CACHE.resolve(newKey.substring(0, 2) + "/" + newKey + ".mkv.part"), new byte[10]);
        // Un remux interrompu par l'arrêt repart en file.
        library("B/B - 01.avi");
        streamUrl("B - 01.avi").then().statusCode(202);
        sql("UPDATE remux_job SET status = 'RUNNING' WHERE media_file_id = " + fileId("B - 01.avi"));
        remux.recover();
        assertEquals(List.of(newKey.substring(0, 2) + "/" + newKey + ".mkv"), cacheFiles());
        assertEquals("READY", job("A - 01.avi", "status"));
        assertEquals("QUEUED", job("B - 01.avi", "status"));
    }

    @Test
    void userRequestPausesTheDryRun() throws Exception {
        library("A/A slowish - 01.avi", "B/B slowish - 01.avi", "U/U - 01.avi");
        assertTrue(remuxTest.start(null));
        Thread.sleep(300);
        streamUrl("U - 01.avi").then().statusCode(202);
        // Le test à blanc cède : il attend que la file soit vide.
        Instant limit = Instant.now().plusSeconds(10);
        while (!remuxTest.status().current().startsWith("en pause") && Instant.now().isBefore(limit)) {
            Thread.sleep(50);
        }
        assertTrue(remuxTest.status().current().startsWith("en pause"), remuxTest.status().current());
        assertTrue(remux.busy());
        remux.processNext();
        assertFalse(remux.busy());
        limit = Instant.now().plusSeconds(20);
        while (remuxTest.running() && Instant.now().isBefore(limit)) {
            Thread.sleep(50);
        }
        // Tous les fichiers « remux nécessaire » finissent testés (y compris celui interrompu).
        assertEquals(6, count(ds, "SELECT count(*) FROM remux_test_result"));
    }

    @Test
    void episodeListTellsWhetherABrowserCanPlayIt() throws Exception {
        library("A/A - 01.avi", "M/M - 01.mp4");
        long s1 = count(ds, "SELECT s.id FROM season s JOIN anime a ON a.id = s.anime_id WHERE a.title = 'A'");
        long s2 = count(ds, "SELECT s.id FROM season s JOIN anime a ON a.id = s.anime_id WHERE a.title = 'M'");
        given().auth().oauth2(user).get("/api/seasons/" + s1 + "/episodes").then().body("[0].browserPlayable", equalTo(false));
        given().auth().oauth2(user).get("/api/seasons/" + s2 + "/episodes").then().body("[0].browserPlayable", equalTo(true));
    }
}
