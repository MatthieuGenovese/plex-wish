package fr.plexwish.animeserver.cast;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.plexwish.animeserver.metadata.FakeAniList;
import fr.plexwish.animeserver.metadata.FakeAniList.Forced;
import fr.plexwish.animeserver.metadata.FakeAniListResource;
import fr.plexwish.animeserver.poster.FakeImages;
import fr.plexwish.animeserver.poster.FakeImages.Reply;
import fr.plexwish.animeserver.poster.FakeImagesResource;
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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Distribution (étape 6.3) contre un faux AniList et un faux serveur d'images (aucun appel réseau réel) :
 * suites (séries TV seulement, nombre de saisons du dossier), meilleur rôle, plafond, comédiens partagés, contenu
 * adulte, idempotence, reprise, AniList indisponible (y compris sur une suite), priorité aux métadonnées, images
 * (refus, path traversal, fichier disparu), page comédien limitée à la bibliothèque, purge, admin.
 */
@QuarkusTest
@QuarkusTestResource(value = FakeAniListResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = FakeImagesResource.class, restrictToAnnotatedClass = true)
class CastTest {

    static final Path ROOT = Path.of("target/test-library");
    static final Path CAST_IMAGES = Path.of("target/test-posters/cast");
    static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    AgroalDataSource ds;
    @Inject
    CastService service;

    FakeAniList anilist;
    FakeImages images;

    @BeforeEach
    void library() throws Exception {
        anilist = FakeAniListResource.server;
        images = FakeImagesResource.server;
        anilist.reset();
        images.reset();
        truncateLibrary(ds);
        sql("TRUNCATE person, cast_character, cast_image RESTART IDENTITY CASCADE");
        deleteTree(ROOT);
        deleteTree(CAST_IMAGES);
        touch(ROOT, "Frieren/Frieren - S01E01.mkv");
        touch(ROOT, "Frieren/Frieren - S02E01.mkv");
        touch(ROOT, "Solo/Solo - 01.mkv");
        touch(ROOT, "Adult/Adult - 01.mkv");
        touch(ROOT, "NoMatch/NoMatch - 01.mkv");
        touch(ROOT, "Gone/Gone - 01.mkv");
        assertEquals("SUCCESS", scan().getString("status"));
        match("Frieren", 100);
        match("Solo", 200);
        match("Adult", 300);
        match("Gone", 400);

        // Frieren : saison 1 (100) → suite TV 101 ; un film (900) et une histoire parallèle (102) ne sont pas suivis.
        anilist.onId(media(100, false, rel(rel("SEQUEL", 101, "TV"), rel("SEQUEL", 900, "MOVIE"), rel("SIDE_STORY", 102, "TV")),
                role("MAIN", 1, "Frieren", 11, "Atsumi Tanezaki", "種﨑敦美"),
                role("SUPPORTING", 2, "Fern", 12, "Kana Ichinose", "市ノ瀬加那"),
                role("SUPPORTING", 3, "Stark", 13, "Chiaki Kobayashi", "小林千晃"),
                role("BACKGROUND", 4, "Villageois", 14, "Figurant", null)));
        anilist.onId(media(101, false, rel(rel("SEQUEL", 103, "TV")),
                role("MAIN", 2, "Fern", 12, "Kana Ichinose", "市ノ瀬加那"),        // devient principale
                role("SUPPORTING", 3, "Stark", 13, "Chiaki Kobayashi", "小林千晃"),  // déjà là : une seule fois
                role("SUPPORTING", 5, "Sein", 11, "Atsumi Tanezaki", "種﨑敦美"),     // même comédienne, autre personnage
                role("SUPPORTING", 6, "Narrateur", null, null, null)));             // pas de doubleur japonais
        anilist.onId(media(103, false, rel(), role("MAIN", 7, "Saison 3", 15, "X", null)));
        anilist.onId(media(900, false, rel(), role("MAIN", 8, "Film", 16, "Y", null)));
        anilist.onId(media(102, false, rel(), role("MAIN", 9, "Spin-off", 17, "Z", null)));
        // Solo : la même comédienne (11) que Frieren.
        anilist.onId(media(200, false, rel(), role("MAIN", 20, "Héroïne", 11, "Atsumi Tanezaki", "種﨑敦美")));
        anilist.onId(media(300, true, rel(), role("MAIN", 30, "X", 31, "Y", null)));
        // Gone : plus aucun fichier disponible ; son comédien 41 n'existe que là.
        anilist.onId(media(400, false, rel(), role("MAIN", 40, "Fantôme", 41, "Comédien Fantôme", null)));
        for (int id : new int[]{1, 2, 3, 5, 6, 7, 20, 40}) {
            images.on("/c/" + id + ".png", Reply.image("image/png", FakeImages.png(500, id)));
        }
        for (int id : new int[]{11, 12, 13, 41}) {
            images.on("/p/" + id + ".jpg", Reply.image("image/jpeg", FakeImages.jpeg(800, id)));
        }
        Files.delete(ROOT.resolve("Gone/Gone - 01.mkv"));
        scan();
    }

