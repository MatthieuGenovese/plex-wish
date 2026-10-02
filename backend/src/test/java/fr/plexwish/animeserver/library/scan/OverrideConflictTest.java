package fr.plexwish.animeserver.library.scan;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.touch;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correction manuelle vers un épisode déjà lié à un autre fichier : 409 sans replace=true (rien n'est modifié),
 * remplacement explicite, fichier délié signalé au rapport (donc corrigeable), annulation qui rétablit l'ancien
 * lien, et aucun rescan qui défait la correction. Chaque cas est joué avec un fichier corrigé qui passe AVANT
 * puis APRÈS le fichier lié dans l'ordre du scan (l'ordre alphabétique ne doit rien changer).
 */
@QuarkusTest
class OverrideConflictTest {

    static final Path ROOT = Path.of("target/test-library");
    static final String LINKED = "Show/Show - S01E01.mkv";
    /** Non résolus : « Mystery » passe avant « Show - S01E01 », « Zeta » après. */
    static final String BEFORE = "Show/Mystery.mkv";
    static final String AFTER = "Show/Zeta.mkv";

    @Inject
    AgroalDataSource ds;

    String token;

    @BeforeEach
    void library() throws Exception {
        truncateLibrary(ds);
        deleteTree(ROOT);
        for (String p : List.of(LINKED, "Show/Show - S01E02.mkv", BEFORE, AFTER)) {
            touch(ROOT, p);
        }
        assertEquals("SUCCESS", scan().getString("status"));
        token = adminToken();
    }

    private long fileId(String path) throws Exception {
        return count(ds, "SELECT id FROM media_file WHERE relative_path = '" + path + "'");
    }

    /** Fichier lié à Show S01E{n} (0 si aucun). */
    private long linkedTo(int episode) throws Exception {
        return count(ds, "SELECT coalesce(max(e.media_file_id), 0) FROM episode e JOIN season s ON s.id = e.season_id"
                + " JOIN anime a ON a.id = s.anime_id WHERE a.title = 'Show' AND s.season_number = 1 AND e.episode_number = " + episode);
    }

    private long episodeId(int episode) throws Exception {
        return count(ds, "SELECT e.id FROM episode e JOIN season s ON s.id = e.season_id"
                + " JOIN anime a ON a.id = s.anime_id WHERE a.title = 'Show' AND s.season_number = 1 AND e.episode_number = " + episode);
    }

