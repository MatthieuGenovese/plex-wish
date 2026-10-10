package fr.plexwish.animeserver.webplay;

import fr.plexwish.animeserver.media.RealFfmpegTest;
import fr.plexwish.animeserver.stream.PlaybackActivity;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static fr.plexwish.animeserver.auth.AuthTestSupport.accessToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conversions pour le navigateur (10.3) avec le vrai ffmpeg (ignorées sans libx264) : H.264 10 bits (Hi10P) → H.264
 * 8 bits 720p + AAC, sous-titres de la préparation de base ; MP4 au son AC3 → vidéo copiée, son AAC (« W2 ») ; AVI
 * Xvid → H.264 ; conversion de l'admin suspendue pendant une lecture (SIGSTOP) puis reprise ; place cédée à une
 * demande urgente.
 */
@QuarkusTest
@TestProfile(RealFfmpegTest.Profile.class)
class WebConvertRealTest {

    static final Path ROOT = Path.of("target/test-library");

    @Inject
    AgroalDataSource ds;
    @Inject
    WebPrepService prep;
    @Inject
    WebCache cache;
    @Inject
    WebSettings webSettings;
    @Inject
    PlaybackActivity playback;
    @Inject
    fr.plexwish.animeserver.setup.AppSettings appSettings;

    String user;

