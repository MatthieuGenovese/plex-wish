package fr.plexwish.animeserver.media;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lecture des sorties ffprobe réelles (fichiers du catalogue) et classification (règles de MediaRules). */
class MediaRulesTest {

    static ProbeFacts fixture(String name) throws IOException {
        try (InputStream in = MediaRulesTest.class.getResourceAsStream("/ffprobe/" + name + ".json")) {
            return ProbeFacts.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void realAviXvidMp3() throws IOException {
        ProbeFacts p = fixture("avi-xvid-mp3");
        assertEquals(1454.966667, p.durationSeconds(), 0.001);
        assertEquals("avi", p.formatName());
        assertEquals("mpeg4", p.video().codec());
        assertEquals("Advanced Simple Profile", p.video().profile());
        assertEquals(8, p.video().bitDepth());
        assertEquals(640, p.video().width());
        assertEquals(List.of("mp3"), p.audio().stream().map(ProbeFacts.Audio::codec).toList());
        assertEquals(2, p.audio().get(0).channels());
        assertTrue(p.subtitles().isEmpty()); // sous-titres incrustés dans l'image : rien à voir pour ffprobe
        MediaRules.Classification c = MediaRules.classify(p, "avi");
        assertEquals(MediaRules.Android.REMUX, c.android());
        assertTrue(c.androidReasons().get(0).contains("AVI"));
        assertFalse(c.browserPlayable());
        // Lecteur web (phase 10) : le conteneur ne compte plus (copie faite par le serveur), la vidéo Xvid est à convertir.
        assertEquals(List.of("MPEG-4 ASP (Xvid/DivX) : à convertir pour le navigateur"), c.browserReasons());
    }

    @Test
    void realOgm() throws IOException {
        MediaRules.Classification c = MediaRules.classify(fixture("ogm-mpeg4-vorbis"), "ogm");
        assertEquals(MediaRules.Android.REMUX, c.android());
        assertTrue(c.androidReasons().get(0).contains("OGM"));
        assertFalse(c.browserPlayable());
    }

    @Test
    void realMkvAndMp4() throws IOException {
        ProbeFacts mkv = fixture("mkv-h264-aac-srt-pgs");
        assertEquals("jpn", mkv.audio().get(0).language());
        assertEquals(2, mkv.subtitles().size());
        assertTrue(mkv.subtitles().get(0).isDefault());
        assertEquals("fre", mkv.subtitles().get(1).language());
        MediaRules.Classification c = MediaRules.classify(mkv, "mkv");
        assertEquals(MediaRules.Android.DIRECT, c.android());
        // MKV H.264 + AAC, SRT et PGS : lisible dans le lecteur web (copie HLS, SRT en WebVTT ; le PGS seul ne l'est pas).
        assertTrue(c.browserPlayable(), c.browserReasons().toString());

        MediaRules.Classification mp4 = MediaRules.classify(fixture("mp4-real"), "mp4");
        assertEquals(MediaRules.Android.DIRECT, mp4.android());
        assertTrue(mp4.browserPlayable(), mp4.browserReasons().toString());
        assertNull(fixture("mp4-real").audio().get(0).language()); // « und » → inconnue
    }

    @Test
    void hevc10WithAss() throws IOException {
        ProbeFacts p = fixture("mkv-hevc10-aac-ass");
        assertEquals(10, p.video().bitDepth());
        MediaRules.Classification c = MediaRules.classify(p, "mkv");
        assertEquals(MediaRules.Android.DIRECT, c.android());
        assertTrue(c.androidReasons().get(0).contains("HEVC 10 bits"));
        assertEquals(List.of("HEVC 10 bits : selon le navigateur"), c.browserReasons());
    }

    @Test
    void h264TenBitIsSoftwareDecodedOnAndroidAndNotPlayableInBrowsers() {
        ProbeFacts.Video v = new ProbeFacts.Video("h264", "High 10", 10, 1920, 1080, "yuv420p10le");
        ProbeFacts.Audio aac = new ProbeFacts.Audio("aac", null, 2, "jpn", true);
        MediaRules.Classification c = MediaRules.classify(new ProbeFacts(1.0, "mov,mp4,m4a,3gp,3g2,mj2", v, List.of(aac), List.of()), "mp4");
        assertEquals(MediaRules.Android.DIRECT, c.android());
        assertEquals(List.of("H.264 10 bits : lisible sur Android (décodage logiciel)"), c.androidReasons());
        assertFalse(c.browserPlayable());
        assertEquals(List.of("H.264 10 bits : à convertir pour le navigateur"), c.browserReasons());
        // 8 bits : rien à signaler.
        ProbeFacts.Video v8 = new ProbeFacts.Video("h264", "High", 8, 1920, 1080, "yuv420p");
        assertTrue(MediaRules.classify(new ProbeFacts(1.0, "mov,mp4,m4a,3gp,3g2,mj2", v8, List.of(aac), List.of()), "mp4").androidReasons().isEmpty());
    }

    @Test
    void transcodeCases() {
        ProbeFacts.Audio aac = new ProbeFacts.Audio("aac", null, 2, "jpn", true);
        // Vidéo que les téléphones ne décodent pas.
        for (String codec : List.of("msmpeg4v3", "wmv3", "theora", "mpeg2video")) {
            ProbeFacts p = new ProbeFacts(1400.0, "avi", new ProbeFacts.Video(codec, null, 8, 640, 480, "yuv420p"), List.of(aac), List.of());
            MediaRules.Classification c = MediaRules.classify(p, "avi");
            assertEquals(MediaRules.Android.TRANSCODE, c.android(), codec);
            assertTrue(c.androidReasons().get(0).contains("non décodé par Android"), codec);
        }
        // Son seulement en DTS : transcodage ; AC3 : lisible selon le téléphone.
        ProbeFacts.Video h264 = new ProbeFacts.Video("h264", "High", 8, 1920, 1080, "yuv420p");
        MediaRules.Classification dts = MediaRules.classify(new ProbeFacts(1.0, "matroska,webm", h264,
                List.of(new ProbeFacts.Audio("dts", null, 6, "jpn", true)), List.of()), "mkv");
        assertEquals(MediaRules.Android.TRANSCODE, dts.android());
        MediaRules.Classification ac3 = MediaRules.classify(new ProbeFacts(1.0, "matroska,webm", h264,
                List.of(new ProbeFacts.Audio("ac3", null, 6, "jpn", true)), List.of()), "mkv");
        assertEquals(MediaRules.Android.DIRECT, ac3.android());
        assertTrue(ac3.androidReasons().get(0).contains("dépend du téléphone"));
        assertTrue(ac3.browserReasons().contains("son AC3 : à convertir pour le navigateur"), ac3.browserReasons().toString());
        // VobSub : affiché sur Android, pas dans un navigateur.
        MediaRules.Classification vob = MediaRules.classify(new ProbeFacts(1.0, "matroska,webm", h264, List.of(aac),
                List.of(new ProbeFacts.Subtitle("dvd_subtitle", "fre", true, false))), "mkv");
        assertEquals(MediaRules.Android.DIRECT, vob.android());
        assertTrue(vob.browserReasons().contains("sous-titres en image (VobSub) : non affichables dans un navigateur"),
                vob.browserReasons().toString());
    }

    @Test
    void unreadableOutputIsRejected() {
        for (String bad : List.of("", "pas du json", "{}", "[]")) {
            try {
                ProbeFacts.parse(bad);
                throw new AssertionError("accepté : " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }
}
