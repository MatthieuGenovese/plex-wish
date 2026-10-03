package fr.plexwish.animeserver.poster;

import fr.plexwish.animeserver.poster.FakeImages.Reply;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Affiches sur le NAS contre un faux serveur d'images (aucun appel réseau réel) : TMDB d'abord puis AniList,
 * idempotence et réutilisation, reprise et ménage, refus (pas une image, signature, taille, redirection, hôte),
 * path traversal, repli quand le fichier local manque, 5 mois / 6 mois TMDB, purge, admin.
 */
@QuarkusTest
@QuarkusTestResource(value = FakeImagesResource.class, restrictToAnnotatedClass = true)
class PosterTest {

    static final Path ROOT = Path.of("target/test-library");
    static final Path POSTERS = Path.of("target/test-posters");
    static final byte[] TMDB_A = FakeImages.jpeg(1000, 1);
    static final byte[] ANILIST_A = FakeImages.jpeg(800, 2);
    static final byte[] ANILIST_B = FakeImages.png(600, 3);

    @Inject
    AgroalDataSource ds;
    @Inject
    PosterService service;
    @Inject
    PosterStore store;

    FakeImages fake;

    @BeforeEach
    void library() throws Exception {
        fake = FakeImagesResource.server;
        fake.reset();
        truncateLibrary(ds);
        deleteTree(ROOT);
        deleteTree(POSTERS);
        for (String folder : List.of("Alpha", "Beta", "Gamma")) {
            touch(ROOT, folder + "/" + folder + " - 01.mkv");
        }
        assertEquals("SUCCESS", scan().getString("status"));
        anilist("Alpha", "/anilist/a.jpg");
        tmdb("Alpha", "/a.jpg", "now()");
        anilist("Beta", "/anilist/b.png");
        fake.on("/t/p/w500/a.jpg", Reply.image("image/jpeg", TMDB_A));
        fake.on("/anilist/a.jpg", Reply.image("image/jpeg", ANILIST_A));
        fake.on("/anilist/b.png", Reply.image("image/png", ANILIST_B));
    }

    // --- Utilitaires --------------------------------------------------------------------------------

    private long id(String title) throws Exception {
        return count(ds, "SELECT id FROM anime WHERE title = '" + title + "'");
    }

