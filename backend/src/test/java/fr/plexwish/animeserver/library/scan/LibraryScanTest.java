package fr.plexwish.animeserver.library.scan;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.accessToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.touch;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Scan sur une vraie petite arborescence (profil test : library.media-root = target/test-library) :
 * import, idempotence, ajouts/disparitions, rebranchement, doublons, garde-fous, corrections manuelles.
 */
@QuarkusTest
class LibraryScanTest {

    static final Path ROOT = Path.of("target/test-library");

    static final List<String> BASE = List.of(
            "Frieren/Season 01/Frieren - S01E01.mkv",
            "Frieren/Season 01/Frieren - S01E02.mkv",
            "Frieren/OAV/Frieren - S00E01.mkv",
            "Frieren/Extras/Frieren NCOP [BD 1080p].mkv",
            "Show/Season 01/Show - S02E03.mkv",             // dossier : saison 1, nom : saison 2
            "Show/Show - 03-04 [720p].mkv",                 // double épisode
            "Show/Mystery.mkv",                              // non résolu
            "Devilman Crybaby/Devilman Crybaby - S01E01 - Titre.mkv",
            "Devilman Crybaby/[Grp] Devilman Crybaby - S01E01.mkv",
            "loose.mkv");                                    // vidéo à la racine

    @Inject
    AgroalDataSource ds;
    @Inject
    ScanService scanService;

    @BeforeEach
    void freshLibrary() throws Exception {
        truncateLibrary(ds);
        deleteTree(ROOT);
        for (String p : BASE) {
            touch(ROOT, p);
        }
        Files.writeString(ROOT.resolve("Frieren/notes.txt"), "pas une vidéo");
        touch(ROOT, "Frieren/subs/Frieren - S01E01.ass");
        touch(ROOT, "Frieren/@eaDir/Frieren - S01E09.mkv"); // dossier technique Synology
    }

    private static long episodeId(String anime, int season, int number) {
        String token = adminToken();
        List<Map<String, Object>> animes = given().auth().oauth2(token).get("/api/anime").jsonPath().getList("$");
        long animeId = animes.stream().filter(a -> anime.equals(a.get("title")))
                .map(a -> ((Number) a.get("id")).longValue()).findFirst().orElseThrow();
        List<Map<String, Object>> seasons = given().auth().oauth2(token).get("/api/anime/" + animeId + "/seasons").jsonPath().getList("$");
        long seasonId = seasons.stream().filter(s -> ((Number) s.get("seasonNumber")).intValue() == season)
                .map(s -> ((Number) s.get("id")).longValue()).findFirst().orElseThrow();
        List<Map<String, Object>> episodes = given().auth().oauth2(token).get("/api/seasons/" + seasonId + "/episodes").jsonPath().getList("$");
        return episodes.stream().filter(e -> ((Number) e.get("episodeNumber")).intValue() == number)
                .map(e -> ((Number) e.get("id")).longValue()).findFirst().orElseThrow();
    }

    private long mediaFileId(String path) throws SQLException {
        return count(ds, "SELECT id FROM media_file WHERE relative_path = '" + path.replace("'", "''") + "'");
    }

