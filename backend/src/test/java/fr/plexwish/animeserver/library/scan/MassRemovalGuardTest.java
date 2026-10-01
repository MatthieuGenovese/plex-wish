package fr.plexwish.animeserver.library.scan;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.touch;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Garde-fou de disparition massive : plus de la moitié des fichiers connus absents → FAILED sans
 * AUCUNE écriture (ni fichier marqué disparu, ni nouvel animé, ni épisode rebranché), sauf confirmation.
 */
@QuarkusTest
class MassRemovalGuardTest {

    static final Path ROOT = LibraryScanTest.ROOT;

    @Inject
    AgroalDataSource ds;

    @BeforeEach
    void tenKnownEpisodes() throws Exception {
        truncateLibrary(ds);
        deleteTree(ROOT);
        for (int i = 1; i <= 10; i++) {
            touch(ROOT, "Frieren/Season 01/Frieren - S01E%02d.mkv".formatted(i));
        }
        assertEquals("SUCCESS", scan().getString("status"));
    }

    private void removeEpisodes(int howMany) throws Exception {
        for (int i = 1; i <= howMany; i++) {
            Files.delete(ROOT.resolve("Frieren/Season 01/Frieren - S01E%02d.mkv".formatted(i)));
        }
    }

    private String snapshot() throws Exception {
        return count(ds, "SELECT count(*) FROM media_file WHERE available") + "/"
                + count(ds, "SELECT count(*) FROM media_file") + "/"
                + count(ds, "SELECT count(*) FROM anime") + "/"
                + count(ds, "SELECT coalesce(sum(media_file_id), 0) FROM episode");
    }

    @Test
    void wrongFolderMountedAbortsWithoutWritingAnything() throws Exception {
        String before = snapshot();
        // Un autre dossier, qui contient d'autres vidéos, monté à la place de la bibliothèque.
        deleteTree(ROOT);
        touch(ROOT, "Autre Anime/Autre Anime - S01E01.mkv");
        touch(ROOT, "Frieren/Frieren - S01E01 [autre version].mkv"); // tenterait un rebranchement

        JsonPath r = scan();
        assertEquals("FAILED", r.getString("status"));
        assertEquals("MASS_REMOVAL", r.getString("failureCode")); // le front propose alors la confirmation
        assertThat(r.getString("failureReason"), containsString("10 fichiers connus sur 10 (100 %)"));
        assertThat(r.getString("failureReason"), containsString("Rien n'a été modifié"));
        assertThat(r.getString("failureReason"), containsString("confirmMassRemoval=true"));
        assertEquals(before, snapshot());
    }

    @Test
    void explicitConfirmationLetsTheScanGoThrough() throws Exception {
        removeEpisodes(8);
        assertEquals("FAILED", scan().getString("status"));

        JsonPath confirmed = scan(true);
        assertEquals("SUCCESS", confirmed.getString("status"));
        assertEquals(8, confirmed.getInt("stats.missing"));
        assertEquals(10, confirmed.getInt("stats.knownFiles"));
        assertTrue(confirmed.getBoolean("stats.massRemovalConfirmed"));
        assertEquals(2, count(ds, "SELECT count(*) FROM media_file WHERE available"));
    }

    @Test
    void halfOrLessIsNormalMoreIsBlocked() throws Exception {
        removeEpisodes(5);
        JsonPath half = scan();
        assertEquals("SUCCESS", half.getString("status")); // 5 sur 10 : pas plus de la moitié
        assertEquals(5, half.getInt("stats.missing"));
        assertTrue(!half.getBoolean("stats.massRemovalConfirmed"));

        // Sur les 5 restants, en retirer 3 : 3 sur 5 connus disponibles = 60 %.
        Files.delete(ROOT.resolve("Frieren/Season 01/Frieren - S01E06.mkv"));
        Files.delete(ROOT.resolve("Frieren/Season 01/Frieren - S01E07.mkv"));
        Files.delete(ROOT.resolve("Frieren/Season 01/Frieren - S01E08.mkv"));
        JsonPath r = scan();
        assertEquals("FAILED", r.getString("status"));
        assertThat(r.getString("failureReason"), containsString("3 fichiers connus sur 5 (60 %)"));
    }
}
