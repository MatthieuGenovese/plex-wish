package fr.plexwish.animeserver.library.scan;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.touch;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scan complet de la vraie bibliothèque, reproduite en fichiers vides (32 940 chemins de library-sample.txt) :
 * seuils, durée, idempotence du rescan. Sous Windows, les noms interdits (":", "?"…) ne peuvent pas être créés :
 * ils sont sautés et comptés.
 */
@QuarkusTest
@TestProfile(FullSampleScanTest.FullLibrary.class)
class FullSampleScanTest {

    static final Path ROOT = Path.of("target/full-library");

    public static class FullLibrary implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("library.media-root", ROOT.toString());
        }
    }

    @Inject
    AgroalDataSource ds;

    @Test
    void fullLibraryScanAndIdempotentRescan() throws Exception {
        truncateLibrary(ds);
        deleteTree(ROOT);
        List<String> paths;
        try (InputStream in = getClass().getResourceAsStream("/library-sample.txt")) {
            paths = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        }
        long t0 = System.nanoTime();
        int skipped = 0;
        for (String p : paths) {
            try {
                touch(ROOT, p);
            } catch (InvalidPathException | IOException e) {
                skipped++;
            }
        }
        System.out.printf("Arborescence factice : %d fichiers créés, %d sautés, en %d ms%n",
                paths.size() - skipped, skipped, (System.nanoTime() - t0) / 1_000_000);
        assertTrue(skipped < paths.size() / 100, "trop de noms impossibles à créer : " + skipped);

        JsonPath first = scan();
        System.out.println("Premier scan : " + first.getMap("stats") + " ; problèmes : " + first.getMap("issueCounts"));
        assertEquals("SUCCESS", first.getString("status"));
        int videos = first.getInt("stats.videos");
        int extras = first.getInt("stats.extras");
        int episodes = first.getInt("stats.episodes");
        int duplicates = first.getInt("stats.duplicates");
        assertTrue(videos >= 28_254 - skipped, "vidéos vues : " + videos);
        double recognised = 100.0 * (episodes + duplicates) / (videos - extras);
        assertTrue(recognised >= 97.0, "épisodes reconnus : " + recognised + " %");
        assertTrue(first.getInt("stats.animeCount") > 1_250, "animés : " + first.getInt("stats.animeCount"));
        assertEquals(videos, first.getInt("stats.newFiles"));

        long rows = count(ds, "SELECT (SELECT count(*) FROM episode) * 100000 + (SELECT count(*) FROM media_file)");
        JsonPath second = scan();
        System.out.println("Rescan : " + second.getMap("stats"));
        assertEquals(0, second.getInt("stats.newFiles"));
        assertEquals(0, second.getInt("stats.missing"));
        assertEquals(0, second.getInt("stats.rebranched"));
        assertEquals(episodes, second.getInt("stats.episodes"));
        assertEquals(duplicates, second.getInt("stats.duplicates"));
        assertEquals(rows, count(ds, "SELECT (SELECT count(*) FROM episode) * 100000 + (SELECT count(*) FROM media_file)"));

        deleteTree(ROOT); // 33 000 fichiers : on ne les laisse pas dans target/
        truncateLibrary(ds);
    }
}