    private void execute(String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    // --- Premier scan ---------------------------------------------------------------------------

    @Test
    void firstScanImportsAndReportsEveryCategory() {
        JsonPath r = scan();
        assertEquals("SUCCESS", r.getString("status"));
        assertEquals(10, r.getInt("stats.videos"));
        assertEquals(5, r.getInt("stats.episodes"));        // Frieren ×3, Show S02E03, Devilman E01
        assertEquals(1, r.getInt("stats.extras"));
        assertEquals(2, r.getInt("stats.unresolved"));      // Mystery, loose.mkv
        assertEquals(1, r.getInt("stats.multiEpisodes"));
        assertEquals(1, r.getInt("stats.duplicates"));
        assertEquals(1, r.getInt("stats.seasonMismatches"));
        assertEquals(10, r.getInt("stats.newFiles"));
        assertEquals(1, r.getInt("stats.otherFiles.subtitle"));
        assertEquals(1, r.getInt("stats.otherFiles.other"));
        assertEquals(3, r.getInt("stats.animeCount"));
        assertEquals(Map.of("DUPLICATE", 1, "MULTI_EPISODE", 1, "SEASON_MISMATCH", 1, "UNRESOLVED", 2),
                r.getMap("issueCounts"));
        given().auth().oauth2(adminToken()).queryParam("category", "SEASON_MISMATCH").get("/api/admin/library/issues").then()
                .body("items[0].seasonNumber", equalTo(2)).body("items[0].episodeNumber", equalTo(3))
                .body("items[0].seasonSource", equalTo("NAME_SXXEXX"));
        // Chaque fichier signalé porte son id : c'est ce que l'admin utilise pour le corriger.
        List<Object> ids = given().auth().oauth2(adminToken()).get("/api/admin/library/issues")
                .then().statusCode(200).body("total", equalTo(5)).extract().jsonPath().getList("items.mediaFileId");
        assertTrue(ids.stream().allMatch(java.util.Objects::nonNull), ids.toString());
    }

    @Test
    void readApiShowsOnlyVisibleEpisodesWithoutPaths() {
        scan();
        String token = adminToken();
        given().auth().oauth2(token).get("/api/anime?sort=title").then().statusCode(200)
                .body("title", contains("Devilman Crybaby", "Frieren", "Show"));
        long frierenId = given().auth().oauth2(token).get("/api/anime").jsonPath().getLong("find { it.title == 'Frieren' }.id");
        String detail = given().auth().oauth2(token).get("/api/anime/" + frierenId).then().statusCode(200)
                .body("seasons.label", contains("Saison 1", "Spéciaux"))   // spéciaux en dernier
                .body("seasons.episodeCount", contains(2, 1))
                .extract().asString();
        assertFalse(detail.contains(".mkv"));
        long e2 = episodeId("Frieren", 1, 2);
        String episode = given().auth().oauth2(token).get("/api/episodes/" + e2).then().statusCode(200)
                .body("animeTitle", equalTo("Frieren")).body("seasonNumber", equalTo(1)).body("episodeNumber", equalTo(2))
                .body("container", equalTo("mkv")).body("fileSize", equalTo(3))
                .extract().asString();
        assertFalse(episode.contains("Season 01"), "aucun chemin dans l'API de lecture");
        given().auth().oauth2(token).get("/api/anime?sort=recent").then().statusCode(200);
        given().auth().oauth2(token).get("/api/anime?sort=bogus").then().statusCode(400);
    }

    // --- Rescan ---------------------------------------------------------------------------------

    @Test
    void rescanIsIdempotent() throws SQLException {
        scan();
        long e1 = episodeId("Frieren", 1, 1);
        String counts = "SELECT (SELECT count(*) FROM anime) * 1000000 + (SELECT count(*) FROM season) * 10000"
                + " + (SELECT count(*) FROM episode) * 100 + (SELECT count(*) FROM media_file)";
        long before = count(ds, counts);

        JsonPath again = scan();
        assertEquals(0, again.getInt("stats.newFiles"));
        assertEquals(0, again.getInt("stats.missing"));
        assertEquals(0, again.getInt("stats.rebranched"));
        assertEquals(5, again.getInt("stats.episodes"));
        assertEquals(1, again.getInt("stats.duplicates"));
        assertEquals(before, count(ds, counts));
        assertEquals(e1, episodeId("Frieren", 1, 1));
    }

    @Test
    void addedMissingAndReappearingFiles() throws Exception {
        scan();
        long e2 = episodeId("Frieren", 1, 2);
        touch(ROOT, "Frieren/Season 01/Frieren - S01E03.mkv");
        Files.delete(ROOT.resolve("Frieren/Season 01/Frieren - S01E02.mkv"));

        JsonPath r = scan();
        assertEquals(1, r.getInt("stats.newFiles"));
        assertEquals(1, r.getInt("stats.missing"));
        assertEquals(1, r.getInt("issueCounts.MISSING"));
        String token = adminToken();
        given().auth().oauth2(token).get("/api/episodes/" + e2).then().statusCode(404); // caché, pas supprimé
        assertEquals(1, count(ds, "SELECT count(*) FROM media_file WHERE NOT available AND missing_since IS NOT NULL"));
        given().auth().oauth2(token).queryParam("category", "MISSING").get("/api/admin/library/issues").then()
                .body("items.relativePath", contains("Frieren/Season 01/Frieren - S01E02.mkv"));

        touch(ROOT, "Frieren/Season 01/Frieren - S01E02.mkv");
        JsonPath back = scan();
        assertEquals(0, back.getInt("stats.newFiles"));
        given().auth().oauth2(token).get("/api/episodes/" + e2).then().statusCode(200); // même id qu'avant
        assertEquals(0, count(ds, "SELECT count(*) FROM media_file WHERE NOT available"));
    }

    @Test
    void renamedFileIsRebranchedOnTheSameEpisode() throws Exception {
        scan();
        long e1 = episodeId("Frieren", 1, 1);
        Files.move(ROOT.resolve("Frieren/Season 01/Frieren - S01E01.mkv"),
                ROOT.resolve("Frieren/Season 01/Frieren - S01E01 [Meilleure version].mkv"));

        JsonPath r = scan();
        assertEquals(1, r.getInt("stats.rebranched"));
        assertEquals(1, r.getInt("stats.missing"));
        assertEquals(1, r.getInt("stats.duplicates")); // seul Devilman reste en doublon
        assertEquals(e1, episodeId("Frieren", 1, 1));
        assertEquals(1, count(ds, "SELECT count(*) FROM episode e JOIN media_file m ON m.id = e.media_file_id"
                + " WHERE e.id = " + e1 + " AND m.relative_path LIKE '%Meilleure version%' AND m.available"));
        assertEquals(1, count(ds, "SELECT count(*) FROM media_file WHERE relative_path = 'Frieren/Season 01/Frieren - S01E01.mkv' AND NOT available"));
    }

    @Test
    void duplicateKeepsTheFileAlreadyLinked() throws Exception {
        scan();
        long linked = count(ds, "SELECT e.media_file_id FROM episode e JOIN season s ON s.id = e.season_id"
                + " JOIN anime a ON a.id = s.anime_id WHERE a.title = 'Frieren' AND s.season_number = 1 AND e.episode_number = 1");
        // Nouveau fichier pour le même épisode, qui passe AVANT l'existant dans l'ordre alphabétique.
        touch(ROOT, "Frieren/Season 01/A - Frieren - S01E01.mkv");
        JsonPath r = scan();
        assertEquals(2, r.getInt("stats.duplicates"));
        assertEquals(linked, count(ds, "SELECT e.media_file_id FROM episode e JOIN season s ON s.id = e.season_id"
                + " JOIN anime a ON a.id = s.anime_id WHERE a.title = 'Frieren' AND s.season_number = 1 AND e.episode_number = 1"));
        given().auth().oauth2(adminToken()).queryParam("category", "DUPLICATE").queryParam("anime", "frier")
                .get("/api/admin/library/issues").then().statusCode(200)
                .body("total", equalTo(1))
                // Tout ce qu'il faut pour vérifier le doublon sans ouvrir les fichiers.
                .body("items[0].animeTitle", equalTo("Frieren"))
                .body("items[0].seasonNumber", equalTo(1))
                .body("items[0].episodeNumber", equalTo(1))
                .body("items[0].relativePath", equalTo("Frieren/Season 01/A - Frieren - S01E01.mkv"))
                .body("items[0].keptRelativePath", equalTo("Frieren/Season 01/Frieren - S01E01.mkv"))
                .body("items[0].seasonSource", equalTo("NAME_SXXEXX"))
                .body("items[0].keptSeasonSource", equalTo("NAME_SXXEXX"));
    }

    // --- Garde-fous ---------------------------------------------------------------------------

    @Test
    void emptyOrMissingMediaRootAbortsWithoutMarkingAnythingMissing() throws Exception {
        scan();
        deleteTree(ROOT);
        Files.createDirectories(ROOT);
        JsonPath empty = scan();
        assertEquals("FAILED", empty.getString("status"));
        org.hamcrest.MatcherAssert.assertThat(empty.getString("failureReason"), containsString("vide"));
        assertEquals(0, count(ds, "SELECT count(*) FROM media_file WHERE NOT available"));

        deleteTree(ROOT);
        JsonPath missing = scan();
        assertEquals("FAILED", missing.getString("status"));
        org.hamcrest.MatcherAssert.assertThat(missing.getString("failureReason"), containsString("montage NAS absent"));
        assertEquals(0, count(ds, "SELECT count(*) FROM media_file WHERE NOT available"));
    }

    @Test
    void onlyOneScanAtATimeAndOrphansAreFailedAtStartup() throws Exception {
        execute("INSERT INTO scan_run (status, triggered_by) VALUES ('RUNNING', 'crash-simulé')");
        given().auth().oauth2(adminToken()).post("/api/admin/library/scan").then()
                .statusCode(409).body("error", equalTo("SCAN_ALREADY_RUNNING"));

        assertEquals(1, scanService.failOrphans()); // ce que fait le démarrage du backend
        assertEquals(1, count(ds, "SELECT count(*) FROM scan_run WHERE status = 'FAILED' AND failure_reason = '"
                + ScanService.INTERRUPTED + "'"));
        assertEquals("SUCCESS", scan().getString("status"));
    }

    @Test
    void symlinksAreNeverFollowed() throws Exception {
        Path outside = Path.of("target/outside-media");
        deleteTree(outside);
        touch(outside, "Secret/Secret - S01E01.mkv");
        try {
            Files.createSymbolicLink(ROOT.resolve("Secret"), outside.toAbsolutePath().resolve("Secret"));
            Files.createSymbolicLink(ROOT.resolve("Frieren/link.mkv"), outside.toAbsolutePath().resolve("Secret/Secret - S01E01.mkv"));
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "liens symboliques non disponibles sur cette machine (Windows sans mode développeur)");
        }
        JsonPath r = scan();
        assertEquals(2, r.getInt("stats.symlinksSkipped"));
        assertEquals(0, count(ds, "SELECT count(*) FROM media_file WHERE relative_path LIKE '%Secret%' OR relative_path LIKE '%link.mkv'"));
    }