    private static boolean has(String what, String name) {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", what).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(10, TimeUnit.SECONDS);
            return out.contains(" " + name + " ");
        } catch (Exception e) {
            return false;
        }
    }

    private static void ffmpeg(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-hide_banner", "-v", "error", "-y"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(180, TimeUnit.SECONDS) && p.exitValue() == 0, String.join(" ", cmd) + "\n" + out);
    }

    private static String ffprobe(Path file, String entries) throws Exception {
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", entries, "-of", "csv=p=0", file.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        p.waitFor(30, TimeUnit.SECONDS);
        return out;
    }

    @BeforeEach
    void files() throws Exception {
        assumeTrue(has("-encoders", "libx264") && has("-encoders", "ac3") && has("-muxers", "hls"), "ffmpeg avec libx264, ac3 et hls absent");
        truncateLibrary(ds);
        sql("DELETE FROM web_job");
        sql("DELETE FROM app_setting WHERE key LIKE 'web.%'");
        appSettings.forget();
        deleteTree(ROOT);
        deleteTree(cache.root());
        Files.createDirectories(ROOT.resolve("Conv"));
        prep.checkTools();
        assertTrue(prep.usable());
        String name = unique("webconv");
        createUser(name, "web-conv-password", "USER");
        user = accessToken(name, "web-conv-password");
        playback.forceForTests(false);
        webSettings.nightForTests(false);

        Path ass = ROOT.resolve("subs.ass");
        Files.writeString(ass, """
                [Script Info]
                ScriptType: v4.00+

                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1

                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:00.50,0:00:03.00,Default,,0,0,0,,Bonjour
                """);
        // Hi10P 1080p, AAC japonais + AC3 français, ASS.
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=1920x1080:rate=24:duration=4", "-f", "lavfi", "-i", "sine=f=440:duration=4",
                "-f", "lavfi", "-i", "sine=f=660:duration=4", "-i", ass.toString(), "-map", "0", "-map", "1", "-map", "2", "-map", "3",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le", "-c:a:0", "aac", "-c:a:1", "ac3", "-c:s", "ass",
                "-metadata:s:a:0", "language=jpn", "-metadata:s:a:1", "language=fre", "-metadata:s:s:0", "language=fre",
                ROOT.resolve("Conv/Conv - S01E01.mkv").toString());
        // MP4 H.264 8 bits, son AC3 : seul le son est converti.
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24:duration=4", "-f", "lavfi", "-i", "sine=f=440:duration=4",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "ac3", ROOT.resolve("Conv/Conv - S01E02.mp4").toString());
        // AVI Xvid + MP3 (MP3 copié).
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25:duration=4", "-f", "lavfi", "-i", "sine=f=550:duration=4",
                "-c:v", "mpeg4", "-vtag", "XVID", "-c:a", has("-encoders", "libmp3lame") ? "libmp3lame" : "mp2",
                ROOT.resolve("Conv/Conv - S01E03.avi").toString());
        // Plus long, pour voir la conversion suspendue puis reprise.
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=1920x1080:rate=24:duration=30", "-f", "lavfi", "-i", "sine=f=440:duration=30",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le", "-c:a", "aac",
                ROOT.resolve("Conv/Conv - S01E04.mkv").toString());
        Files.delete(ass);
        assertEquals("SUCCESS", scan(true).getString("status"));
    }

    @AfterEach
    void reset() {
        playback.forceForTests(null);
        webSettings.nightForTests(null);
    }

    private void sql(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private String one(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
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

    private String key(String fileName, String kind) throws Exception {
        return one("SELECT j.cache_key FROM web_job j JOIN media_file f ON f.id = j.media_file_id WHERE j.kind = '" + kind
                + "' AND f.file_name = '" + fileName + "'");
    }

    private String status(String fileName, String kind) throws Exception {
        return one("SELECT j.status FROM web_job j JOIN media_file f ON f.id = j.media_file_id WHERE j.kind = '" + kind
                + "' AND f.file_name = '" + fileName + "'");
    }

    private static List<String> urls(String playlist) {
        Matcher m = Pattern.compile("(/api/stream/[^\"\\s]+)").matcher(playlist);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static void await(BooleanSupplier ok, int seconds, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("délai dépassé : " + what);
            }
            Thread.sleep(100);
        }
    }

    /** Lance une conversion (admin) du fichier, analyse de base comprise. */
    private void enqueueAdmin(String fileName) throws Exception {
        long id = fileId(fileName);
        long size;
        OffsetDateTime modified;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT file_size, last_modified FROM media_file WHERE id = " + id)) {
            rs.next();
            size = rs.getLong(1);
            modified = rs.getObject(2, OffsetDateTime.class);
        }
        WebManifest base = prep.analyse(ROOT.resolve("Conv").resolve(fileName), fileName.substring(fileName.lastIndexOf('.') + 1));
        prep.enqueue(WebPrepService.CONV, id, size, modified, base, WebPrepService.PRIORITY_ADMIN);
    }

    @Test
    void hi10pBecomesH264At720pWithAacAndTheSubtitlesOfTheBasePreparation() throws Exception {
        playback("Conv - S01E01.mkv", "h264,aac").then().statusCode(202);
        assertTrue(prep.processNext());
        playback("Conv - S01E01.mkv", "h264,aac").then().statusCode(202).body("preparing.conversion", equalTo(true))
                .body("preparing.message", containsString("Conversion"));
        assertTrue(prep.processNext(WebPrepService.CONV));
        Response r = playback("Conv - S01E01.mkv", "h264,aac");
        String convKey = key("Conv - S01E01.mkv", "CONV");
        String baseKey = key("Conv - S01E01.mkv", "BASE");
        r.then().statusCode(200).body("mode", equalTo("HLS")).body("audio.label", equalTo(List.of("Japonais", "Français")))
                .body("subtitles[0].format", equalTo("ass"));
        assertTrue(r.jsonPath().getString("url").contains("/web/" + convKey + "/master.m3u8"));
        assertTrue(r.jsonPath().getString("subtitles[0].url").contains("/web/" + baseKey + "/sub_0.ass"));
        given().get(r.jsonPath().getString("subtitles[0].url")).then().statusCode(200).body(containsString("Bonjour"));

        Path dir = cache.dir(convKey);
        assertEquals("h264,High,1280,720,yuv420p", ffprobe(dir.resolve("s_0.m4s"), "stream=codec_name,profile,pix_fmt,width,height"));
        String master = given().get(r.jsonPath().getString("url")).then().statusCode(200).extract().asString();
        assertTrue(master.contains("RESOLUTION=1280x720"), master);
        List<String> refs = urls(master);
        assertEquals(3, refs.size(), master);
        String videoPl = given().get(refs.get(2)).then().statusCode(200).extract().asString();
        assertTrue(videoPl.contains("#EXT-X-ENDLIST"), videoPl);
        given().header("Range", "bytes=0-99").get(urls(videoPl).get(1)).then().statusCode(206);

        assertEquals("aac", ffprobe(dir.resolve("s_1.m4s"), "stream=codec_name"));
        assertEquals("aac,2", ffprobe(dir.resolve("s_2.m4s"), "stream=codec_name,channels"), "AC3 converti en AAC stéréo");
        assertEquals(4, WebPrepSteps.duration(Files.readString(dir.resolve("s_0.m3u8"))), 0.6);
        assertNotNull(one("SELECT elapsed_ms FROM web_job WHERE cache_key = '" + convKey + "'"));
    }

    @Test
    void soundOnlyConversionCopiesTheVideo() throws Exception {
        playback("Conv - S01E02.mp4", "h264,aac").then().statusCode(202);
        assertTrue(prep.processNext());
        playback("Conv - S01E02.mp4", "h264,aac").then().statusCode(202).body("preparing.conversion", equalTo(true));
        assertTrue(prep.processNext(WebPrepService.CONV));
        playback("Conv - S01E02.mp4", "h264,aac").then().statusCode(200).body("mode", equalTo("HLS"));
        Path dir = cache.dir(key("Conv - S01E02.mp4", "CONV"));
        assertEquals("h264,320,240", ffprobe(dir.resolve("s_0.m4s"), "stream=codec_name,width,height"));
        assertEquals("aac", ffprobe(dir.resolve("s_1.m4s"), "stream=codec_name"));
        // Navigateur qui lit l'AC3 (Edge sous Windows) : l'original, sans conversion.
        playback("Conv - S01E02.mp4", "h264,aac,ac3").then().statusCode(200).body("mode", equalTo("DIRECT"));
    }

    @Test
    void xvidAviIsConverted() throws Exception {
        playback("Conv - S01E03.avi", "h264,aac,mp3").then().statusCode(202);
        assertTrue(prep.processNext());
        playback("Conv - S01E03.avi", "h264,aac,mp3").then().statusCode(202);
        assertTrue(prep.processNext(WebPrepService.CONV));
        playback("Conv - S01E03.avi", "h264,aac,mp3").then().statusCode(200).body("mode", equalTo("HLS"));
        assertEquals("h264", ffprobe(cache.dir(key("Conv - S01E03.avi", "CONV")).resolve("s_0.m4s"), "stream=codec_name"));
    }

    @Test
    void adminConversionIsSuspendedDuringPlaybackThenResumes() throws Exception {
        webSettings.save(1080, true, false); // plus lent : le temps d'observer la pause
        enqueueAdmin("Conv - S01E04.mkv");
        playback.forceForTests(true);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread t = Thread.ofPlatform().start(() -> {
            try {
                prep.processNext(WebPrepService.CONV);
            } catch (Throwable e) {
                error.set(e);
            }
        });
        await(() -> prep.convPaused() && prep.convPid() != null, 30, "conversion suspendue");
        long pid = prep.convPid();
        await(() -> state(pid) == 'T', 10, "processus arrêté (SIGSTOP)");
        playback.forceForTests(false);
        await(() -> !prep.convPaused() && state(pid) != 'T', 10, "conversion reprise (SIGCONT)");
        t.join(180_000);
        assertNull(error.get());
        assertEquals("READY", status("Conv - S01E04.mkv", "CONV"));
        assertEquals("1920,1080", ffprobe(cache.dir(key("Conv - S01E04.mkv", "CONV")).resolve("s_0.m4s"), "stream=width,height"));
    }

    @Test
    void anAdminConversionGivesWayToSomeoneWaiting() throws Exception {
        webSettings.save(1080, true, false);
        enqueueAdmin("Conv - S01E04.mkv");
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread t = Thread.ofPlatform().start(() -> {
            try {
                prep.processNext(WebPrepService.CONV);
            } catch (Throwable e) {
                error.set(e);
            }
        });
        await(() -> prep.convPid() != null, 30, "conversion de l'admin lancée");
        // Quelqu'un ouvre un autre épisode à convertir.
        playback("Conv - S01E03.avi", "h264,aac,mp3").then().statusCode(202);
        assertTrue(prep.processNext());
        playback("Conv - S01E03.avi", "h264,aac,mp3").then().statusCode(202);
        t.join(30_000);
        assertFalse(t.isAlive(), "conversion de l'admin arrêtée");
        assertNull(error.get());
        assertEquals("QUEUED", status("Conv - S01E04.mkv", "CONV"), "remise en file, pas en échec");
        assertEquals("0", one("SELECT attempts FROM web_job WHERE kind = 'CONV' AND cache_key = '" + key("Conv - S01E04.mkv", "CONV") + "'"));
        assertFalse(prep.partExists(key("Conv - S01E04.mkv", "CONV")));
        assertTrue(prep.processNext(WebPrepService.CONV));
        assertEquals("READY", status("Conv - S01E03.avi", "CONV"), "la demande urgente passe d'abord");
        assertEquals("QUEUED", status("Conv - S01E04.mkv", "CONV"));
    }

    /** État du processus (/proc) : 'T' = arrêté par SIGSTOP. */
    private static char state(long pid) {
        try {
            String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
            return stat.charAt(stat.lastIndexOf(')') + 2);
        } catch (Exception e) {
            return '?';
        }
    }
}
