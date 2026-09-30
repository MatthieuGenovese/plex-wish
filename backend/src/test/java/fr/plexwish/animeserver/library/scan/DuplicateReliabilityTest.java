package fr.plexwish.animeserver.library.scan;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.touch;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Doublons : le fichier le plus fiable (SxxExx/NxEE, puis E\d+, puis numéro seul) est gardé, l'ordre
 * alphabétique ne départage qu'à fiabilité égale ; un épisode lié lors d'un scan précédent ne change pas.
 */
@QuarkusTest
class DuplicateReliabilityTest {

    static final Path ROOT = LibraryScanTest.ROOT;
    static final String BONUS = "Blue Exorcist/Blue Exorcist Bonus 01.mkv";
    static final String NUMBER_ONLY = "Blue Exorcist/Blue Exorcist 01.mkv";             // passe AVANT dans l'ordre alphabétique
    static final String NXEE = "Blue Exorcist/Saison 1/Blue Exorcist - 1x01 - Le mal est tapi au fond de nous.mkv";

    @Inject
    AgroalDataSource ds;

    @BeforeEach
    void clean() throws Exception {
        truncateLibrary(ds);
        deleteTree(ROOT);
    }

    /** Chemin du fichier lié à l'épisode (animé, saison, numéro), ou null. */
    String linkedPath(int season, int episode) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement st = c.prepareStatement("""
                SELECT m.relative_path FROM episode e JOIN season s ON s.id = e.season_id
                JOIN anime a ON a.id = s.anime_id JOIN media_file m ON m.id = e.media_file_id
                WHERE a.title = 'Blue Exorcist' AND s.season_number = ? AND e.episode_number = ?""")) {
            st.setInt(1, season);
            st.setInt(2, episode);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void realBlueExorcistCaseIsNoLongerADuplicate() throws Exception {
        touch(ROOT, BONUS);
        touch(ROOT, NXEE);
        JsonPath r = scan();
        assertEquals(0, r.getInt("stats.duplicates"));
        assertEquals(NXEE, linkedPath(1, 1));
        assertEquals(BONUS, linkedPath(0, 1)); // le bonus est un spécial
    }

    @Test
    void mostReliableFileWinsWithinAScan() throws Exception {
        touch(ROOT, NUMBER_ONLY);
        touch(ROOT, NXEE);
        JsonPath r = scan();
        assertEquals(1, r.getInt("stats.duplicates"));
        assertEquals(1, r.getInt("stats.episodes"));
        assertEquals(NXEE, linkedPath(1, 1)); // et non le premier par ordre alphabétique
        given().auth().oauth2(adminToken()).queryParam("category", "DUPLICATE").get("/api/admin/library/issues").then()
                .body("total", equalTo(1))
                .body("items[0].relativePath", equalTo(NUMBER_ONLY))
                .body("items[0].keptRelativePath", equalTo(NXEE))
                .body("items[0].seasonSource", equalTo("DEFAULT"))
                .body("items[0].keptSeasonSource", equalTo("NAME_NXEE"))
                .body("items[0].detail", containsString("plus fiable"));
        // Rescan : rien ne bouge.
        JsonPath again = scan();
        assertEquals(1, again.getInt("stats.duplicates"));
        assertEquals(NXEE, linkedPath(1, 1));
    }

    @Test
    void sameReliabilityKeepsAlphabeticalOrder() throws Exception {
        touch(ROOT, "Show/A - Show - S01E01.mkv");
        touch(ROOT, "Show/B - Show - S01E01.mkv");
        scan();
        given().auth().oauth2(adminToken()).queryParam("category", "DUPLICATE").get("/api/admin/library/issues").then()
                .body("items[0].relativePath", equalTo("Show/B - Show - S01E01.mkv"))
                .body("items[0].keptRelativePath", equalTo("Show/A - Show - S01E01.mkv"));
    }

    @Test
    void episodeLinkedInAPreviousScanDoesNotChange() throws Exception {
        touch(ROOT, NUMBER_ONLY);
        scan();
        assertEquals(NUMBER_ONLY, linkedPath(1, 1));

        touch(ROOT, NXEE); // arrive plus tard, plus fiable
        JsonPath r = scan();
        assertEquals(1, r.getInt("stats.duplicates"));
        assertEquals(NUMBER_ONLY, linkedPath(1, 1)); // déjà lié : inchangé
        given().auth().oauth2(adminToken()).queryParam("category", "DUPLICATE").get("/api/admin/library/issues").then()
                .body("items[0].relativePath", equalTo(NXEE))
                .body("items[0].keptRelativePath", equalTo(NUMBER_ONLY));
    }
}
