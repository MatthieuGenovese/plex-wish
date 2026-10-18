package fr.plexwish.animeserver.live;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lecture et correction du MP4 fragmenté écrit par le vrai ffmpeg (ignoré s'il est absent) : même commande que la
 * lecture à la volée (docs/LIVE-REMUX.md §2.3), sur un petit fichier généré.
 */
class Fmp4Test {

    static byte[] stream;

    @BeforeAll
    static void generate() throws Exception {
        assumeTrue(LiveTools.has("ffmpeg"), "ffmpeg absent");
        Process p = new ProcessBuilder("ffmpeg", "-nostdin", "-hide_banner", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=6", "-f", "lavfi", "-i", "sine=duration=6",
                "-map", "0:v", "-map", "1:a", "-map", "1:a", "-c:v", "mpeg4", "-g", "25", "-c:a", "aac",
                "-f", "mp4", "-movflags", "+frag_keyframe+empty_moov+default_base_moof+separate_moof", "pipe:1")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        stream = p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0);
    }

    record Parsed(byte[] header, List<byte[]> moofs, List<byte[]> mdats) {
    }

    static Parsed parse(byte[] data) throws Exception {
        InputStream in = new ByteArrayInputStream(data);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        List<byte[]> moofs = new ArrayList<>();
        List<byte[]> mdats = new ArrayList<>();
        for (Fmp4.Box b = Fmp4.next(in); b != null; b = Fmp4.next(in)) {
            switch (b.type()) {
                case "ftyp", "moov" -> header.write(b.bytes());
                case "moof" -> moofs.add(b.bytes());
                case "mdat" -> mdats.add(b.bytes());
                default -> {
                }
            }
        }
        return new Parsed(header.toByteArray(), moofs, mdats);
    }

    @Test
    void headerTracksAndOneTrackPerFragment() throws Exception {
        Parsed p = parse(stream);
        List<Fmp4.Track> tracks = Fmp4.tracks(p.header());
        assertEquals(3, tracks.size());
        assertEquals("vide", tracks.get(0).handler());
        assertEquals("soun", tracks.get(1).handler());
        assertEquals(p.moofs().size(), p.mdats().size());
        // Une image clé par seconde, 6 s : 6 fragments par piste, dans l'ordre vidéo, son, son.
        assertEquals(18, p.moofs().size());
        assertEquals(tracks.get(0).id(), Fmp4.trackId(p.moofs().get(0)));
        assertEquals(tracks.get(1).id(), Fmp4.trackId(p.moofs().get(1)));
        long scale = tracks.get(0).timescale();
        long previous = -1;
        for (byte[] m : p.moofs()) {
            if (Fmp4.trackId(m) == tracks.get(0).id()) {
                long t = Fmp4.decodeTime(m);
                assertTrue(t > previous);
                previous = t;
            }
        }
        assertEquals(5.0, (double) previous / scale, 0.05); // dernier fragment vidéo : 5 s
    }

    @Test
    void decodeTimeIsShiftedInPlace() throws Exception {
        byte[] moof = parse(stream).moofs().get(3).clone();
        int size = moof.length;
        long before = Fmp4.decodeTime(moof);
        Fmp4.shiftDecodeTime(moof, 123_456_789_000L);
        assertEquals(before + 123_456_789_000L, Fmp4.decodeTime(moof));
        assertEquals(size, moof.length);
        assertThrows(IllegalArgumentException.class, () -> Fmp4.shiftDecodeTime(moof, -999_999_999_999L));
    }

    @Test
    void trackHeaderKeepsOneTrackAndIsReadableByFfprobe() throws Exception {
        Parsed p = parse(stream);
        int audioId = Fmp4.tracks(p.header()).get(2).id();
        byte[] audio = Fmp4.trackHeader(p.header(), audioId);
        List<Fmp4.Track> kept = Fmp4.tracks(audio);
        assertEquals(1, kept.size());
        assertEquals(audioId, kept.get(0).id());
        assertThrows(IllegalArgumentException.class, () -> Fmp4.trackHeader(p.header(), 99));
        // En-tête de la piste + ses fragments = un fichier valide d'une seule piste audio.
        Path f = Files.createTempFile("fmp4-audio", ".mp4");
        try (var out = Files.newOutputStream(f)) {
            out.write(audio);
            for (int i = 0; i < p.moofs().size(); i++) {
                if (Fmp4.trackId(p.moofs().get(i)) == audioId) {
                    out.write(p.moofs().get(i));
                    out.write(p.mdats().get(i));
                }
            }
        }
        String probe = LiveTools.run("ffprobe", "-v", "error", "-show_entries", "stream=codec_type:format=duration",
                "-of", "csv=p=0", f.toString());
        Files.delete(f);
        List<String> lines = List.of(probe.strip().split("\\s+"));
        assertEquals("audio", lines.get(0));
        assertEquals(2, lines.size(), probe); // une seule piste, puis la durée
        assertEquals(6.0, Double.parseDouble(lines.get(1)), 0.2);
        assertNotNull(Fmp4.timescales(p.header()).get(audioId));
    }

    @Test
    void truncatedOrInvalidBoxesAreRejected() {
        assertThrows(java.io.EOFException.class, () -> Fmp4.next(new ByteArrayInputStream(new byte[]{0, 0, 0, 20, 'm', 'o'})));
        assertThrows(java.io.IOException.class,
                () -> Fmp4.next(new ByteArrayInputStream(new byte[]{0, 0, 0, 4, 'm', 'o', 'o', 'f'})));
    }
}