    // --- Faux AniList ------------------------------------------------------------------------------------

    private ObjectNode media(int id, boolean adult, ArrayNode relations, ObjectNode... roles) {
        ObjectNode m = JSON.createObjectNode();
        m.put("id", id);
        m.put("isAdult", adult);
        m.put("format", "TV");
        m.putObject("relations").set("edges", relations);
        ArrayNode edges = m.putObject("characters").putArray("edges");
        for (ObjectNode r : roles) {
            edges.add(r);
        }
        return m;
    }

    private static ArrayNode rel(ObjectNode... edges) {
        ArrayNode a = JSON.createArrayNode();
        for (ObjectNode e : edges) {
            a.add(e);
        }
        return a;
    }

    private static ObjectNode rel(String type, int id, String format) {
        ObjectNode e = JSON.createObjectNode();
        e.put("relationType", type);
        ObjectNode n = e.putObject("node");
        n.put("id", id);
        n.put("type", "ANIME");
        n.put("format", format);
        n.put("isAdult", false);
        n.putObject("startDate").put("year", 2020 + id % 10);
        return e;
    }

    private ObjectNode role(String role, int characterId, String character, Integer personId, String person, String nativeName) {
        ObjectNode e = JSON.createObjectNode();
        e.put("role", role);
        ObjectNode c = e.putObject("node");
        c.put("id", characterId);
        c.putObject("name").put("full", character).putNull("native");
        c.putObject("image").put("medium", images.url("/c/" + characterId + ".png")).put("large", images.url("/c/L" + characterId + ".png"));
        ArrayNode vas = e.putArray("voiceActors");
        if (personId != null) {
            ObjectNode p = vas.addObject();
            p.put("id", personId);
            ObjectNode name = p.putObject("name").put("full", person);
            if (nativeName != null) name.put("native", nativeName); else name.putNull("native");
            p.putObject("image").put("large", images.url("/p/" + personId + ".jpg")).put("medium", images.url("/p/M" + personId + ".jpg"));
            p.put("languageV2", "Japanese");
        }
        return e;
    }

    // --- Utilitaires -------------------------------------------------------------------------------------

    private long id(String title) throws Exception {
        return count(ds, "SELECT id FROM anime WHERE title = '" + title + "'");
    }

    private void match(String title, int anilistId) throws Exception {
        sql("UPDATE anime SET metadata_provider = 'ANILIST', metadata_provider_id = '" + anilistId + "',"
                + " metadata_url = 'https://anilist.co/anime/" + anilistId + "' WHERE title = '" + title + "'");
    }

