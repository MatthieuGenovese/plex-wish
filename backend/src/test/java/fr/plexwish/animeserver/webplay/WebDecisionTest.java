package fr.plexwish.animeserver.webplay;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Choix de la source pour un navigateur (docs/WEB-PLAYER.md §4.1) et analyse des pistes. */
class WebDecisionTest {

    static WebManifest.Video h264 = new WebManifest.Video(0, "h264", 8, 1920, 1080, 0.0);

    static WebManifest.Audio audio(int n, String codec, String lang, Integer rendition) {
        return new WebManifest.Audio(n, n + 1, codec, 2, lang, null, n == 0, rendition);
    }

    static WebManifest manifest(String container, WebManifest.Video v, List<WebManifest.Audio> a, boolean direct, boolean hls) {
        return new WebManifest(1440.0, container, v, a, List.of(), List.of(), direct, !direct, hls);
    }

    @Test
    void capsAreWhitelistedWithASafeDefault() {
        assertEquals(Set.of("h264", "aac", "mp3"), WebDecision.caps(null));
        assertEquals(Set.of("h264", "aac", "mp3"), WebDecision.caps(" , ,inconnu"));
        assertEquals(Set.of("hevc", "opus"), WebDecision.caps("HEVC, opus,<script>"));
    }

    @Test
    void mp4WithOneDecodableTrackIsReadDirectly() {
        WebManifest m = manifest("mp4", h264, List.of(audio(0, "aac", null, null)), true, false);
        WebDecision.Result r = WebDecision.decide(m, WebDecision.caps("h264,aac"));
        assertEquals(WebDecision.Mode.DIRECT, r.mode());
        assertEquals(1, r.audio().size());
    }

    @Test
    void videoTheBrowserCannotDecodeIsUnsupportedWithAReason() {
        WebManifest hi10 = manifest("matroska", new WebManifest.Video(0, "h264", 10, 1920, 1080, 0.0), List.of(audio(0, "aac", "jpn", 1)), false, true);
        WebDecision.Result r = WebDecision.decide(hi10, WebDecision.caps("h264,hevc,hevc10,aac"));
        assertEquals(WebDecision.Mode.UNSUPPORTED, r.mode());
        assertTrue(r.reason().contains("H.264 10 bits"), r.reason());

        WebManifest hevc10 = manifest("matroska", new WebManifest.Video(0, "hevc", 10, 1920, 1080, 0.0), List.of(audio(0, "aac", "jpn", 1)), false, true);
        assertEquals(WebDecision.Mode.UNSUPPORTED, WebDecision.decide(hevc10, WebDecision.caps("h264,hevc,aac")).mode());
        assertEquals(WebDecision.Mode.HLS, WebDecision.decide(hevc10, WebDecision.caps("h264,hevc10,aac")).mode());
    }

    @Test
    void mkvWithTwoTracksUsesTheCopyAndOnlyOffersDecodableTracks() {
        WebManifest m = manifest("matroska", h264, List.of(audio(0, "aac", "jpn", 1), audio(1, "ac3", "fre", 2)), false, true);
        WebDecision.Result chrome = WebDecision.decide(m, WebDecision.caps("h264,aac,opus"));
        assertEquals(WebDecision.Mode.HLS, chrome.mode());
        assertEquals(List.of(0), chrome.audio().stream().map(WebManifest.Audio::n).toList());
        assertEquals(List.of(1), chrome.missingAudio().stream().map(WebManifest.Audio::n).toList());
        assertEquals(2, WebDecision.decide(m, WebDecision.caps("h264,aac,ac3")).audio().size());
    }

    @Test
    void soundNobodyCanDecodeIsUnsupported() {
        WebManifest m = manifest("mp4", h264, List.of(audio(0, "dts", "jpn", null)), true, false);
        WebDecision.Result r = WebDecision.decide(m, WebDecision.caps("h264,aac"));
        assertEquals(WebDecision.Mode.UNSUPPORTED, r.mode());
        assertTrue(r.reason().contains("DTS"), r.reason());
    }

