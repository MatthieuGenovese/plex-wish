package fr.plexwish.animeserver.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Installe les faux ffprobe / ffmpeg (scripts sh) et leurs réponses dans target/. */
final class FakeTools {

    static final Path TARGET = Path.of("target");

    private FakeTools() {
    }

    static void install() throws IOException {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "scripts sh : Linux / macOS seulement");
        Files.createDirectories(TARGET.resolve("ffprobe-fixtures"));
        for (String f : List.of("avi-xvid-mp3.json", "ogm-mpeg4-vorbis.json", "mp4-real.json", "mkv-h264-aac-srt-pgs.json",
                "mkv-hevc10-aac-ass.json", "copy-short.json", "copy-noaudio.json")) {
            copy("/ffprobe/" + f, TARGET.resolve("ffprobe-fixtures").resolve(f));
        }
        for (String s : List.of("fake-ffprobe.sh", "fake-ffmpeg.sh")) {
            Path p = TARGET.resolve(s);
            copy("/" + s, p);
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Files.deleteIfExists(TARGET.resolve("fake-ffprobe.log"));
        Files.deleteIfExists(TARGET.resolve("fake-ffmpeg.log"));
    }

    static List<String> log(String tool) throws IOException {
        Path p = TARGET.resolve("fake-" + tool + ".log");
        return Files.exists(p) ? Files.readAllLines(p) : List.of();
    }

    private static void copy(String resource, Path to) throws IOException {
        try (InputStream in = FakeTools.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("ressource absente : " + resource);
            }
            Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