    // --- Corrections manuelles ---------------------------------------------------------------------

    @Test
    void manualOverrideIsAppliedAndNeverOverwrittenByARescan() throws Exception {
        scan();
        String token = adminToken();
        long mystery = mediaFileId("Show/Mystery.mkv");

        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("action", "EPISODE", "animeTitle", "Show", "seasonNumber", 1, "episodeNumber", 7))
                .put("/api/admin/library/files/" + mystery + "/override").then().statusCode(200)
                .body("relativePath", equalTo("Show/Mystery.mkv")).body("createdBy", equalTo("admin"));

        JsonPath r = scan();
        assertEquals(1, r.getInt("stats.overridesApplied"));
        assertEquals(1, r.getInt("stats.unresolved")); // il ne reste que loose.mkv
        long e7 = episodeId("Show", 1, 7);
        scan();
        assertEquals(e7, episodeId("Show", 1, 7)); // toujours là après un rescan

        // IGNORE sur un épisode reconnu : il disparaît de la liste ; suppression de la correction : il revient.
        long frierenE1 = mediaFileId("Frieren/Season 01/Frieren - S01E01.mkv");
        long episode1 = episodeId("Frieren", 1, 1);
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("action", "IGNORE"))
                .put("/api/admin/library/files/" + frierenE1 + "/override").then().statusCode(200);
        assertEquals(1, scan().getInt("stats.ignoredByOverride"));
        given().auth().oauth2(token).get("/api/episodes/" + episode1).then().statusCode(404);

        given().auth().oauth2(token).delete("/api/admin/library/files/" + frierenE1 + "/override").then().statusCode(204);
        scan();
        given().auth().oauth2(token).get("/api/episodes/" + episode1).then().statusCode(200);
        given().auth().oauth2(token).get("/api/admin/library/overrides").then().statusCode(200)
                .body("relativePath", contains("Show/Mystery.mkv"));
    }

    @Test
    void overrideValidation() throws Exception {
        scan();
        String token = adminToken();
        long mystery = mediaFileId("Show/Mystery.mkv");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("action", "EPISODE", "animeTitle", "Show"))
                .put("/api/admin/library/files/" + mystery + "/override").then().statusCode(400)
                .body("error", equalTo("INCOMPLETE_OVERRIDE"));
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("action", "DELETE_FILE"))
                .put("/api/admin/library/files/" + mystery + "/override").then().statusCode(400);
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("action", "IGNORE"))
                .put("/api/admin/library/files/999999/override").then().statusCode(404);
        given().auth().oauth2(token).delete("/api/admin/library/files/" + mystery + "/override").then().statusCode(404);
        given().auth().oauth2(token).queryParam("category", "NOPE").get("/api/admin/library/issues").then().statusCode(400);
    }

    // --- Permissions et identifiants ---------------------------------------------------------------

    @Test
    void permissions() {
        scan();
        String name = unique("reader");
        createUser(name, "reader-password", "USER");
        String user = accessToken(name, "reader-password");
        given().get("/api/anime").then().statusCode(401);
        given().auth().oauth2(user).get("/api/anime").then().statusCode(200);
        for (String path : List.of("/api/admin/library/scan-report", "/api/admin/library/issues", "/api/admin/library/overrides")) {
            given().auth().oauth2(user).get(path).then().statusCode(403);
            given().get(path).then().statusCode(401);
        }
        given().auth().oauth2(user).post("/api/admin/library/scan").then().statusCode(403);
        given().auth().oauth2(user).contentType(ContentType.JSON).body(Map.of("action", "IGNORE"))
                .put("/api/admin/library/files/1/override").then().statusCode(403);
    }

    @Test
    void onlyNumericIdsAreAccepted() {
        scan();
        String token = adminToken();
        for (String id : List.of("0", "-1", "999999", "abc", "..%2F..%2Fetc%2Fpasswd", "Frieren")) {
            given().auth().oauth2(token).urlEncodingEnabled(false).get("/api/episodes/" + id).then()
                    .statusCode(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(400), org.hamcrest.Matchers.is(404)))
                    .body(not(containsString(".mkv")));
            given().auth().oauth2(token).urlEncodingEnabled(false).get("/api/anime/" + id).then()
                    .statusCode(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(400), org.hamcrest.Matchers.is(404)));
        }
        assertTrue(true);
    }
}