    @Test
    void copyNotMadeIsUnsupported() {
        WebManifest m = manifest("matroska", h264, List.of(audio(0, "aac", "jpn", 1)), false, false);
        assertEquals(WebDecision.Mode.UNSUPPORTED, WebDecision.decide(m, WebDecision.caps("h264,aac")).mode());
    }

    @Test
    void conversionIsOfferedWhenItWouldHelp() {
        WebManifest hi10 = manifest("matroska", new WebManifest.Video(0, "h264", 10, 1920, 1080, 0.0), List.of(audio(0, "aac", "jpn", 1)), false, true);
        assertTrue(WebDecision.decide(hi10, WebDecision.caps("h264,aac")).convert());
        // Navigateur sans AAC : la conversion (H.264 + AAC) ne l'aiderait pas.
        assertEquals(false, WebDecision.decide(hi10, WebDecision.caps("h264,opus")).convert());
        WebManifest dts = manifest("mp4", h264, List.of(audio(0, "dts", "jpn", null)), true, false);
        assertTrue(WebDecision.decide(dts, WebDecision.caps("h264,aac")).convert());
        // Vidéo que ffmpeg ne sait pas décoder (pas de décodeur AV1 dans l'image) : raison, pas d'attente inutile.
        WebManifest none = manifest("matroska", new WebManifest.Video(0, "prores", 10, 1920, 1080, 0.0), List.of(audio(0, "aac", "jpn", 1)), false, false);
        WebDecision.Result r = WebDecision.decide(none, WebDecision.caps("h264,aac"));
        assertEquals(false, r.convert());
        assertTrue(r.reason().contains("ne sait pas le convertir"), r.reason());
        assertEquals(false, WebDecision.decide(manifest("mp4", h264, List.of(audio(0, "aac", null, null)), true, false),
                WebDecision.caps("h264,aac")).convert());
    }

    @Test
    void conversionPlan() {
        WebManifest hi10 = new WebManifest(1420.0, "matroska", new WebManifest.Video(0, "h264", 10, 1920, 1080, 0.042),
                List.of(audio(0, "aac", "jpn", 1), audio(1, "ac3", "fre", 2), audio(2, "mp3", "eng", null)), List.of(), List.of(), false, false, false);
        WebManifest p = hi10.convertPlan(720);
        assertEquals(WebManifest.VIDEO, p.container());
        assertEquals(new WebManifest.Video(0, "h264", 8, 1280, 720, 0.042), p.video());
        assertEquals(List.of("aac", "aac", "mp3"), p.audio().stream().map(WebManifest.Audio::codec).toList());
        assertEquals(List.of(1, 2, 3), p.audio().stream().map(WebManifest.Audio::rendition).toList());
        assertEquals(1080, hi10.convertPlan(1080).video().height());
        // Vidéo déjà lisible partout : copiée telle quelle, seul le son change (« W2 »).
        WebManifest ac3 = new WebManifest(1420.0, "mp4", h264, List.of(audio(0, "ac3", "jpn", null)), List.of(), List.of(), true, false, false);
        WebManifest q = ac3.convertPlan(720);
        assertEquals(WebManifest.AUDIO_ONLY, q.container());
        assertEquals(1080, q.video().height());
        assertEquals(2, q.audio().get(0).channels());
        // Petite vidéo : jamais agrandie ; dimensions paires.
        WebManifest sd = new WebManifest(600.0, "avi", new WebManifest.Video(0, "mpeg4", null, 853, 481, null), List.of(), List.of(),
                List.of(), false, false, false);
        assertEquals(481, sd.convertPlan(720).video().height());
        assertEquals(WebPrepService.convertedBytes(p, 1_000_000_000L) > 300_000_000L, true);
    }

