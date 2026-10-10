package fr.plexwish.animeserver.webplay;

import fr.plexwish.animeserver.media.RealFfmpegTest;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Préparation web avec le vrai ffmpeg (ignorée sans ffmpeg / libx264) : MKV H.264 à deux pistes AAC, sous-titres ASS
 * avec police jointe → copie HLS fMP4 (pistes séparées), ASS + WebVTT, police ; HEVC marqué hvc1 ; MP4 lu tel quel
 * avec ses sous-titres mov_text en WebVTT.
 */
@QuarkusTest
@TestProfile(RealFfmpegTest.Profile.class)
class WebPlaybackRealTest {

    static final Path ROOT = Path.of("target/test-library");
    static final byte[] FONT = "FAUSSE-POLICE-TTF-0123456789".getBytes();

    @Inject
    AgroalDataSource ds;
    @Inject
    WebPrepService prep;
    @Inject
    WebCache cache;

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
        assertTrue(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0, String.join(" ", cmd) + "\n" + out);
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
        assumeTrue(has("-encoders", "libx264") && has("-muxers", "hls"), "ffmpeg avec libx264 et hls absent");
        truncateLibrary(ds);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM web_job");
        }
        deleteTree(ROOT);
        deleteTree(cache.root());
        Files.createDirectories(ROOT.resolve("Gen"));
        prep.checkTools();
        assertTrue(prep.usable());
        String name = unique("webreal");
        createUser(name, "web-real-password", "USER");
        user = accessToken(name, "web-real-password");

        String video = "testsrc2=size=320x240:rate=24:duration=14";
        Path ass = ROOT.resolve("subs.ass");
        Files.writeString(ass, """
                [Script Info]
                ScriptType: v4.00+

                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                Style: Default,Fausse Police,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1

                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:00.50,0:00:03.00,Default,,0,0,0,,Bonjour {\\i1}vous{\\i0}
                """);
        Path font = ROOT.resolve("police.ttf");
        Files.write(font, FONT);
        ffmpeg("-f", "lavfi", "-i", video, "-f", "lavfi", "-i", "sine=f=440:duration=14", "-f", "lavfi", "-i", "sine=f=660:duration=14",
                "-i", ass.toString(), "-map", "0", "-map", "1", "-map", "2", "-map", "3",
                "-c:v", "libx264", "-preset", "ultrafast", "-g", "48", "-c:a", "aac", "-c:s", "ass",
                "-metadata:s:a:0", "language=jpn", "-metadata:s:a:1", "language=fre", "-metadata:s:a:1", "title=VF",
                "-metadata:s:s:0", "language=fre", "-attach", font.toString(),
                "-metadata:s:t:0", "mimetype=application/x-truetype-font", "-metadata:s:t:0", "filename=../evil.ttf",
                ROOT.resolve("Gen/Gen - S01E01.mkv").toString());
        ffmpeg("-f", "lavfi", "-i", video, "-f", "lavfi", "-i", "sine=f=440:duration=14", "-i", ass.toString(),
                "-map", "0", "-map", "1", "-map", "2", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-c:s", "mov_text",
                ROOT.resolve("Gen/Gen - S01E02.mp4").toString());
        if (has("-encoders", "libx265")) {
            ffmpeg("-f", "lavfi", "-i", video, "-f", "lavfi", "-i", "sine=f=440:duration=14", "-c:v", "libx265", "-preset", "ultrafast",
                    "-x265-params", "log-level=error", "-c:a", "aac", ROOT.resolve("Gen/Gen - S01E03.mkv").toString());
        }
        Files.delete(ass);
        Files.delete(font);
        assertEquals("SUCCESS", scan(true).getString("status"));
    }

    private long episode(String fileName) throws Exception {
        return count(ds, "SELECT e.id FROM episode e JOIN media_file f ON f.id = e.media_file_id WHERE f.file_name = '" + fileName + "'");
    }

    private Response playback(String fileName, String caps) throws Exception {
        return given().auth().oauth2(user).queryParam("caps", caps).get("/api/episodes/" + episode(fileName) + "/web-playback");
    }

    private String key(String fileName) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT j.cache_key FROM web_job j JOIN media_file f ON f.id = j.media_file_id WHERE j.kind = 'BASE'"
                     + " AND f.file_name = '" + fileName + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static List<String> urls(String playlist) {
        Matcher m = Pattern.compile("(/api/stream/[^\"\\s]+)").matcher(playlist);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void mkvWithTwoTracksAssAndFontBecomesAPlayableHlsCopy() throws Exception {
        playback("Gen - S01E01.mkv", "h264,aac").then().statusCode(202);
        assertTrue(prep.processNext());
        Response r = playback("Gen - S01E01.mkv", "h264,aac");
        r.then().statusCode(200).body("mode", equalTo("HLS")).body("growing", equalTo(false))
                .body("audio.label", equalTo(List.of("Japonais", "Français (VF)")))
                .body("subtitles[0].format", equalTo("ass")).body("subtitles[0].label", equalTo("Français"))
                .body("fonts.size()", equalTo(1));
        assertFalse(r.asString().contains("evil") || r.asString().contains("test-library"), r.asString());

        String master = given().get(r.jsonPath().getString("url")).then().statusCode(200).extract().asString();
        List<String> refs = urls(master);
        assertEquals(3, refs.size(), master); // deux pistes audio + vidéo
        String videoPl = given().get(refs.get(2)).then().statusCode(200).extract().asString();
        assertTrue(videoPl.contains("#EXT-X-ENDLIST"), videoPl);
        List<String> segs = urls(videoPl);
        assertTrue(segs.size() >= 3, videoPl); // init + au moins deux segments de ~6 s
        given().header("Range", "bytes=0-99").get(segs.get(1)).then().statusCode(206);
        String audioPl = given().get(refs.get(1)).then().statusCode(200).extract().asString();
        assertTrue(audioPl.contains("#EXT-X-ENDLIST"), audioPl);

        String assText = given().get(r.jsonPath().getString("subtitles[0].url")).then().statusCode(200).extract().asString();
        assertTrue(assText.contains("Dialogue:") && assText.contains("Bonjour"), assText);
        given().get(r.jsonPath().getString("subtitles[0].vttUrl")).then().statusCode(200)
                .contentType(org.hamcrest.Matchers.startsWith("text/vtt")).body(org.hamcrest.Matchers.startsWith("WEBVTT"));
        byte[] f = given().get(r.jsonPath().getString("fonts[0]")).then().statusCode(200).contentType("font/ttf").extract().asByteArray();
        assertArrayEquals(FONT, f);

        // Copie : vidéo seule dans s_0, une piste audio par fichier, durée conservée.
        Path dir = cache.dir(key("Gen - S01E01.mkv"));
        assertEquals("h264", ffprobe(dir.resolve("s_0.m4s"), "stream=codec_name"));
        assertEquals("aac", ffprobe(dir.resolve("s_1.m4s"), "stream=codec_name"));
        assertTrue(Files.isRegularFile(dir.resolve("font_0.ttf")) && !Files.exists(dir.resolve("evil.ttf")));
        assertEquals(14, WebPrepSteps.duration(Files.readString(dir.resolve("s_0.m3u8"))), 0.6);
    }

    @Test
    void mp4IsReadDirectlyWithItsSubtitlesAsWebVtt() throws Exception {
        playback("Gen - S01E02.mp4", "h264,aac").then().statusCode(202);
        assertTrue(prep.processNext());
        Response r = playback("Gen - S01E02.mp4", "h264,aac");
        r.then().statusCode(200).body("mode", equalTo("DIRECT")).body("subtitles[0].format", equalTo("vtt"));
        given().get(r.jsonPath().getString("subtitles[0].url")).then().statusCode(200).body(org.hamcrest.Matchers.containsString("Bonjour"));
        assertFalse(Files.exists(cache.dir(key("Gen - S01E02.mp4")).resolve("s_0.m3u8")), "pas de copie HLS pour un MP4 lisible");
    }

    @Test
    void hevcCopyIsTaggedHvc1() throws Exception {
        assumeTrue(Files.exists(ROOT.resolve("Gen/Gen - S01E03.mkv")), "libx265 absent");
        playback("Gen - S01E03.mkv", "h264,hevc,aac").then().statusCode(202);
        assertTrue(prep.processNext());
        playback("Gen - S01E03.mkv", "h264,hevc,aac").then().statusCode(200).body("mode", equalTo("HLS"));
        // Navigateur sans HEVC : conversion pour le navigateur (10.3).
        playback("Gen - S01E03.mkv", "h264,aac").then().statusCode(202).body("preparing.conversion", equalTo(true));
        Path dir = cache.dir(key("Gen - S01E03.mkv"));
        assertEquals("hevc,hvc1", ffprobe(dir.resolve("s_0.m4s"), "stream=codec_name,codec_tag_string"));
    }

    @Test
    void earlyPlaybackNeedsTwoSegmentsPerTrack() throws Exception {
        Path part = Files.createTempDirectory("web-early");
        WebManifest m = new WebManifest(60.0, "matroska", new WebManifest.Video(0, "h264", 8, 320, 240, 0.0),
                List.of(new WebManifest.Audio(0, 1, "aac", 2, "jpn", null, true, 1)), List.of(), List.of(), false, true, false);
        WebPrepSteps steps = new WebPrepSteps();
        assertFalse(steps.playableEarly(part, m));
        Files.writeString(part.resolve("s_0.m3u8"), "#EXTM3U\n#EXTINF:6,\ns_0.m4s\n#EXTINF:6,\ns_0.m4s\n");
        assertFalse(steps.playableEarly(part, m), "piste audio pas encore commencée");
        Files.writeString(part.resolve("s_1.m3u8"), "#EXTM3U\n#EXTINF:6,\ns_1.m4s\n#EXTINF:6,\ns_1.m4s\n");
        assertTrue(steps.playableEarly(part, m));
    }
}