    private void sql(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private String state(String title, String column) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT s." + column + " FROM anime_cast_state s JOIN anime a ON a.id = s.anime_id"
                     + " WHERE a.title = '" + title + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private void processAll() throws Exception {
        for (int i = 0; i < 200; i++) {
            if (service.processNext(false) instanceof CastService.Idle) {
                return;
            }
        }
        throw new AssertionError("la tâche de distribution ne s'arrête pas");
    }

    private List<String> anilistRequests() {
        return anilist.requests();
    }

    private static io.restassured.specification.RequestSpecification admin() {
        return given().auth().oauth2(adminToken());
    }

    private List<Path> files() throws Exception {
        if (!Files.isDirectory(CAST_IMAGES)) return List.of();
        try (Stream<Path> s = Files.walk(CAST_IMAGES)) {
            return s.filter(Files::isRegularFile).toList();
        }
    }

    // --- Récupération -----------------------------------------------------------------------------------

    @Test
    void fetchesTvSequelsUpToLocalSeasonsWithBestRolePerCharacter() throws Exception {
        processAll();
        // 2 saisons dans le dossier : 100 puis sa suite TV 101 ; ni film, ni histoire parallèle, ni saison 3.
        assertTrue(anilistRequests().containsAll(List.of("id:100", "id:101")));
        assertFalse(anilistRequests().stream().anyMatch(r -> List.of("id:900", "id:102", "id:103").contains(r)), anilistRequests().toString());
        assertEquals("OK", state("Frieren", "status"));
        assertEquals("2", state("Frieren", "seasons"));

        admin().get("/api/anime/" + id("Frieren") + "/cast").then().statusCode(200)
                .body("source", equalTo("AniList")).body("sourceUrl", equalTo("https://anilist.co/anime/100"))
                // Principaux d'abord ; Fern une seule fois, avec son meilleur rôle ; figurant écarté.
                .body("items.character.name", contains("Frieren", "Fern", "Stark", "Sein", "Narrateur"))
                .body("items.role", contains("MAIN", "MAIN", "SUPPORTING", "SUPPORTING", "SUPPORTING"))
                .body("items.language", contains("ja", "ja", "ja", "ja", "ja"))
                .body("items[0].person.id", equalTo("11")).body("items[0].person.name", equalTo("Atsumi Tanezaki"))
                .body("items[0].person.nativeName", equalTo("種﨑敦美"))
                .body("items[3].person.id", equalTo("11"))  // même comédienne, deux personnages
                .body("items[4].person", nullValue());
        // Personnes dédoublonnées entre animés : Tanezaki n'existe qu'une fois.
        assertEquals(1, count(ds, "SELECT count(*) FROM person WHERE provider_id = '11'"));
        assertEquals(1, count(ds, "SELECT count(*) FROM cast_character WHERE provider_id = '2'"));
    }

    @Test
    void capsRolesMainFirst() throws Exception {
        ObjectNode[] roles = new ObjectNode[26];
        for (int i = 0; i < 26; i++) {
            roles[i] = role(i < 3 ? "SUPPORTING" : i < 23 ? "SUPPORTING" : "MAIN", 1000 + i, "P" + i, 2000 + i, "V" + i, null);
        }
        anilist.onId(media(200, false, rel(), roles));
        processAll();
        admin().get("/api/anime/" + id("Solo") + "/cast").then()
                .body("items", hasSize(20))
                .body("items[0].role", equalTo("MAIN")).body("items[2].role", equalTo("MAIN"))
                .body("items[3].character.name", equalTo("P0"));
    }

    @Test
    void adultEntriesAreExcludedAndUnmatchedAnimeAreNeverSearched() throws Exception {
        processAll();
        assertEquals("EXCLUDED", state("Adult", "status"));
        assertEquals(0, count(ds, "SELECT count(*) FROM anime_cast WHERE anime_id = " + id("Adult")));
        assertEquals(0, count(ds, "SELECT count(*) FROM person WHERE provider_id = '31'"));
        assertTrue(anilistRequests().stream().noneMatch(r -> r.startsWith("search:")), "aucun nouvel appariement");
        assertEquals(null, state("NoMatch", "status"));
    }

    @Test
    void idempotentAndResumable() throws Exception {
        // Interrompu après un animé : la reprise continue sans refaire.
        assertInstanceOf(CastService.Done.class, service.processNext(false));
        processAll();
        int requests = anilistRequests().size();
        int downloads = images.totalHits();
        assertTrue(downloads > 0);
        processAll();
        assertEquals(requests, anilistRequests().size(), "rien de nouveau : aucune requête AniList");
        assertEquals(downloads, images.totalHits(), "rien de nouveau : aucune image retéléchargée");
        assertEquals(1, anilistRequests().stream().filter("id:100"::equals).count());

        // L'appariement AniList change : la distribution est refaite.
        anilist.onId(media(201, false, rel(), role("MAIN", 21, "Autre héros", 12, "Kana Ichinose", "市ノ瀬加那")));
        match("Solo", 201);
        processAll();
        admin().get("/api/anime/" + id("Solo") + "/cast").then().body("items.character.name", contains("Autre héros"));
        // Le personnage « Héroïne » n'est plus utilisé : effacé, son image aussi.
        assertEquals(0, count(ds, "SELECT count(*) FROM cast_character WHERE provider_id = '20'"));

        // Rafraîchissement après 6 mois.
        sql("UPDATE anime_cast_state SET fetched_at = now() - interval '181 days' WHERE anime_id = " + id("Frieren"));
        processAll();
        assertEquals(2, anilistRequests().stream().filter("id:100"::equals).count());
    }

    // --- Indisponibilité, priorité ----------------------------------------------------------------------------

    @Test
    void unavailableAniListPausesWithoutLosingWork() throws Exception {
        anilist.always(new Forced(500, Map.of(), "{}"));
        assertInstanceOf(CastService.Unavailable.class, service.processNext(false));
        assertEquals(0, count(ds, "SELECT count(*) FROM anime_cast_state"));
        anilist.always(null);
        anilist.force(new Forced(429, Map.of("Retry-After", "1"), "{}"));
        CastService.Step s = service.processNext(false);
        assertEquals(Duration.ofSeconds(1), assertInstanceOf(CastService.Unavailable.class, s).retryAfter());
        Thread.sleep(1100);
        processAll();
        assertEquals("OK", state("Frieren", "status"));
    }

    @Test
    void failureOnASequelKeepsTheRestAndIsRetried() throws Exception {
        anilist.failId(101, new Forced(503, Map.of(), "{}"));
        CastService.Step step = null;
        for (int i = 0; i < 10 && !(step instanceof CastService.Unavailable); i++) {
            step = service.processNext(false);
        }
        assertInstanceOf(CastService.Unavailable.class, step);
        assertEquals("OK", state("Frieren", "status"));
        assertEquals("1", state("Frieren", "seasons"));
        assertTrue(state("Frieren", "last_error").contains("suite 101"));
        admin().get("/api/anime/" + id("Frieren") + "/cast").then()
                .body("items.character.name", contains("Frieren", "Fern", "Stark"));
        // Les autres animés continuent.
        processAll();
        assertEquals("OK", state("Solo", "status"));
        // Plus tard, la suite répond : la distribution est complétée.
        anilist.failId(101, null);
        sql("UPDATE anime_cast_state SET next_attempt_at = now() - interval '1 minute'");
        processAll();
        assertEquals("2", state("Frieren", "seasons"));
        assertEquals(null, state("Frieren", "last_error"));
    }

    @Test
    void metadataGoFirst() throws Exception {
        // Les métadonnées ont du travail : aucune requête AniList pour la distribution.
        CastService.Step s = service.processNext(true);
        assertEquals(new CastService.Idle(true), s);
        assertTrue(anilistRequests().isEmpty());
        processAll();
        // Les images, qui ne passent pas par l'API AniList, continuent même quand les métadonnées travaillent.
        sql("UPDATE cast_image SET status = 'PENDING', relative_path = NULL, public_id = NULL, sha256 = NULL");
        int hits = images.totalHits();
        assertInstanceOf(CastService.Done.class, service.processNext(true));
        assertEquals(hits + 1, images.totalHits());
    }

    // --- Images -------------------------------------------------------------------------------------

    @Test
    void imagesAreStoredValidatedAndServedLocally() throws Exception {
        images.on("/c/3.png", new Reply(200, Map.of("Content-Type", "text/html"), "<html>".getBytes(), false));
        images.on("/c/5.png", Reply.image("image/png", FakeImages.png(1024 * 1024 + 1, 5)));
        images.on("/c/6.png", Reply.image("image/png", "GIF89a…".getBytes()));
        processAll();
        Response r = admin().get("/api/anime/" + id("Frieren") + "/cast");
        String frierenImage = r.path("items[0].character.imageUrl");
        String tanezaki = r.path("items[0].person.imageUrl");
        assertTrue(frierenImage.matches("/api/cast-images/[0-9a-f]{32}"), frierenImage);
        assertTrue(tanezaki.matches("/api/cast-images/[0-9a-f]{32}"), tanezaki);
        // Refusées : pas une image, trop grosse, signature fausse → URL d'origine (le web affichera le repli).
        assertEquals(images.url("/c/3.png"), r.path("items[2].character.imageUrl"));
        assertEquals(images.url("/c/5.png"), r.path("items[3].character.imageUrl"));
        assertEquals(images.url("/c/6.png"), r.path("items[4].character.imageUrl"));
        assertEquals(3, count(ds, "SELECT count(*) FROM cast_image WHERE status = 'FAILED'"));
        // Aucune réponse ne contient de chemin de fichier.
        assertFalse(r.asString().contains(".png\"") && r.asString().contains("cast/"));
        assertFalse(r.asString().contains("test-posters"));

        Response img = given().get(tanezaki);
        assertEquals(200, img.statusCode());
        assertEquals("image/jpeg", img.contentType());
        assertArrayEquals(FakeImages.jpeg(800, 11), img.asByteArray());
        assertTrue(img.header("Cache-Control").contains("immutable"));
        // Une seule image par comédien, même s'il joue plusieurs rôles ou dans plusieurs animés.
        assertEquals(1, images.hits("/p/11.jpg"));

        // Fichier disparu : URL d'origine, puis retéléchargé.
        String publicId = tanezaki.substring(tanezaki.lastIndexOf('/') + 1);
        String rel = relPath(publicId);
        Files.delete(CAST_IMAGES.resolve(rel));
        given().get(tanezaki).then().statusCode(404);
        admin().get("/api/anime/" + id("Frieren") + "/cast").then().body("items[0].person.imageUrl", equalTo(images.url("/p/11.jpg")));
        processAll();
        admin().get("/api/anime/" + id("Frieren") + "/cast").then().body("items[0].person.imageUrl", startsWith("/api/cast-images/"));
        assertEquals(2, images.hits("/p/11.jpg"));
    }

    private String relPath(String publicId) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT relative_path FROM cast_image WHERE public_id = '" + publicId + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void pathTraversalIsImpossible() throws Exception {
        processAll();
        for (String p : List.of("/api/cast-images/..%2F..%2F..%2Fpom.xml", "/api/cast-images/%2e%2e%2fpom.xml",
                "/api/cast-images/ab/" + "a".repeat(64) + ".png", "/api/cast-images/" + "A".repeat(32), "/api/cast-images/x")) {
            Response r = RestAssured.given().urlEncodingEnabled(false).get(p);
            assertTrue(r.statusCode() == 404 || r.statusCode() == 400, p + " → " + r.statusCode());
            assertFalse(r.asString().contains("<project"));
        }
        String publicId;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT public_id FROM cast_image WHERE public_id IS NOT NULL LIMIT 1")) {
            rs.next();
            publicId = rs.getString(1);
        }
        sql("UPDATE cast_image SET relative_path = '../../../pom.xml' WHERE public_id = '" + publicId + "'");
        given().get("/api/cast-images/" + publicId).then().statusCode(404);
        admin().get("/api/people/..%2F1").then().statusCode(404);
    }

    // --- Page comédien ------------------------------------------------------------------------------------

    @Test
    void personPageListsOnlyAvailableLibraryAnime() throws Exception {
        processAll();
        String user = userToken();
        given().auth().oauth2(user).get("/api/people/11").then().statusCode(200)
                .body("id", equalTo("11")).body("name", equalTo("Atsumi Tanezaki")).body("nativeName", equalTo("種﨑敦美"))
                .body("imageUrl", startsWith("/api/cast-images/")).body("sourceUrl", equalTo("https://anilist.co/staff/11"))
                // Frieren (deux personnages) et Solo ; jamais d'animé hors bibliothèque.
                .body("roles.animeTitle", contains("Frieren", "Frieren", "Solo"))
                .body("roles.character.name", contains("Frieren", "Sein", "Héroïne"))
                .body("roles.role", contains("MAIN", "SUPPORTING", "MAIN"));
        // Comédien qui ne joue que dans un animé sans épisode disponible : pas de page.
        given().auth().oauth2(user).get("/api/people/41").then().statusCode(404).body("error", equalTo("PERSON_NOT_FOUND"));
        given().auth().oauth2(user).get("/api/anime/" + id("Gone") + "/cast").then().statusCode(404);
        given().auth().oauth2(user).get("/api/people/999999").then().statusCode(404);
        given().auth().oauth2(user).get("/api/people/abc").then().statusCode(404);
        given().get("/api/people/11").then().statusCode(401);
        given().get("/api/anime/" + id("Frieren") + "/cast").then().statusCode(401);
        // Pas de distribution : liste vide, sans source.
        given().auth().oauth2(user).get("/api/anime/" + id("NoMatch") + "/cast").then().statusCode(200)
                .body("items", hasSize(0)).body("source", nullValue());
    }

    // --- Admin --------------------------------------------------------------------------------------------

    @Test
    void adminSummaryListRefreshPurgeAndPermissions() throws Exception {
        processAll();
        admin().get("/api/admin/cast/summary").then().statusCode(200)
                .body("withAniList", equalTo(4)).body("counts.OK", equalTo(3)).body("counts.EXCLUDED", equalTo(1))
                .body("people", equalTo(4)).body("roles", equalTo(7)).body("maxRoles", equalTo(20))
                .body("folderUsable", equalTo(true)).body("diskBytes", not(equalTo(0)));
        admin().queryParam("filter", "missing").get("/api/admin/cast").then().statusCode(200)
                .body("items.title", containsInAnyOrder("Adult", "NoMatch"))
                .body("items.status", containsInAnyOrder("EXCLUDED", "NO_MATCH"));
        admin().queryParam("filter", "nope").get("/api/admin/cast").then().statusCode(400);

        admin().post("/api/admin/anime/" + id("NoMatch") + "/cast/refresh").then().statusCode(409).body("error", equalTo("NO_ANILIST_MATCH"));
        admin().post("/api/admin/anime/999999/cast/refresh").then().statusCode(404);
        admin().post("/api/admin/anime/" + id("Solo") + "/cast/refresh").then().statusCode(200).body("queued", equalTo(true));
        assertEquals("PENDING", state("Solo", "status"));
        processAll();
        assertEquals(2, anilistRequests().stream().filter("id:200"::equals).count());

        // « Effacer toutes les données TMDB » ne touche pas à la distribution (AniList).
        admin().post("/api/admin/tmdb/purge?confirm=true").then().statusCode(200);
        assertEquals(7, count(ds, "SELECT count(*) FROM anime_cast"));

        // Effacer toute la distribution : données et images.
        assertFalse(files().isEmpty());
        admin().post("/api/admin/cast/purge").then().statusCode(400).body("error", equalTo("CONFIRMATION_REQUIRED"));
        admin().post("/api/admin/cast/purge?confirm=true").then().statusCode(200).body("purged", equalTo(7));
        for (String t : List.of("anime_cast", "anime_cast_state", "person", "cast_character", "cast_image")) {
            assertEquals(0, count(ds, "SELECT count(*) FROM " + t), t);
        }
        assertTrue(files().isEmpty(), "images effacées");

        String user = userToken();
        given().auth().oauth2(user).get("/api/admin/cast/summary").then().statusCode(403);
        given().auth().oauth2(user).post("/api/admin/cast/purge?confirm=true").then().statusCode(403);
    }

    private static String userToken() {
        String name = unique("castuser");
        createUser(name, "cast-user-password", "USER");
        return accessToken(name, "cast-user-password");
    }
}
