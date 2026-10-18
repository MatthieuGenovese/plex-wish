package fr.plexwish.animeserver.live;

import fr.plexwish.animeserver.media.MediaProbeService;
import fr.plexwish.animeserver.stream.StreamSigner;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lecture à la volée de bout en bout avec les vrais ffprobe / ffmpeg (ignoré s'ils manquent), docs/LIVE-REMUX.md :
 * AVI MPEG-4 à deux pistes audio (MP3 copiée, MP2 convertie en AAC), index des images clés, playlists, segments,
 * saut, sécurité des liens, plafond et une session par utilisateur.
 */
@QuarkusTest
@TestProfile(LiveStreamTest.Profile.class)
class LiveStreamTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("anime.media.ffprobe-path", "ffprobe", "anime.media.ffmpeg-path", "ffmpeg",
                    "anime.media.probe-timeout", "30s", "anime.media.live-segment-wait", "30s");
        }
    }

    static final Path ROOT = Path.of("target/test-library");
    static final Pattern URI = Pattern.compile("URI=\"([^\"]+)\"");

    @Inject
    AgroalDataSource ds;
    @Inject
    MediaProbeService probe;
    @Inject
    LiveSessionService sessions;
    @Inject
    StreamSigner signer;

    String user;
    long userId;

    @BeforeEach
    void library() throws Exception {
        assumeTrue(LiveTools.has("ffmpeg") && LiveTools.has("ffprobe") && LiveTools.hasEncoder("libmp3lame")
                && LiveTools.hasEncoder("mp2"), "ffmpeg / ffprobe (mpeg4, libmp3lame, mp2) absents");
        sessions.closeAll();
        sessions.maxSessionsForTests(null);
        truncateLibrary(ds);
        deleteTree(ROOT);
        for (String name : List.of("Gen - S01E01.avi", "Gen - S01E02.avi")) {
            Path f = ROOT.resolve("Gen/" + name);
            Files.createDirectories(f.getParent());
            // Image clé toutes les 2 s : segments de 6 s (0, 6, 12, 18, 24).
            LiveTools.run("ffmpeg", "-nostdin", "-hide_banner", "-v", "error", "-y",
                    "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=30",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=30", "-f", "lavfi", "-i", "sine=frequency=880:duration=30",
                    "-map", "0", "-map", "1", "-map", "2", "-c:v", "mpeg4", "-vtag", "XVID", "-g", "50", "-bf", "2",
                    "-c:a:0", "libmp3lame", "-c:a:1", "mp2", f.toString());
        }
        assertEquals("SUCCESS", scan(true).getString("status"));
        for (var w = probe.nextPending(); w.isPresent(); w = probe.nextPending()) {
            assertEquals("OK", probe.probe(w.get()));
        }
        String name = unique("liveuser");
        userId = createUser(name, "live-user-password", "USER");
        user = accessToken(name, "live-user-password");
    }

    @AfterEach
    void tearDown() {
        if (sessions != null) {
            sessions.closeAll();
            sessions.maxSessionsForTests(null);
        }
    }

    private long episode(String file) throws Exception {
        return count(ds, "SELECT e.id FROM episode e JOIN media_file f ON f.id = e.media_file_id WHERE f.file_name = '" + file + "'");
    }

    private long fileId(String file) throws Exception {
        return count(ds, "SELECT id FROM media_file WHERE file_name = '" + file + "'");
    }

    /** Lien HLS : 202 pendant le relevé des images clés, puis 200. */
    private String master(String token, String file) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            Response r = given().auth().oauth2(token).queryParam("hls", true).get("/api/episodes/" + episode(file) + "/stream-url");
            if (r.statusCode() == 200) {
                assertEquals("HLS", r.path("kind"));
                assertEquals("application/x-mpegURL", r.path("mimeType"));
                return r.path("url");
            }
            assertEquals(202, r.statusCode(), r.asString());
            assertEquals("PREPARING", r.path("state"));
            assertTrue(System.currentTimeMillis() < deadline, "index des images clés trop long");
            Thread.sleep(300);
        }
    }

    /** URL d'une ressource voisine de la playlist {@code base} (chemins relatifs des playlists). */
    private static String resolve(String base, String relative) {
        String dir = base.substring(0, base.indexOf('?'));
        return dir.substring(0, dir.lastIndexOf('/') + 1) + relative;
    }

    private static byte[] bytes(String url) {
        Response r = given().get(url);
        assertEquals(200, r.statusCode(), url + " : " + r.asString());
        return r.asByteArray();
    }

    record Fragments(List<Integer> tracks, List<Long> times) {
    }

    private static Fragments fragments(byte[] data) throws Exception {
        var in = new ByteArrayInputStream(data);
        List<Integer> tracks = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        for (Fmp4.Box b = Fmp4.next(in); b != null; b = Fmp4.next(in)) {
            if (b.type().equals("moof")) {
                tracks.add(Fmp4.trackId(b.bytes()));
                times.add(Fmp4.decodeTime(b.bytes()));
            }
        }
        return new Fragments(tracks, times);
    }

    @Test
    void androidGetsHlsPlaylistsSegmentsAndSeeks() throws Exception {
        String master = master(user, "Gen - S01E01.avi");
        long fid = fileId("Gen - S01E01.avi");
        assertTrue(master.startsWith("/api/stream/" + fid + "/live/master.m3u8?"), master);

        Response m = given().get(master);
        assertEquals(200, m.statusCode());
        assertTrue(m.contentType().startsWith("application/vnd.apple.mpegurl"));
        assertEquals("no-store", m.header("Cache-Control"));
        String text = m.asString();
        assertEquals(2, text.split("TYPE=AUDIO").length - 1, text);
        assertTrue(text.contains("DEFAULT=YES") && text.contains("NAME=\"Piste audio 2\""), text);
        String video = resolve(master, text.lines().filter(l -> l.startsWith("v.m3u8")).findFirst().orElseThrow());
        List<String> audio = new ArrayList<>();
        Matcher mm = URI.matcher(text);
        while (mm.find()) {
            audio.add(resolve(master, mm.group(1)));
        }

        String vText = given().get(video).asString();
        assertTrue(vText.contains("#EXT-X-PLAYLIST-TYPE:VOD") && vText.contains("#EXT-X-ENDLIST"), vText);
        List<Double> lengths = vText.lines().filter(l -> l.startsWith("#EXTINF:"))
                .map(l -> Double.parseDouble(l.substring(8, l.length() - 1))).toList();
        assertTrue(lengths.size() >= 4, vText); // segments de 6 s au moins, coupés sur les images clés
        double[] starts = new double[lengths.size()];
        for (int i = 1; i < starts.length; i++) {
            starts[i] = starts[i - 1] + lengths.get(i - 1);
        }
        assertEquals(30, lengths.stream().mapToDouble(Double::doubleValue).sum(), 0.2);
        List<String> segments = vText.lines().filter(l -> l.startsWith("v/") && l.contains(".m4s")).toList();
        Matcher map = URI.matcher(vText);
        assertTrue(map.find());
        byte[] vInit = bytes(resolve(video, map.group(1)));
        List<Fmp4.Track> vTracks = Fmp4.tracks(vInit);
        assertEquals(1, vTracks.size());
        assertEquals("vide", vTracks.get(0).handler());
        long scale = vTracks.get(0).timescale();

        // Lecture dans l'ordre puis saut vers la fin : chaque segment commence à sa borne du plan.
        byte[] all = vInit;
        int last = segments.size() - 1;
        for (int i : new int[]{0, 1, last, 2, last - 1}) {
            byte[] seg = bytes(resolve(video, segments.get(i)));
            Fragments f = fragments(seg);
            assertFalse(f.times().isEmpty());
            assertTrue(f.tracks().stream().allMatch(t -> t == vTracks.get(0).id()));
            assertEquals(starts[i], (double) f.times().get(0) / scale, 0.05, "segment " + i);
        }
        for (String s : segments) {
            byte[] seg = bytes(resolve(video, s));
            byte[] joined = new byte[all.length + seg.length];
            System.arraycopy(all, 0, joined, 0, all.length);
            System.arraycopy(seg, 0, joined, all.length, seg.length);
            all = joined;
        }
        Path joinedFile = Files.createTempFile("live-video", ".mp4");
        Files.write(joinedFile, all);
        String probed = LiveTools.run("ffprobe", "-v", "error", "-show_entries", "stream=codec_name:format=duration", "-of", "csv=p=0",
                joinedFile.toString());
        Files.delete(joinedFile);
        assertTrue(probed.startsWith("mpeg4"), probed);
        assertEquals(30.0, Double.parseDouble(probed.strip().split("\\s+")[1]), 0.3);

        // Pistes audio : MP3 copiée, MP2 convertie en AAC ; même découpage.
        for (String a : audio) {
            String aText = given().get(a).asString();
            Matcher am = URI.matcher(aText);
            assertTrue(am.find());
            byte[] aInit = bytes(resolve(a, am.group(1)));
            assertEquals("soun", Fmp4.tracks(aInit).get(0).handler());
            String seg2 = aText.lines().filter(l -> l.contains("/2.m4s")).findFirst().orElseThrow();
            Fragments f = fragments(bytes(resolve(a, seg2)));
            assertEquals(starts[2], (double) f.times().get(0) / Fmp4.tracks(aInit).get(0).timescale(), 0.1);
        }
    }

    @Test
    void linksAreSignedPerFileUserAndExpiry() throws Exception {
        String master = master(user, "Gen - S01E01.avi");
        long fid = fileId("Gen - S01E01.avi");
        long other = fileId("Gen - S01E02.avi");
        String query = master.substring(master.indexOf('?'));
        given().get(master.replace("sig=", "sig=x")).then().statusCode(403).body("error", equalTo("STREAM_URL_INVALID"));
        given().get("/api/stream/" + other + "/live/master.m3u8" + query).then().statusCode(403);
        given().get("/api/stream/" + fid + "/live/master.m3u8").then().statusCode(403);
        // L'original et le remux en cache ne s'ouvrent pas avec une signature de lecture à la volée.
        given().get("/api/stream/" + fid + query).then().statusCode(403);
        given().get("/api/stream/" + fid + "/remux" + query).then().statusCode(403);
        long past = System.currentTimeMillis() / 1000 - 10;
        given().get("/api/stream/" + fid + "/live/master.m3u8?u=" + userId + "&exp=" + past + "&sig="
                + signer.signature(StreamSigner.Target.LIVE, fid, userId, past)).then().statusCode(403)
                .body("error", equalTo("STREAM_URL_EXPIRED"));
        // Ressources inconnues.
        given().get("/api/stream/" + fid + "/live/a7.m3u8" + query).then().statusCode(404);
        given().get("/api/stream/" + fid + "/live/v/99.m4s" + query).then().statusCode(404);
        given().get("/api/stream/" + fid + "/live/x.m3u8" + query).then().statusCode(404);
        // Compte désactivé depuis l'émission du lien.
        patchUser(userId, Map.of("enabled", false)).then().statusCode(200);
        given().get(master).then().statusCode(403).body("error", equalTo("USER_DISABLED"));
    }

    @Test
    void oneSessionPerUserAndAGlobalLimit() throws Exception {
        sessions.maxSessionsForTests(1);
        String first = master(user, "Gen - S01E01.avi");
        String firstSeg = resolve(first, "v/0.m4s") + first.substring(first.indexOf('?'));
        bytes(firstSeg);
        assertEquals(1, sessions.activeSessions());
        // Un autre utilisateur : plafond atteint.
        String name = unique("liveother");
        createUser(name, "live-user-password", "USER");
        String token = accessToken(name, "live-user-password");
        String otherMaster = master(token, "Gen - S01E01.avi");
        given().get(resolve(otherMaster, "v/0.m4s") + otherMaster.substring(otherMaster.indexOf('?'))).then().statusCode(503)
                .body("error", equalTo("LIVE_BUSY"));
        // Le même utilisateur change d'épisode : l'ancienne lecture s'arrête, la nouvelle prend sa place.
        String second = master(user, "Gen - S01E02.avi");
        bytes(resolve(second, "v/0.m4s") + second.substring(second.indexOf('?')));
        assertEquals(1, sessions.activeSessions());
    }

    @Test
    void textSubtitlesOfTheFileAreExtractedAndTheNextEpisodeIsIndexedAhead() throws Exception {
        // MKV avec une piste SRT (anglais) et une piste ASS (français, par défaut), classé « remux nécessaire » à la main
        // (un MKV en mode compatibilité Windows ne se fabrique pas avec ffmpeg).
        Path dir = ROOT.resolve("Subs");
        Files.createDirectories(dir);
        Path srt = dir.resolve("a.srt");
        Files.writeString(srt, "1\n00:00:01,000 --> 00:00:03,000\nHello there\n");
        Path ass = dir.resolve("b.ass");
        Files.writeString(ass, """
                [Script Info]
                ScriptType: v4.00+

                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, Bold, Italic, Alignment
                Style: Default,Arial,20,&H00FFFFFF,0,0,2

                [Events]
                Format: Layer, Start, End, Style, Text
                Dialogue: 0,0:00:01.00,0:00:03.00,Default,Bonjour
                """);
        LiveTools.run("ffmpeg", "-nostdin", "-hide_banner", "-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=8", "-f", "lavfi", "-i", "sine=duration=8",
                "-i", srt.toString(), "-i", ass.toString(), "-map", "0", "-map", "1", "-map", "2", "-map", "3",
                "-c:v", "mpeg4", "-g", "50", "-c:a", "libmp3lame", "-c:s:0", "subrip", "-c:s:1", "ass",
                "-metadata:s:s:0", "language=eng", "-metadata:s:s:1", "language=fre", "-disposition:s:1", "default",
                dir.resolve("Subs - S01E01.mkv").toString());
        Files.delete(srt);
        Files.delete(ass);
        assertEquals("SUCCESS", scan(true).getString("status"));
        for (var w = probe.nextPending(); w.isPresent(); w = probe.nextPending()) {
            assertEquals("OK", probe.probe(w.get()));
        }
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("UPDATE media_probe SET android_class = 'REMUX' WHERE media_file_id = (SELECT id FROM media_file"
                    + " WHERE file_name = 'Subs - S01E01.mkv')");
        }
        master(user, "Subs - S01E01.mkv");
        Response r = given().auth().oauth2(user).queryParam("hls", true).get("/api/episodes/" + episode("Subs - S01E01.mkv") + "/stream-url");
        assertEquals(200, r.statusCode(), r.asString());
        List<Map<String, Object>> subs = r.path("subtitles");
        assertEquals(2, subs.size(), r.asString());
        assertEquals("Anglais", subs.get(0).get("label"));
        assertEquals("text/vtt", subs.get(0).get("mimeType"));
        assertEquals(false, subs.get(0).get("isDefault"));
        assertEquals("Français", subs.get(1).get("label"));
        assertEquals("text/x-ssa", subs.get(1).get("mimeType"));
        assertEquals(true, subs.get(1).get("isDefault"));
        Response vtt = given().get((String) subs.get(0).get("url"));
        assertEquals(200, vtt.statusCode());
        assertTrue(vtt.asString().startsWith("WEBVTT") && vtt.asString().contains("Hello there"), vtt.asString());
        Response ssa = given().get((String) subs.get(1).get("url"));
        assertEquals(200, ssa.statusCode());
        assertTrue(ssa.asString().contains("Bonjour"), ssa.asString());
        // Une signature d'un autre fichier n'ouvre pas ces sous-titres.
        String url = (String) subs.get(0).get("url");
        given().get(url.replace("/stream/" + fileId("Subs - S01E01.mkv") + "/", "/stream/" + fileId("Gen - S01E01.avi") + "/"))
                .then().statusCode(403);

        // Pendant la lecture de l'épisode 1, l'index de l'épisode 2 est relevé à l'avance.
        master(user, "Gen - S01E01.avi");
        long deadline = System.currentTimeMillis() + 20_000;
        while (count(ds, "SELECT count(*) FROM media_keyframes WHERE media_file_id = " + fileId("Gen - S01E02.avi")) == 0) {
            assertTrue(System.currentTimeMillis() < deadline, "épisode suivant non indexé");
            Thread.sleep(200);
        }
    }

    @Test
    void withoutHlsTheAndroidAnswerIsUnchanged() throws Exception {
        Response r = given().auth().oauth2(user).get("/api/episodes/" + episode("Gen - S01E01.avi") + "/stream-url");
        assertTrue(r.statusCode() == 202 || r.statusCode() == 200, r.asString());
        assertFalse(r.asString().contains("\"HLS\""), r.asString());
    }
}