    @Test
    void planFromFfprobe() {
        String json = """
                {"format": {"format_name": "matroska,webm", "duration": "1420.5"},
                 "streams": [
                  {"index": 0, "codec_type": "video", "codec_name": "hevc", "pix_fmt": "yuv420p10le", "width": 1920, "height": 1080},
                  {"index": 1, "codec_type": "audio", "codec_name": "aac", "channels": 2, "tags": {"language": "jpn"}, "disposition": {"default": 1}},
                  {"index": 2, "codec_type": "audio", "codec_name": "dts", "channels": 6, "tags": {"language": "fre", "title": "VF\\u0007 5.1"}},
                  {"index": 3, "codec_type": "subtitle", "codec_name": "ass", "tags": {"language": "fre", "title": "Dialogues"}, "disposition": {"default": 1}},
                  {"index": 4, "codec_type": "subtitle", "codec_name": "hdmv_pgs_subtitle", "tags": {"language": "eng"}},
                  {"index": 5, "codec_type": "subtitle", "codec_name": "subrip", "disposition": {"forced": 1}},
                  {"index": 6, "codec_type": "attachment", "codec_name": "ttf", "tags": {"filename": "../../Arial Black.TTF", "mimetype": "application/x-truetype-font"}},
                  {"index": 7, "codec_type": "attachment", "codec_name": "otf", "tags": {"filename": "x.otf", "mimetype": "font/otf"}},
                  {"index": 8, "codec_type": "attachment", "tags": {"filename": "cover.jpg", "mimetype": "image/jpeg"}}
                 ]}""";
        WebManifest m = WebManifest.plan(json, "mkv");
        assertEquals("matroska", m.container());
        assertEquals(10, m.video().bitDepth());
        assertTrue(m.wantsHls() && !m.direct() && !m.hls());
        assertEquals(1, m.audio().get(0).rendition());
        assertEquals(null, m.audio().get(1).rendition()); // DTS : à convertir
        assertEquals("VF  5.1", m.audio().get(1).title());
        assertEquals(List.of("ass", "image", "text"), m.subtitles().stream().map(WebManifest.Subtitle::kind).toList());
        assertTrue(m.subtitles().get(2).forced());
        // Polices : noms fixés par le serveur (jamais celui de la pièce jointe), images ignorées.
        assertEquals(List.of("font_0.ttf", "font_1.otf"), m.fonts().stream().map(WebManifest.Font::file).toList());
        assertEquals(List.of(6, 7), m.fonts().stream().map(WebManifest.Font::index).toList());

        String mp4 = """
                {"format": {"format_name": "mov,mp4,m4a,3gp,3g2,mj2", "duration": "60"},
                 "streams": [{"index": 0, "codec_type": "video", "codec_name": "h264", "pix_fmt": "yuv420p"},
                             {"index": 1, "codec_type": "audio", "codec_name": "aac"}]}""";
        WebManifest direct = WebManifest.plan(mp4, "mp4");
        assertTrue(direct.direct() && !direct.wantsHls());
        assertTrue(direct.fonts().isEmpty());
    }

    @Test
    void labelsAreFrench() {
        assertEquals("Japonais", WebPlaybackResource.label("jpn", null, "Piste 1", false));
        assertEquals("Français (Panneaux)", WebPlaybackResource.label("fre", "Panneaux", "x", false));
        assertEquals("Piste 2", WebPlaybackResource.label(null, null, "Piste 2", false));
        assertEquals("Français — forcés", WebPlaybackResource.label("fra", null, "x", true));
        assertEquals("ja", WebDecision.bcp47("jpn"));
    }

    @Test
    void mediaPlaylistReferencesAreSignedAndAnythingElseIsNeutralised() {
        String in = """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-MAP:URI="s_0.m4s",BYTERANGE="1133@0"
                #EXTINF:7.966,
                #EXT-X-BYTERANGE:5348004@1133
                s_0.m4s
                #EXTINF:6.0,
                http://ailleurs.example/x.ts
                #EXT-X-ENDLIST
                """;
        String out = WebPlaybackResource.rewriteMediaPlaylist(in, n -> "/signed/" + n + "?sig=1");
        assertTrue(out.contains("#EXT-X-MAP:URI=\"/signed/s_0.m4s?sig=1\",BYTERANGE=\"1133@0\""), out);
        assertTrue(out.contains("\n/signed/s_0.m4s?sig=1\n"), out);
        assertTrue(out.contains("#http://ailleurs.example/x.ts"), out);
        assertTrue(out.endsWith("#EXT-X-ENDLIST\n"), out);
    }
}
