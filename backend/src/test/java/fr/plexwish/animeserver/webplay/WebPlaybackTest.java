package fr.plexwish.animeserver.webplay;

import fr.plexwish.animeserver.stream.StreamSigner;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static fr.plexwish.animeserver.auth.AuthTestSupport.accessToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.patchUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lecture dans le navigateur (phase 10), faux ffprobe : préparation (202) puis décision (original, copie HLS, non
 * lisible) selon ce que le navigateur décode ; ressources signées liées au fichier, à la préparation et au nom ;
 * playlists réécrites, jamais mises en cache ; Range ; épisode suivant et reprise ; reprise après redémarrage.
 */
@QuarkusTest
class WebPlaybackTest {

    static final Path ROOT = Path.of("target/test-library");
    static final Path CACHE = Path.of("target/test-web-cache");

    @Inject
    AgroalDataSource ds;
    @Inject
    WebPrepService prep;
    @Inject
    WebCache cache;
    @Inject
    StreamSigner signer;
    @Inject
    fr.plexwish.animeserver.media.MediaProbeService probe;

    String user;
    long userId;

    @BeforeEach
    void setUp() throws Exception {
        installFakeTools();
        truncateLibrary(ds);
        sql("DELETE FROM web_job");
        deleteTree(ROOT);
        deleteTree(CACHE);
        prep.maxBytesForTests(null);
        prep.checkTools();
        assertTrue(prep.usable());
        String name = unique("webuser");
        userId = createUser(name, "web-user-password", "USER");
        user = accessToken(name, "web-user-password");
    }

    @AfterEach
    void tearDown() {
        prep.maxBytesForTests(null);
    }

    /** Mêmes faux outils que les tests du remux (scripts dans target/). */
    static void installFakeTools() throws Exception {
        Class<?> c = Class.forName("fr.plexwish.animeserver.media.FakeTools");
        var m = c.getDeclaredMethod("install");
        m.setAccessible(true);
        m.invoke(null);
    }

    private void library(String... files) throws Exception {
        for (String f : files) {
            Path p = ROOT.resolve(f);
            Files.createDirectories(p.getParent());
            Files.write(p, new byte[1000]);
        }
        assertEquals("SUCCESS", scan(true).getString("status"));
    }

    private long episode(String fileName) throws Exception {
        return count(ds, "SELECT e.id FROM episode e JOIN media_file f ON f.id = e.media_file_id WHERE f.file_name = '" + fileName + "'");
    }

    private long fileId(String fileName) throws Exception {
        return count(ds, "SELECT id FROM media_file WHERE file_name = '" + fileName + "'");
    }

    private Response playback(String fileName, String caps) throws Exception {
        return given().auth().oauth2(user).queryParam("caps", caps).get("/api/episodes/" + episode(fileName) + "/web-playback");
    }