    private void sql(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private void anilist(String title, String path) throws Exception {
        String url = path.startsWith("http") ? path : fake.url(path);
        sql("UPDATE anime SET poster_url = '" + url + "', poster_large_url = '" + url + "' WHERE id = " + id(title));
    }

    private void tmdb(String title, String posterPath, String fetchedAt) throws Exception {
        sql("INSERT INTO anime_tmdb (anime_id, status, tmdb_type, tmdb_id, language, poster_path, fetched_at, updated_by)"
                + " VALUES (" + id(title) + ", 'MATCHED', 'tv', " + id(title) + ", 'fr', '" + posterPath + "', " + fetchedAt + ", 'auto')"
                + " ON CONFLICT (anime_id) DO UPDATE SET poster_path = EXCLUDED.poster_path, fetched_at = EXCLUDED.fetched_at");
    }

    private void processAll() throws Exception {
        for (int i = 0; i < 50; i++) {
            if (service.processNext() instanceof PosterService.Idle) {
                return;
            }
        }
        throw new AssertionError("la tâche des affiches ne s'arrête pas");
    }

    private String column(String title, String column) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT p." + column + " FROM anime_poster p JOIN anime a ON a.id = p.anime_id"
                     + " WHERE a.title = '" + title + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private String posterUrl(String title) throws Exception {
        return given().auth().oauth2(adminToken()).get("/api/anime/" + id(title)).then().statusCode(200)
                .extract().path("posterUrl");
    }

    private List<Path> files() throws Exception {
        if (!Files.isDirectory(POSTERS)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(POSTERS)) {
            return s.filter(Files::isRegularFile).toList();
        }
    }

    // --- Téléchargement -----------------------------------------------------------------------------

    @Test
    void downloadsTmdbFirstThenAniListAndServesLocally() throws Exception {
        processAll();
        assertEquals("TMDB", column("Alpha", "provider"));
        assertEquals(1, fake.hits("/t/p/w500/a.jpg"));
        assertEquals(0, fake.hits("/anilist/a.jpg"), "TMDB d'abord : AniList pas téléchargée");
        assertEquals("ANILIST", column("Beta", "provider"));
        assertNull(column("Gamma", "status"));

        // Fichier nommé par son empreinte, rangé dans un sous-dossier de 2 caractères.
        String sha = column("Alpha", "sha256");
        assertEquals(PosterDownloader.sha256(TMDB_A), sha);
        assertEquals(sha.substring(0, 2) + "/" + sha + ".jpg", column("Alpha", "relative_path"));
        assertArrayEquals(TMDB_A, Files.readAllBytes(POSTERS.resolve(column("Alpha", "relative_path"))));
        assertTrue(column("Beta", "relative_path").endsWith(".png"));
        assertEquals(fake.url("/t/p/w500/a.jpg"), column("Alpha", "source_url"));
        assertTrue(count(ds, "SELECT count(*) FROM anime_poster WHERE fetched_at > now() - interval '1 minute'") == 2);

        // L'API renvoie l'URL locale ; l'image est servie sans authentification (balise <img>), avec cache long.
        String url = posterUrl("Alpha");
        assertTrue(url.matches("/api/posters/[0-9a-f]{32}"), url);
        given().auth().oauth2(adminToken()).get("/api/anime").then()
                .body("items.find { it.title == 'Alpha' }.posterUrl", equalTo(url))
                .body("items.find { it.title == 'Gamma' }.posterUrl", nullValue());
        Response r = given().get(url);
        assertEquals(200, r.statusCode());
        assertEquals("image/jpeg", r.contentType());
        assertArrayEquals(TMDB_A, r.asByteArray());
        assertTrue(r.header("Cache-Control").contains("immutable"));
        assertEquals("\"" + sha + "\"", r.header("ETag"));
        assertEquals("nosniff", r.header("X-Content-Type-Options"));
    }

    @Test
    void idempotentAndSharedSourceIsNotDownloadedTwice() throws Exception {
        processAll();
        int hits = fake.totalHits();
        processAll();
        assertEquals(hits, fake.totalHits(), "rien de nouveau : aucun appel");

        // Deuxième animé avec la même affiche TMDB (deux saisons d'une série) : réutilisée, pas retéléchargée.
        touch(ROOT, "Delta/Delta - 01.mkv");
        scan();
        tmdb("Delta", "/a.jpg", "now()");
        processAll();
        assertEquals(hits, fake.totalHits());
        assertEquals(column("Alpha", "relative_path"), column("Delta", "relative_path"));
        assertNotEquals(column("Alpha", "public_id"), column("Delta", "public_id"));
        assertEquals(2, files().size());
    }

    @Test
    void resumesAfterInterruptionAndSweepsLeftovers() throws Exception {
        // Interrompu après le premier animé : la reprise continue sans refaire le premier.
        assertInstanceOf(PosterService.Done.class, service.processNext());
        processAll();
        assertEquals(1, fake.hits("/t/p/w500/a.jpg"));
        assertEquals(1, fake.hits("/anilist/b.png"));

        // Fichier temporaire d'un téléchargement coupé, et fichier que plus rien ne référence : supprimés.
        Path shard = POSTERS.resolve("ff");
        Files.createDirectories(shard);
        Path tmp = shard.resolve(".tmp-1234");
        Files.write(tmp, new byte[]{1, 2, 3});
        Files.setLastModifiedTime(tmp, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
        Path orphan = shard.resolve("ff" + "0".repeat(62) + ".jpg");
        Files.write(orphan, FakeImages.jpeg(10, 9));
        Path unrelated = POSTERS.resolve("notes.txt");
        Files.writeString(unrelated, "à garder");
        assertEquals(2, service.sweep());
        assertFalse(Files.exists(tmp));
        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(unrelated), "seuls les fichiers au format des affiches sont touchés");
        assertTrue(Files.exists(POSTERS.resolve(column("Alpha", "relative_path"))));
    }

    // --- Refus ------------------------------------------------------------------------------------------

    @Test
    void rejectsNonImagesOversizedRedirectsAndForeignHosts() throws Exception {
        for (String t : List.of("Html", "Fake", "Big", "Chunked", "Redirect", "Evil")) {
            touch(ROOT, t + "/" + t + " - 01.mkv");
        }
        scan();
        anilist("Html", "/x/page.jpg");
        fake.on("/x/page.jpg", new Reply(200, Map.of("Content-Type", "text/html"), "<html>".getBytes(), false));
        anilist("Fake", "/x/fake.jpg");
        fake.on("/x/fake.jpg", Reply.image("image/jpeg", "GIF89a, pas un JPEG".getBytes()));
        anilist("Big", "/x/big.jpg");
        fake.on("/x/big.jpg", Reply.image("image/jpeg", FakeImages.jpeg((int) FakeImagesResource.MAX_BYTES + 1, 4)));
        anilist("Chunked", "/x/chunked.jpg");
        fake.on("/x/chunked.jpg", new Reply(200, Map.of("Content-Type", "image/jpeg"),
                FakeImages.jpeg((int) FakeImagesResource.MAX_BYTES * 2, 5), true));
        anilist("Redirect", "/x/moved.jpg");
        fake.on("/x/moved.jpg", new Reply(302, Map.of("Location", "http://localhost:1/elsewhere.jpg"), new byte[0], false));
        anilist("Evil", "https://evil.example/poster.jpg");

        processAll();
        for (String t : List.of("Html", "Fake", "Big", "Chunked", "Redirect", "Evil")) {
            assertEquals("FAILED", column(t, "status"), t);
            assertNull(column(t, "relative_path"), t);
        }
        assertTrue(column("Html", "last_error").contains("text/html"));
        assertTrue(column("Fake", "last_error").contains("pas une image"));
        assertTrue(column("Big", "last_error").contains("trop grosse"));
        assertTrue(column("Chunked", "last_error").contains("trop grosse"));
        assertTrue(column("Redirect", "last_error").contains("redirection"));
        assertTrue(column("Evil", "last_error").contains("hôte non autorisé"));
        assertEquals(2, files().size(), "seules les deux vraies affiches sont écrites");

        // Pas de nouvel essai d'une source refusée ; repli sur l'URL distante (le navigateur affichera le visuel de remplacement).
        int hits = fake.totalHits();
        processAll();
        assertEquals(hits, fake.totalHits());
        assertEquals(fake.url("/x/page.jpg"), posterUrl("Html"));
    }

    @Test
    void pathTraversalIsImpossible() throws Exception {
        processAll();
        for (String p : List.of("/api/posters/..%2F..%2F..%2Fetc%2Fpasswd", "/api/posters/%2e%2e%2f%2e%2e%2fpom.xml",
                "/api/posters/ab/" + "a".repeat(64) + ".jpg", "/api/posters/" + "A".repeat(32), "/api/posters/x")) {
            Response r = RestAssured.given().urlEncodingEnabled(false).get(p);
            assertTrue(r.statusCode() == 404 || r.statusCode() == 400, p + " → " + r.statusCode());
            assertFalse(r.asString().contains("root:"));
        }
        // Même un chemin falsifié en base n'est jamais suivi hors du dossier.
        String publicId = column("Alpha", "public_id");
        sql("UPDATE anime_poster SET relative_path = '../../pom.xml' WHERE public_id = '" + publicId + "'");
        given().get("/api/posters/" + publicId).then().statusCode(404);
        assertTrue(store.existing("../../pom.xml").isEmpty());
        assertTrue(store.existing("ab/../../pom.xml").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.write("../evil.jpg", new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> store.write("ab/" + "a".repeat(64) + ".exe", new byte[]{1}));
    }

    // --- Repli ------------------------------------------------------------------------------------------

    @Test
    void missingLocalFileFallsBackToRemoteThenIsDownloadedAgain() throws Exception {
        processAll();
        String oldUrl = posterUrl("Alpha");
        Files.delete(POSTERS.resolve(column("Alpha", "relative_path")));
        given().get(oldUrl).then().statusCode(404);
        assertEquals(fake.url("/t/p/w500/a.jpg"), posterUrl("Alpha"), "repli : URL distante");
        assertEquals("PENDING", column("Alpha", "status"));
        processAll();
        assertEquals(2, fake.hits("/t/p/w500/a.jpg"));
        assertTrue(posterUrl("Alpha").startsWith("/api/posters/"));
    }

    // --- Conditions TMDB ----------------------------------------------------------------------------------

    @Test
    void tmdbPostersRefreshedAtFiveMonthsAndErasedAtSix() throws Exception {
        processAll();
        String publicId = column("Alpha", "public_id");
        sql("UPDATE anime_poster SET fetched_at = now() - interval '160 days' WHERE provider = 'TMDB'");
        processAll();
        assertEquals(2, fake.hits("/t/p/w500/a.jpg"), "retéléchargée à 5 mois");
        assertEquals(publicId, column("Alpha", "public_id"), "même image : même URL");
        assertEquals(1, count(ds, "SELECT count(*) FROM anime_poster WHERE provider = 'TMDB' AND fetched_at > now() - interval '1 minute'"));

        // La fiche TMDB a plus de 6 mois (TMDB indisponible depuis) : affiche TMDB effacée, repli AniList.
        String tmdbFile = column("Alpha", "relative_path");
        sql("UPDATE anime_tmdb SET fetched_at = now() - interval '181 days'");
        processAll();
        assertFalse(Files.exists(POSTERS.resolve(tmdbFile)));
        assertEquals("ANILIST", column("Alpha", "provider"));
        assertEquals(1, fake.hits("/anilist/a.jpg"));
        assertTrue(posterUrl("Alpha").startsWith("/api/posters/"));

        // Une affiche TMDB dont la date a dépassé 6 mois n'est plus servie, même si le fichier est encore là.
        tmdb("Beta", "/a.jpg", "now()");
        processAll();
        String betaUrl = posterUrl("Beta");
        sql("UPDATE anime_poster SET fetched_at = now() - interval '181 days' WHERE provider = 'TMDB'");
        given().get(betaUrl).then().statusCode(404);
    }

    @Test
    void tmdbPurgeAlsoErasesTmdbPosterFiles() throws Exception {
        processAll();
        String file = column("Alpha", "relative_path");
        given().auth().oauth2(adminToken()).post("/api/admin/tmdb/purge?confirm=true").then().statusCode(200)
                .body("postersPurged", equalTo(1));
        assertFalse(Files.exists(POSTERS.resolve(file)));
        assertNull(column("Alpha", "status"));
        assertEquals(fake.url("/anilist/a.jpg"), posterUrl("Alpha"));
        assertEquals("ANILIST", column("Beta", "provider"), "les affiches AniList restent");
    }

    @Test
    void unavailableServerPausesWithoutLosingWork() throws Exception {
        fake.on("/t/p/w500/a.jpg", new Reply(429, Map.of("Retry-After", "1"), new byte[0], false));
        PosterService.Step s = service.processNext();
        assertEquals(Duration.ofSeconds(1), assertInstanceOf(PosterService.Unavailable.class, s).retryAfter());
        assertNull(column("Alpha", "status"), "rien d'enregistré : repris plus tard");
        fake.on("/t/p/w500/a.jpg", new Reply(503, Map.of(), new byte[0], false));
        Thread.sleep(1100);
        service.processNext();
        assertEquals("PENDING", column("Alpha", "status"));
        assertEquals("1", column("Alpha", "attempts"));
    }

    // --- Admin ------------------------------------------------------------------------------------------

    @Test
    void adminSummaryRedownloadAndPermissions() throws Exception {
        touch(ROOT, "Html/Html - 01.mkv");
        scan();
        anilist("Html", "/x/page.jpg");
        fake.on("/x/page.jpg", new Reply(200, Map.of("Content-Type", "text/html"), "<html>".getBytes(), false));
        processAll();
        String admin = adminToken();
        given().auth().oauth2(admin).get("/api/admin/posters/summary").then().statusCode(200)
                .body("total", equalTo(4)).body("local", equalTo(2)).body("localTmdb", equalTo(1))
                .body("localAniList", equalTo(1)).body("remote", equalTo(1)).body("missing", equalTo(1))
                .body("failed", equalTo(1)).body("diskBytes", equalTo(TMDB_A.length + ANILIST_B.length))
                .body("averageBytes", equalTo((TMDB_A.length + ANILIST_B.length) / 2))
                .body("estimatedBytes", equalTo((TMDB_A.length + ANILIST_B.length) / 2 * 3))
                .body("folderUsable", equalTo(true));
        given().auth().oauth2(admin).queryParam("filter", "failed").get("/api/admin/posters").then().statusCode(200)
                .body("total", equalTo(1)).body("items[0].title", equalTo("Html")).body("items[0].state", equalTo("REMOTE"))
                .body("items[0].lastError", containsString("text/html"));
        given().auth().oauth2(admin).queryParam("filter", "local").queryParam("q", "alp").get("/api/admin/posters").then()
                .body("items[0].posterUrl", startsWith("/api/posters/")).body("items[0].provider", equalTo("TMDB"));
        given().auth().oauth2(admin).queryParam("filter", "nope").get("/api/admin/posters").then().statusCode(400);

        // Retélécharger : la source réparée est retentée.
        fake.on("/x/page.jpg", Reply.image("image/jpeg", FakeImages.jpeg(700, 7)));
        given().auth().oauth2(admin).post("/api/admin/anime/" + id("Html") + "/poster/redownload").then().statusCode(200)
                .body("queued", equalTo(true));
        processAll();
        assertEquals("OK", column("Html", "status"));
        given().auth().oauth2(admin).post("/api/admin/anime/" + id("Gamma") + "/poster/redownload").then().statusCode(409)
                .body("error", equalTo("NO_POSTER_SOURCE"));
        given().auth().oauth2(admin).post("/api/admin/anime/999999/poster/redownload").then().statusCode(404);

        String name = unique("posteruser");
        createUser(name, "poster-user-password", "USER");
        String user = accessToken(name, "poster-user-password");
        given().auth().oauth2(user).get("/api/admin/posters/summary").then().statusCode(403);
        given().auth().oauth2(user).post("/api/admin/anime/" + id("Html") + "/poster/redownload").then().statusCode(403);
    }
}
