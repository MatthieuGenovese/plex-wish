package fr.plexwish.animeserver.media;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Intégration avec le vrai ffprobe / ffmpeg (ignoré s'ils ne sont pas installés) : petits fichiers générés
 * (MP4, MKV avec sous-titres ASS, AVI MPEG-4, Ogg Theora), analyse, classification, durée, test à blanc du remux.
 * L'OGM (MPEG-4 dans Ogg) ne se fabrique pas avec ffmpeg : il est couvert par la sortie ffprobe d'un vrai fichier
 * (MediaRulesTest).
 */
@QuarkusTest
@TestProfile(RealFfmpegTest.Profile.class)
class RealFfmpegTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("anime.media.ffprobe-path", "ffprobe", "anime.media.ffmpeg-path", "ffmpeg",
                    "anime.media.probe-timeout", "30s", "anime.media.remux-test-timeout", "60s");
        }
    }

    static final Path ROOT = Path.of("target/test-library");

    @Inject
    AgroalDataSource ds;
    @Inject
    MediaProbeService service;
    @Inject
    RemuxTestService remuxTest;
    @Inject
    RemuxService remux;

    private static boolean has(String tool, String what, String name) {
        try {
            Process p = new ProcessBuilder(tool, "-hide_banner", what).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(10, TimeUnit.SECONDS);
            return name == null ? p.exitValue() == 0 : out.contains(" " + name + " ");
        } catch (Exception e) {
            return false;
        }
    }

    private static void ffmpeg(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-hide_banner", "-v", "error", "-y"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, String.join(" ", cmd) + "\n" + out);
    }

    @BeforeEach
    void files() throws Exception {
        assumeTrue(has("ffprobe", "-version", null) && has("ffmpeg", "-version", null), "ffmpeg / ffprobe absents");
        truncateLibrary(ds);
        deleteTree(ROOT);
        Files.createDirectories(ROOT);
        String video = "testsrc=size=320x240:rate=25:duration=4";
        String audio = "sine=frequency=440:duration=4";
        Path mp4 = ROOT.resolve("Gen/Gen - S01E01.mp4");
        Files.createDirectories(mp4.getParent());
        ffmpeg("-f", "lavfi", "-i", video, "-f", "lavfi", "-i", audio, "-c:v", "mpeg4", "-c:a", "aac", mp4.toString());
        Path ass = ROOT.resolve("subs.ass");
        Files.writeString(ass, """
                [Script Info]
                ScriptType: v4.00+

                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, Bold, Italic, Alignment
                Style: Default,Arial,20,&H00FFFFFF,0,0,2

                [Events]
                Format: Layer, Start, End, Style, Text
                Dialogue: 0,0:00:00.50,0:00:03.00,Default,Bonjour
                """);
        ffmpeg("-f", "lavfi", "-i", video, "-f", "lavfi", "-i", audio, "-i", ass.toString(), "-map", "0", "-map", "1", "-map", "2",
                "-c:v", "mpeg4", "-c:a", "aac", "-c:s", "ass", "-metadata:s:s:0", "language=fre", "-metadata:s:a:0", "language=jpn",
                ROOT.resolve("Gen/Gen - S01E02.mkv").toString());
        Files.delete(ass);
        String mp3 = has("ffmpeg", "-encoders", "libmp3lame") ? "libmp3lame" : "mp2";
        ffmpeg("-f", "lavfi", "-i", video, "-f", "lavfi", "-i", audio, "-c:v", "mpeg4", "-vtag", "XVID", "-bf", "2", "-c:a", mp3,
                ROOT.resolve("Gen/Gen - S01E03.avi").toString());
        if (has("ffmpeg", "-encoders", "libtheora") && has("ffmpeg", "-encoders", "libvorbis")) {
            ffmpeg("-f", "lavfi", "-i", video, "-f", "lavfi", "-i", audio, "-c:v", "libtheora", "-c:a", "libvorbis",
                    ROOT.resolve("Gen/Gen - S01E04.ogg").toString());
        }
        assertEquals("SUCCESS", scan(true).getString("status"));
    }

    private String col(String file, String column) throws Exception {
        try (var c = ds.getConnection(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT p." + column + " FROM media_probe p JOIN media_file f ON f.id = p.media_file_id"
                     + " WHERE f.file_name = '" + file + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void realToolsProbeClassifyAndRemuxToNull() throws Exception {
        assertTrue(service.ffprobeVersion().startsWith("ffprobe version"));
        for (var w = service.nextPending(); w.isPresent(); w = service.nextPending()) {
            assertEquals("OK", service.probe(w.get()), w.get().relativePath());
        }
        assertEquals("DIRECT", col("Gen - S01E01.mp4", "android_class"));
        assertEquals("mpeg4", col("Gen - S01E01.mp4", "video_codec"));
        assertTrue(Math.abs(Double.parseDouble(col("Gen - S01E01.mp4", "duration_seconds")) - 4) < 0.2);
        assertEquals("DIRECT", col("Gen - S01E02.mkv", "android_class"));
        assertTrue(col("Gen - S01E02.mkv", "subtitles").contains("\"codec\": \"ass\"") && col("Gen - S01E02.mkv", "subtitles").contains("fre"));
        assertTrue(col("Gen - S01E02.mkv", "audio").contains("jpn"));
        assertTrue(col("Gen - S01E02.mkv", "browser_reasons").contains("sous-titres ASS"));
        assertEquals("REMUX", col("Gen - S01E03.avi", "android_class"));
        if (col("Gen - S01E04.ogg", "status") != null) {
            assertEquals("TRANSCODE", col("Gen - S01E04.ogg", "android_class")); // Theora : non décodé par Android
        }
        assertEquals(4, count(ds, "SELECT min(duration_seconds) FROM episode"));

        // Test à blanc avec le vrai ffmpeg : l'AVI passe avec les deux commandes, rien n'est écrit.
        long filesBefore;
        try (var s = Files.walk(ROOT)) {
            filesBefore = s.count();
        }
        assertTrue(remuxTest.start(null));
        Instant limit = Instant.now().plusSeconds(60);
        while (remuxTest.running() && Instant.now().isBefore(limit)) {
            Thread.sleep(50);
        }
        assertEquals(2, count(ds, "SELECT count(*) FROM remux_test_result WHERE ok"),
                String.valueOf(count(ds, "SELECT count(*) FROM remux_test_result")));
        try (var s = Files.walk(ROOT)) {
            assertEquals(filesBefore, s.count());
        }

        // Remux réel de l'AVI : copie MKV vérifiée par ffprobe (durée, vidéo, son), servie depuis le cache.
        remux.checkTools();
        long fileId = count(ds, "SELECT id FROM media_file WHERE file_name = 'Gen - S01E03.avi'");
        long size = count(ds, "SELECT file_size FROM media_file WHERE id = " + fileId);
        java.time.OffsetDateTime modified;
        try (var c = ds.getConnection(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT last_modified FROM media_file WHERE id = " + fileId)) {
            rs.next();
            modified = rs.getObject(1, java.time.OffsetDateTime.class);
        }
        assertTrue(remux.request(fileId, size, modified) instanceof RemuxService.Preparing);
        assertTrue(remux.processNext());
        RemuxService.Decision d = remux.request(fileId, size, modified);
        assertTrue(d instanceof RemuxService.Ready, String.valueOf(d));
        Path copy = remux.served(fileId, size, modified).orElseThrow();
        assertTrue(Files.size(copy) > 10_000);
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=format_name", "-of", "csv=p=0", copy.toString())
                .redirectErrorStream(true).start();
        assertEquals("matroska,webm", new String(p.getInputStream().readAllBytes()).trim().replace("\"", ""));
    }
}