    private void sql(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private String key(String fileName) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT j.cache_key FROM web_job j JOIN media_file f ON f.id = j.media_file_id WHERE f.file_name = '"
                     + fileName + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void mp4IsPreparedThenReadDirectlyWithNextEpisodeAndResume() throws Exception {
        library("Days/Days - S01E01.mp4", "Days/Days - S01E02.mp4");
        // Première demande : analyse en file (202), distincte d'une erreur.
        playback("Days - S01E01.mp4", "h264,aac").then().statusCode(202).header("Retry-After", org.hamcrest.Matchers.notNullValue())
                .body("state", equalTo("PREPARING")).body("preparing.message", containsString("…"))
                .body("episode.episodeNumber", equalTo(1));
        assertTrue(prep.processNext());
        given().auth().oauth2(user).contentType("application/json").body(Map.of("positionSeconds", 300, "durationSeconds", 1455))
                .put("/api/episodes/" + episode("Days - S01E01.mp4") + "/progress").then().statusCode(200);
        Response r = playback("Days - S01E01.mp4", "h264,aac");
        r.then().statusCode(200).body("state", equalTo("READY")).body("mode", equalTo("DIRECT"))
                .body("url", startsWith("/api/stream/" + fileId("Days - S01E01.mp4") + "?u="))
                .body("mimeType", equalTo("video/mp4")).body("audio.size()", equalTo(1))
                .body("next.id", equalTo((int) episode("Days - S01E02.mp4"))).body("resume.positionSeconds", equalTo(300))
                .body("subtitles.size()", equalTo(0));
        assertFalse(r.asString().contains("test-library") || r.asString().contains("Days - S01E01"), r.asString());
        // L'URL donnée lit bien l'original (Range).
        given().header("Range", "bytes=0-9").get(r.jsonPath().getString("url")).then().statusCode(206);
        // Dernier épisode : pas de suivant.
        playback("Days - S01E02.mp4", "h264,aac").then().statusCode(202);
        assertTrue(prep.processNext());
        playback("Days - S01E02.mp4", "h264,aac").then().statusCode(200).body("next", nullValue());
    }

    @Test
    void soundOrVideoTheBrowserCannotDecodeIsUnsupported() throws Exception {
        library("Days/Days - S01E01.mp4", "Frieren/Frieren - S01E01 hevc10.mkv");
        playback("Days - S01E01.mp4", "h264").then().statusCode(202);
        assertTrue(prep.processNext());
        playback("Days - S01E01.mp4", "h264").then().statusCode(200).body("state", equalTo("UNSUPPORTED"))
                .body("reason", containsString("son")).body("url", nullValue());
        playback("Frieren - S01E01 hevc10.mkv", "h264,hevc,aac").then().statusCode(202);
        assertTrue(prep.processNext());
        playback("Frieren - S01E01 hevc10.mkv", "h264,hevc,aac").then().statusCode(200).body("state", equalTo("UNSUPPORTED"))
                .body("reason", containsString("HEVC 10 bits"));
    }

    /** Copie HLS simulée (10.2.2 la fabrique avec ffmpeg) : réécriture, signatures, Range, en-têtes. */
    private void fakeCopy(String fileName) throws Exception {
        String k = key(fileName);
        sql("UPDATE web_job SET manifest = jsonb_set(manifest, '{hls}', 'true') WHERE cache_key = '" + k + "'");
        Path d = cache.dir(k);
        Files.createDirectories(d);
        Files.writeString(d.resolve("s_0.m3u8"), """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-TARGETDURATION:8
                #EXT-X-PLAYLIST-TYPE:EVENT
                #EXT-X-MAP:URI="s_0.m4s",BYTERANGE="10@0"
                #EXTINF:8.0,
                #EXT-X-BYTERANGE:90@10
                s_0.m4s
                #EXT-X-ENDLIST
                """);
        Files.write(d.resolve("s_0.m4s"), new byte[100]);
        Files.writeString(d.resolve("s_1.m3u8"), "#EXTM3U\n#EXT-X-MAP:URI=\"s_1.m4s\",BYTERANGE=\"10@0\"\n#EXTINF:8.0,\ns_1.m4s\n#EXT-X-ENDLIST\n");
        Files.write(d.resolve("s_1.m4s"), new byte[50]);
    }

    private static List<String> urls(String playlist) {
        Matcher m = Pattern.compile("(/api/stream/[^\"\\s]+)").matcher(playlist);
        List<String> out = new java.util.ArrayList<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void hlsCopyIsServedWithSignedRewrittenPlaylists() throws Exception {
        library("Days/Days - S01E03.mkv");
        playback("Days - S01E03.mkv", "h264,aac").then().statusCode(202);
        assertTrue(prep.processNext());
        // 10.2.1 : copie pas encore faite → non lisible, avec la raison.
        playback("Days - S01E03.mkv", "h264,aac").then().statusCode(200).body("state", equalTo("UNSUPPORTED"));
        fakeCopy("Days - S01E03.mkv");
        Response r = playback("Days - S01E03.mkv", "h264,aac");
        r.then().statusCode(200).body("mode", equalTo("HLS")).body("mimeType", equalTo("application/vnd.apple.mpegurl"))
                .body("audio[0].label", equalTo("Japonais")).body("audio[0].isDefault", equalTo(true));
        String master = r.jsonPath().getString("url");
        assertTrue(master.contains("/web/" + key("Days - S01E03.mkv") + "/master.m3u8?a=1&u="), master);

        Response m = given().get(master);
        m.then().statusCode(200).header("Cache-Control", equalTo("no-store")).contentType(startsWith("application/vnd.apple.mpegurl"));
        String text = m.asString();
        assertTrue(text.contains("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"Japonais\",LANGUAGE=\"ja\",DEFAULT=YES"), text);
        List<String> refs = urls(text);
        assertEquals(2, refs.size(), text);
        Response video = given().get(refs.get(1));
        video.then().statusCode(200).header("Cache-Control", equalTo("no-store"));
        List<String> segs = urls(video.asString());
        assertEquals(2, segs.size(), video.asString()); // EXT-X-MAP + segment
        given().header("Range", "bytes=10-99").get(segs.get(1)).then().statusCode(206)
                .header("Content-Range", equalTo("bytes 10-99/100")).contentType(startsWith("video/mp4"));
        given().get(urls(given().get(refs.get(0)).asString()).get(0)).then().statusCode(200).contentType(startsWith("audio/mp4"));

        // Signature d'une ressource sur une autre : refusée ; signature modifiée : refusée.
        String seg = segs.get(1);
        given().get(seg.replace("/s_0.m4s?", "/s_1.m4s?")).then().statusCode(403).body("error", equalTo("STREAM_URL_INVALID"));
        given().get(seg.replaceAll("sig=[^&]+", "sig=AAAA")).then().statusCode(403);
        given().get(master.replace("a=1", "a=1,2")).then().statusCode(403);
        given().get(seg.replace("/api/stream/" + fileId("Days - S01E03.mkv") + "/", "/api/stream/999999/")).then().statusCode(403);
        // Nom hors de la liste, même signé : 404 ; lien expiré : 403 dédié.
        long fid = fileId("Days - S01E03.mkv");
        String k = key("Days - S01E03.mkv");
        given().get("/api/stream/" + fid + "/web/" + k + "/manifest.json?" + signer.webQuery(fid, k, "manifest.json", userId, signer.expiry()))
                .then().statusCode(404);
        long past = java.time.Instant.now().minusSeconds(60).getEpochSecond();
        given().get("/api/stream/" + fid + "/web/" + k + "/s_0.m4s?" + signer.webQuery(fid, k, "s_0.m4s", userId, past))
                .then().statusCode(403).body("error", equalTo("STREAM_URL_EXPIRED"));
        // Compte désactivé depuis : plus rien.
        patchUser(userId, Map.of("enabled", false)).then().statusCode(200);
        given().get(seg).then().statusCode(403).body("error", equalTo("USER_DISABLED"));
    }

    @Test
    void fileTooBigForTheCacheFailsClearly() throws Exception {
        library("Days/Days - S01E03.mkv");
        prep.maxBytesForTests(10L);
        playback("Days - S01E03.mkv", "h264,aac").then().statusCode(202);
        assertTrue(prep.processNext());
        playback("Days - S01E03.mkv", "h264,aac").then().statusCode(409).body("error", equalTo("WEB_PREP_FAILED"))
                .body("message", containsString("trop gros"));
    }

    @Test
    void interruptedPreparationStartsAgainAfterARestart() throws Exception {
        library("Days/Days - S01E01.mp4");
        playback("Days - S01E01.mp4", "h264,aac").then().statusCode(202);
        String k = key("Days - S01E01.mp4");
        sql("UPDATE web_job SET status = 'RUNNING', phase = 'HLS' WHERE cache_key = '" + k + "'");
        Files.createDirectories(cache.partDir(k));
        Files.writeString(cache.partDir(k).resolve("s_0.m3u8"), "#EXTM3U\n");
        Files.createDirectories(cache.dir("c".repeat(64)));
        prep.recover();
        assertEquals("QUEUED", status(k));
        assertFalse(prep.partExists(k));
        assertFalse(Files.exists(cache.dir("c".repeat(64))), "dossier inconnu de la base effacé");
        assertTrue(prep.processNext());
        assertEquals("READY", status(k));
    }

    private String status(String key) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT status FROM web_job WHERE cache_key = '" + key + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void anonymousCannotAskWhatToPlay() throws Exception {
        library("Days/Days - S01E01.mp4");
        given().get("/api/episodes/" + episode("Days - S01E01.mp4") + "/web-playback").then().statusCode(401);
    }
}