    private ValidatableResponse override(String path, int episode, Boolean replace) throws Exception {
        var request = given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("action", "EPISODE", "animeTitle", "show", "seasonNumber", 1, "episodeNumber", episode));
        if (replace != null) {
            request.queryParam("replace", replace);
        }
        return request.put("/api/admin/library/files/" + fileId(path) + "/override").then();
    }

    private long overrides() throws Exception {
        return count(ds, "SELECT count(*) FROM media_file_override");
    }

    @Test
    void noConflict() throws Exception {
        override(BEFORE, 7, null).statusCode(200);
        JsonPath r = scan();
        assertEquals(fileId(BEFORE), linkedTo(7));
        assertEquals(fileId(LINKED), linkedTo(1));
        assertEquals(0, r.getInt("stats.duplicates"));
    }

    @Test
    void conflictWithoutReplaceIs409AndChangesNothing() throws Exception {
        long e1 = episodeId(1);
        override(BEFORE, 1, null).statusCode(409)
                .body("error", equalTo("EPISODE_ALREADY_LINKED"))
                .body("episode.animeTitle", equalTo("Show"))
                .body("episode.seasonNumber", equalTo(1))
                .body("episode.episodeNumber", equalTo(1))
                .body("currentFiles.mediaFileId", contains((int) fileId(LINKED)))
                .body("currentFiles.relativePath", contains(LINKED))
                .body("currentFiles.viaOverride", contains(false))
                .body("targetFile.mediaFileId", equalTo((int) fileId(BEFORE)))
                .body("targetFile.relativePath", equalTo(BEFORE));
        override(BEFORE, 1, false).statusCode(409);
        assertEquals(0, overrides());
        scan();
        assertEquals(fileId(LINKED), linkedTo(1));
        assertEquals(e1, episodeId(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {BEFORE, AFTER})
    void replaceThenCancel(String corrected) throws Exception {
        long e1 = episodeId(1);
        int episodesBefore = scan().getInt("stats.episodes");

        override(corrected, 1, true).statusCode(200);
        JsonPath r = scan();
        assertEquals(fileId(corrected), linkedTo(1), "le fichier corrigé prend l'épisode");
        assertEquals(e1, episodeId(1), "même épisode (même id)");
        assertEquals(episodesBefore, r.getInt("stats.episodes"));
        assertEquals(1, r.getInt("stats.duplicates"));
        // Le fichier délié reste disponible, et apparaît au rapport avec son id : il est corrigeable.
        assertEquals(1, count(ds, "SELECT count(*) FROM media_file WHERE available AND relative_path = '" + LINKED + "'"));
        given().auth().oauth2(token).queryParam("category", "DUPLICATE").get("/api/admin/library/issues").then()
                .body("total", equalTo(1))
                .body("items[0].mediaFileId", equalTo((int) fileId(LINKED)))
                .body("items[0].relativePath", equalTo(LINKED))
                .body("items[0].keptRelativePath", equalTo(corrected))
                .body("items[0].keptSeasonSource", equalTo("OVERRIDE"));

        // Un rescan ne défait pas la correction.
        for (int i = 0; i < 2; i++) {
            JsonPath again = scan();
            assertEquals(fileId(corrected), linkedTo(1));
            assertEquals(1, again.getInt("stats.duplicates"));
            assertEquals(0, again.getInt("stats.rebranched"));
        }

        // Annulation : l'ancien lien revient, sur le même épisode, et y reste.
        given().auth().oauth2(token).delete("/api/admin/library/files/" + fileId(corrected) + "/override").then().statusCode(204);
        JsonPath cancelled = scan();
        assertEquals(fileId(LINKED), linkedTo(1));
        assertEquals(e1, episodeId(1));
        assertEquals(0, cancelled.getInt("stats.duplicates"));
        assertEquals(episodesBefore, cancelled.getInt("stats.episodes"));
        given().auth().oauth2(token).queryParam("category", "UNRESOLVED").get("/api/admin/library/issues").then()
                .body("items.relativePath", contains(BEFORE, AFTER));
        scan();
        assertEquals(fileId(LINKED), linkedTo(1));
    }

    @Test
    void replacingAFileLinkedByAnotherCorrectionRemovesThatCorrection() throws Exception {
        override(BEFORE, 7, null).statusCode(200);
        scan();
        assertEquals(fileId(BEFORE), linkedTo(7));

        override(AFTER, 7, null).statusCode(409)
                .body("currentFiles.relativePath", contains(BEFORE))
                .body("currentFiles.viaOverride", contains(true));
        override(AFTER, 7, true).statusCode(200);
        given().auth().oauth2(token).get("/api/admin/library/overrides").then().body("relativePath", contains(AFTER));
        scan();
        assertEquals(fileId(AFTER), linkedTo(7));
        given().auth().oauth2(token).queryParam("category", "UNRESOLVED").get("/api/admin/library/issues").then()
                .body("items.relativePath", contains(BEFORE)); // redevenu non résolu, donc corrigeable
    }

    @Test
    void pendingCorrectionsConflictToo() throws Exception {
        override(BEFORE, 9, null).statusCode(200); // pas encore de scan : l'épisode 9 n'existe pas
        override(AFTER, 9, null).statusCode(409)
                .body("currentFiles.relativePath", contains(BEFORE))
                .body("currentFiles.viaOverride", contains(true));
        // Refaire la même correction sur le même fichier n'est pas un conflit.
        override(BEFORE, 9, null).statusCode(200);
    }

    @Test
    void noConflictWhenTheLinkedFileIsAlreadyBeingMovedAway() throws Exception {
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("action", "IGNORE"))
                .put("/api/admin/library/files/" + fileId(LINKED) + "/override").then().statusCode(200);
        override(BEFORE, 1, null).statusCode(200);
        scan();
        assertEquals(fileId(BEFORE), linkedTo(1));
        given().auth().oauth2(token).queryParam("category", "DUPLICATE").get("/api/admin/library/issues").then()
                .body("items", empty());
    }

    @Test
    void missingLinkedFileIsNotAConflict() throws Exception {
        java.nio.file.Files.delete(ROOT.resolve(LINKED));
        assertTrue(scan().getInt("stats.missing") == 1);
        override(BEFORE, 1, null).statusCode(200);
        scan();
        assertEquals(fileId(BEFORE), linkedTo(1));
    }
}
