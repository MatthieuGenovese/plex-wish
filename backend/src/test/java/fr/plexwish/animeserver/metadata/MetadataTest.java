package fr.plexwish.animeserver.metadata;

import fr.plexwish.animeserver.metadata.FakeAniList.Forced;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.touch;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tâche des métadonnées sur une vraie base, contre le faux AniList : résultats enregistrés, idempotence,
 * reprise après interruption, panne et limite de débit, bibliothèque utilisable sans l'API, correction
 * manuelle jamais écrasée.
 */
@QuarkusTest
@QuarkusTestResource(value = FakeAniListResource.class, restrictToAnnotatedClass = true)
class MetadataTest {

    static final Path ROOT = Path.of("target/test-library");
    static final List<String> FOLDERS = List.of("Chûnibyô Demo Koi ga Shitai!", "Hunter x Hunter", "Le Seigneur des Yôkai",
            "Sousou no Frieren");

    @Inject
    AgroalDataSource ds;
    @Inject
    MetadataService service;

    FakeAniList fake;

    @BeforeEach
    void library() throws Exception {
        fake = FakeAniListResource.server;
        fake.reset();
        truncateLibrary(ds);
        deleteTree(ROOT);
        for (String folder : FOLDERS) {
            touch(ROOT, folder + "/" + folder + " - S01E01.mkv");
        }
        assertEquals("SUCCESS", scan().getString("status"));
        fake.onSearch("Sousou no Frieren",
                FakeAniList.media(154587, "Sousou no Frieren", "Frieren: Beyond Journey's End", 2023, "TV", 28),
                FakeAniList.media(182255, "Sousou no Frieren 2nd Season", "Frieren: Beyond Journey's End Season 2", 2026, "TV", 10));
        fake.onSearch("Chûnibyô Demo Koi ga Shitai!",
                FakeAniList.media(14741, "Chuunibyou demo Koi ga Shitai!", "Love, Chunibyo & Other Delusions!", 2012, "TV", 12));
        fake.onSearch("Hunter x Hunter",
                FakeAniList.media(136, "HUNTER×HUNTER", "Hunter x Hunter", 1999, "TV", 62),
                FakeAniList.media(11061, "HUNTER×HUNTER (2011)", "Hunter x Hunter (2011)", 2011, "TV", 148, "HUNTER×HUNTER"));
    }

    private long animeId(String title) throws Exception {
        return count(ds, "SELECT id FROM anime WHERE title = '" + title + "'");
    }

    private String status(String title) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT m.status FROM anime a LEFT JOIN anime_metadata_match m ON m.anime_id = a.id"
                     + " WHERE a.title = '" + title.replace("'", "''") + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Fait tourner la tâche jusqu'à ce qu'il n'y ait plus rien à faire, ou jusqu'à une indisponibilité. */
    private MetadataService.Step runUntilIdle() throws Exception {
        for (int i = 0; i < 100; i++) {
            MetadataService.Step step = service.processNext();
            if (!(step instanceof MetadataService.Done)) {
                return step;
            }
        }
        throw new AssertionError("la tâche ne s'arrête pas");
    }

    @Test
    void matchesAreStoredWithProviderAndLanguage() throws Exception {
        assertInstanceOf(MetadataService.Idle.class, runUntilIdle());
        assertEquals("MATCHED", status("Sousou no Frieren"));
        assertEquals("MATCHED", status("Chûnibyô Demo Koi ga Shitai!"));   // accents
        assertEquals("UNMATCHED", status("Hunter x Hunter"));               // remake homonyme, rien pour départager
        assertEquals("UNMATCHED", status("Le Seigneur des Yôkai"));         // aucun résultat
        assertEquals(1, count(ds, "SELECT count(*) FROM anime_metadata_match WHERE reason = 'AMBIGUOUS'"
                + " AND jsonb_array_length(candidates) = 2 AND provider_id IS NULL"));

        String token = adminToken();
        given().auth().oauth2(token).get("/api/anime/" + animeId("Sousou no Frieren")).then().statusCode(200)
                .body("title", equalTo("Sousou no Frieren"))                  // le titre du dossier ne change jamais
                .body("alternativeTitle", equalTo("Frieren: Beyond Journey's End"))
                .body("year", equalTo(2023))
                .body("synopsis", equalTo("Synopsis of Sousou no Frieren.\n\n(Source: test)"))
                .body("synopsisLanguage", equalTo("en"))
                .body("metadataSource", equalTo("AniList"))
                .body("metadataUrl", equalTo("https://anilist.co/anime/154587"))
                .body("posterUrl", startsWith("https://s4.anilist.co/"))
                .body("posterLargeUrl", startsWith("https://s4.anilist.co/"));
        given().auth().oauth2(token).get("/api/anime/" + animeId("Hunter x Hunter")).then()
                .body("posterUrl", nullValue()).body("synopsis", nullValue()).body("metadataSource", nullValue());
        given().auth().oauth2(token).queryParam("q", "frieren").get("/api/anime").then()
                .body("items[0].posterUrl", startsWith("https://s4.anilist.co/")).body("items[0].year", equalTo(2023));
    }

    @Test
    void idempotentNothingIsAskedTwiceAndRescanKeepsTheData() throws Exception {
        runUntilIdle();
        int requests = fake.requests().size();
        // 4 animés ; « Le Seigneur des Yôkai » (aucun résultat) a droit à une recherche de repli « Youkai ».
        assertEquals(5, requests, fake.requests().toString());
        assertTrue(fake.requests().contains("search:Le Seigneur des Youkai"));
        assertInstanceOf(MetadataService.Idle.class, runUntilIdle());
        assertEquals("SUCCESS", scan().getString("status"));
        assertInstanceOf(MetadataService.Idle.class, runUntilIdle());
        assertEquals(requests, fake.requests().size(), "aucun nouvel appel");
        assertEquals("MATCHED", status("Sousou no Frieren"));
        assertEquals(1, count(ds, "SELECT count(*) FROM anime WHERE title = 'Sousou no Frieren' AND year = 2023"));

        // Un nouvel animé arrivé par le scan est traité, et lui seul.
        touch(ROOT, "Naruto/Naruto - S01E01.mkv");
        fake.onSearch("Naruto", FakeAniList.media(20, "NARUTO", "Naruto", 2002, "TV", 220));
        scan();
        runUntilIdle();
        assertEquals(List.of("search:Naruto"), fake.requests().subList(requests, fake.requests().size()));
        assertEquals("MATCHED", status("Naruto"));
    }

    @Test
    void resumesWhereItStoppedAfterAnOutage() throws Exception {
        // Premier animé traité, puis panne : la tâche s'arrête sans rien marquer pour les autres.
        // Premier animé (« Chûnibyô… ») : aucun résultat, ni pour sa recherche de repli « Chuunibyou… ».
        String empty = "{\"data\":{\"Page\":{\"media\":[]}}}";
        fake.force(new Forced(200, Map.of(), empty), new Forced(200, Map.of(), empty), new Forced(500, Map.of(), "{}"));
        assertInstanceOf(MetadataService.Done.class, service.processNext());
        MetadataService.Step step = service.processNext();
        assertInstanceOf(MetadataService.Unavailable.class, step);
        assertEquals(5, ((MetadataService.Unavailable) step).retryAfter().toMinutes());
        assertEquals(1, count(ds, "SELECT count(*) FROM anime_metadata_match"));
        assertTrue(service.pausedUntil().isPresent());

        // Reprise (comme après un redémarrage : tout l'état est en base) : seuls les animés restants sont demandés.
        int before = fake.requests().size();
        assertInstanceOf(MetadataService.Idle.class, runUntilIdle());
        assertEquals(4, count(ds, "SELECT count(*) FROM anime_metadata_match"));
        List<String> after = fake.requests().subList(before, fake.requests().size());
        assertEquals(4, after.size(), after.toString()); // 3 animés, dont un avec recherche de repli
        assertTrue(!after.contains(fake.requests().get(0)), "le premier animé n'est pas redemandé");
    }

    @Test
    void rateLimitAndOutageNeverBlockTheLibrary() throws Exception {
        fake.force(new Forced(429, Map.of("Retry-After", "1"), "{\"errors\":[{\"message\":\"Too Many Requests.\"}]}"));
        MetadataService.Step limited = service.processNext();
        assertInstanceOf(MetadataService.Unavailable.class, limited);
        assertEquals(1, ((MetadataService.Unavailable) limited).retryAfter().toSeconds());
        assertEquals(0, count(ds, "SELECT count(*) FROM anime_metadata_match"), "rien n'est marqué : on réessaiera");

        // Panne prolongée (AniList répond 403 pendant ses maintenances) : bibliothèque, scan et lecture fonctionnent.
        fake.always(new Forced(403, Map.of(), "{\"errors\":[{\"message\":\"API temporarily disabled\"}]}"));
        assertInstanceOf(MetadataService.Unavailable.class, service.processNext());
        assertEquals("SUCCESS", scan().getString("status"));
        String token = adminToken();
        given().auth().oauth2(token).get("/api/anime").then().statusCode(200).body("total", equalTo(4))
                .body("items[0].posterUrl", nullValue());
        given().auth().oauth2(token).get("/api/anime/" + animeId("Sousou no Frieren")).then().statusCode(200);
        fake.always(null);
        assertInstanceOf(MetadataService.Idle.class, runUntilIdle());
        assertEquals("MATCHED", status("Sousou no Frieren"));
    }

    @Test
    void lockedManualCorrectionIsNeverOverwritten() throws Exception {
        long frieren = animeId("Sousou no Frieren");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO anime_metadata_match (anime_id, status, provider, provider_id, locked, updated_by)"
                    + " VALUES (" + frieren + ", 'MANUAL', 'ANILIST', '182255', TRUE, 'admin')");
            st.execute("UPDATE anime SET year = 2026, metadata_provider_id = '182255', synopsis = 'manuel' WHERE id = " + frieren);
        }
        runUntilIdle();
        assertTrue(fake.requests().stream().noneMatch(r -> r.equals("search:Sousou no Frieren")), "jamais redemandé");
        // Même un traitement forcé de cet animé ne touche à rien.
        service.process(frieren);
        assertEquals("MANUAL", status("Sousou no Frieren"));
        assertEquals(1, count(ds, "SELECT count(*) FROM anime WHERE id = " + frieren
                + " AND year = 2026 AND metadata_provider_id = '182255' AND synopsis = 'manuel'"));
        // Ni par un rescan.
        scan();
        runUntilIdle();
        assertEquals(1, count(ds, "SELECT count(*) FROM anime WHERE id = " + frieren + " AND synopsis = 'manuel'"));
    }

    // --- Administration des appariements (§15.4) --------------------------------------------------------

    private io.restassured.specification.RequestSpecification admin() {
        return given().auth().oauth2(adminToken()).contentType(io.restassured.http.ContentType.JSON);
    }

    private io.restassured.response.ValidatableResponse put(String title, Object providerId, Boolean replace) throws Exception {
        var req = admin().body(providerId == null ? "{\"providerId\":null}" : "{\"providerId\":\"" + providerId + "\"}");
        if (replace != null) {
            req.queryParam("replace", replace);
        }
        return req.put("/api/admin/anime/" + animeId(title) + "/metadata").then();
    }

    @Test
    void adminListsUnmatchedAndDoubtfulWithCandidates() throws Exception {
        runUntilIdle();
        admin().get("/api/admin/metadata/summary").then().statusCode(200)
                .body("counts.MATCHED", equalTo(2)).body("counts.UNMATCHED", equalTo(2)).body("counts.PENDING", equalTo(0))
                .body("total", equalTo(4)).body("provider", equalTo("AniList"));
        admin().queryParam("status", "UNMATCHED").get("/api/admin/metadata").then().statusCode(200)
                .body("total", equalTo(2))
                .body("items.title", org.hamcrest.Matchers.contains("Hunter x Hunter", "Le Seigneur des Yôkai"))
                .body("items[0].reason", equalTo("AMBIGUOUS"))
                .body("items[0].candidates.providerId", org.hamcrest.Matchers.containsInAnyOrder("136", "11061"))
                .body("items[0].candidates[0].posterUrl", startsWith("https://"))
                .body("items[1].reason", equalTo("NO_RESULT"));
        admin().queryParam("status", "MATCHED").queryParam("q", "frieren").get("/api/admin/metadata").then()
                .body("items[0].providerId", equalTo("154587")).body("items[0].matchedTitle", equalTo("Frieren: Beyond Journey's End"))
                .body("items[0].score", equalTo(1.0f));
        admin().queryParam("status", "NOPE").get("/api/admin/metadata").then().statusCode(400);
        // Réservé aux admins.
        String name = fr.plexwish.animeserver.auth.AuthTestSupport.unique("meta");
        fr.plexwish.animeserver.auth.AuthTestSupport.createUser(name, "meta-password-1", "USER");
        given().auth().oauth2(fr.plexwish.animeserver.auth.AuthTestSupport.accessToken(name, "meta-password-1"))
                .get("/api/admin/metadata").then().statusCode(403);
    }

    @Test
    void manualMatchIsLockedAndSurvivesTheTaskAndRescans() throws Exception {
        runUntilIdle();
        admin().queryParam("providerId", "11061").get("/api/admin/anime/" + animeId("Hunter x Hunter") + "/metadata/preview")
                .then().statusCode(200).body("title", equalTo("Hunter x Hunter (2011)")).body("year", equalTo(2011))
                .body("episodes", equalTo(148));
        admin().queryParam("providerId", "999").get("/api/admin/anime/" + animeId("Hunter x Hunter") + "/metadata/preview")
                .then().statusCode(404).body("error", equalTo("PROVIDER_ENTRY_NOT_FOUND"));

        // Non apparié : pas de fiche à remplacer, pas de confirmation nécessaire.
        put("Hunter x Hunter", "11061", null).statusCode(200)
                .body("status", equalTo("MANUAL")).body("locked", equalTo(true)).body("providerId", equalTo("11061"))
                .body("matchedTitle", equalTo("Hunter x Hunter (2011)")).body("updatedBy", equalTo("admin"));
        given().auth().oauth2(adminToken()).get("/api/anime/" + animeId("Hunter x Hunter")).then()
                .body("year", equalTo(2011)).body("posterUrl", startsWith("https://"));

        // Ni la tâche (même relancée sur tous les non appariés), ni un rescan n'y touchent.
        admin().queryParam("status", "UNMATCHED").post("/api/admin/metadata/requeue").then().statusCode(200)
                .body("requeued", equalTo(1)); // Le Seigneur des Yôkai seulement
        runUntilIdle();
        scan();
        runUntilIdle();
        assertEquals("MANUAL", status("Hunter x Hunter"));
        assertEquals(1, count(ds, "SELECT count(*) FROM anime WHERE title = 'Hunter x Hunter' AND metadata_provider_id = '11061'"));
    }

    private void exec(String sql) throws Exception {
        try (java.sql.Connection c = ds.getConnection(); java.sql.Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    @Test
    void manualCorrectionRedoesAnAutomaticTmdbMatchButKeepsAManualOne() throws Exception {
        runUntilIdle();
        fake.onId(FakeAniList.media(182255, "Sousou no Frieren 2nd Season", "Frieren: Beyond Journey's End Season 2", 2026, "TV", 10));
        long frieren = animeId("Sousou no Frieren");
        // Fiche TMDB trouvée automatiquement avec les titres de l'ancienne fiche AniList.
        exec("INSERT INTO anime_tmdb (anime_id, status, tmdb_type, tmdb_id, language, poster_path, fetched_at, locked, updated_by)"
                + " VALUES (" + frieren + ", 'MATCHED', 'tv', 1, 'fr', '/ancienne.jpg', now(), FALSE, 'auto')");
        put("Sousou no Frieren", "182255", true).statusCode(200);
        assertEquals(0, count(ds, "SELECT count(*) FROM anime_tmdb WHERE anime_id = " + frieren),
                "appariement TMDB automatique refait avec la nouvelle fiche");
        // Même fiche AniList redonnée : rien ne change côté TMDB.
        exec("INSERT INTO anime_tmdb (anime_id, status, tmdb_type, tmdb_id, language, poster_path, fetched_at, locked, updated_by)"
                + " VALUES (" + frieren + ", 'MATCHED', 'tv', 2, 'fr', '/nouvelle.jpg', now(), FALSE, 'auto')");
        put("Sousou no Frieren", "182255", null).statusCode(200);
        assertEquals(1, count(ds, "SELECT count(*) FROM anime_tmdb WHERE anime_id = " + frieren + " AND tmdb_id = 2"));
        // Fiche TMDB choisie à la main (verrouillée) : gardée même si la fiche AniList change.
        exec("UPDATE anime_tmdb SET status = 'MANUAL', locked = TRUE WHERE anime_id = " + frieren);
        put("Sousou no Frieren", "154587", true).statusCode(200);
        assertEquals(1, count(ds, "SELECT count(*) FROM anime_tmdb WHERE anime_id = " + frieren + " AND locked AND tmdb_id = 2"));
    }

    @Test
    void replacingAnExistingSheetNeedsConfirmationAndChangesNothingBefore() throws Exception {
        runUntilIdle();
        fake.onId(FakeAniList.media(182255, "Sousou no Frieren 2nd Season", "Frieren: Beyond Journey's End Season 2", 2026, "TV", 10));
        put("Sousou no Frieren", "182255", null).statusCode(409)
                .body("error", equalTo("METADATA_CONFLICT"))
                .body("anime.title", equalTo("Sousou no Frieren"))
                .body("current.providerId", equalTo("154587")).body("current.title", equalTo("Frieren: Beyond Journey's End"))
                .body("current.year", equalTo(2023))
                .body("proposed.providerId", equalTo("182255")).body("proposed.year", equalTo(2026))
                .body("otherAnime", org.hamcrest.Matchers.empty());
        assertEquals("MATCHED", status("Sousou no Frieren"));
        assertEquals(1, count(ds, "SELECT count(*) FROM anime WHERE title = 'Sousou no Frieren' AND year = 2023"));

        put("Sousou no Frieren", "182255", true).statusCode(200).body("status", equalTo("MANUAL"));
        assertEquals(1, count(ds, "SELECT count(*) FROM anime WHERE title = 'Sousou no Frieren' AND year = 2026"));
        // Même fiche une seconde fois : rien à confirmer.
        put("Sousou no Frieren", "182255", null).statusCode(200);

        // Fiche déjà utilisée par un autre animé : confirmation aussi.
        put("Chûnibyô Demo Koi ga Shitai!", "182255", null).statusCode(409)
                .body("otherAnime.title", org.hamcrest.Matchers.contains("Sousou no Frieren"))
                .body("current.providerId", equalTo("14741"));

        // « Aucune fiche » : efface et verrouille, après confirmation.
        put("Sousou no Frieren", null, null).statusCode(409).body("proposed", nullValue());
        put("Sousou no Frieren", null, true).statusCode(200).body("status", equalTo("MANUAL")).body("providerId", nullValue());
        assertEquals(1, count(ds, "SELECT count(*) FROM anime WHERE title = 'Sousou no Frieren' AND year IS NULL"
                + " AND poster_url IS NULL AND synopsis IS NULL AND metadata_provider IS NULL"));

        // Déverrouiller : la tâche refait l'appariement automatique.
        admin().delete("/api/admin/anime/" + animeId("Sousou no Frieren") + "/metadata").then().statusCode(204);
        admin().delete("/api/admin/anime/" + animeId("Sousou no Frieren") + "/metadata").then().statusCode(404);
        runUntilIdle();
        assertEquals("MATCHED", status("Sousou no Frieren"));
        assertEquals(1, count(ds, "SELECT count(*) FROM anime WHERE title = 'Sousou no Frieren' AND year = 2023"));
    }

    @Test
    void manualCorrectionWhileAniListIsDownChangesNothing() throws Exception {
        runUntilIdle();
        fake.always(new Forced(503, Map.of(), "{}"));
        put("Hunter x Hunter", "11061", null).statusCode(503).body("error", equalTo("METADATA_PROVIDER_UNAVAILABLE"));
        admin().queryParam("providerId", "11061").get("/api/admin/anime/" + animeId("Hunter x Hunter") + "/metadata/preview")
                .then().statusCode(503);
        fake.always(null);
        assertEquals("UNMATCHED", status("Hunter x Hunter"));
        put("Hunter x Hunter", "abc", null).statusCode(400);
        admin().body("{\"providerId\":\"1\"}").put("/api/admin/anime/999999/metadata").then().statusCode(404);
    }

    @Test
    void genresAreStoredWithTheSheetAndBackfilledInBatches() throws Exception {
        runUntilIdle();
        long frieren = animeId("Sousou no Frieren");
        String token = adminToken();
        given().auth().oauth2(token).get("/api/anime/" + frieren).then()
                .body("genres.genre", org.hamcrest.Matchers.contains("Adventure", "Fantasy"))
                .body("genres.label", org.hamcrest.Matchers.contains("Aventure", "Fantasy"));
        given().auth().oauth2(token).get("/api/anime/" + animeId("Hunter x Hunter")).then()
                .body("genres", org.hamcrest.Matchers.empty());
        assertInstanceOf(MetadataService.Idle.class, service.backfillGenres(), "rien à rattraper après un appariement");

        // Fiches appariées avant l'ajout des genres : rattrapées par un seul appel groupé.
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM anime_genre");
            st.execute("UPDATE anime SET genres_fetched_at = NULL");
        }
        fake.onId(patched());
        int before = fake.requests().size();
        assertInstanceOf(MetadataService.Done.class, service.backfillGenres());
        List<String> calls = fake.requests().subList(before, fake.requests().size());
        assertEquals(1, calls.size(), calls.toString());
        assertTrue(calls.get(0).startsWith("ids:"), calls.toString());
        given().auth().oauth2(token).get("/api/anime/" + frieren).then()
                .body("genres.label", org.hamcrest.Matchers.contains("Drame", "Mystère", "Tranche de vie"));
        assertInstanceOf(MetadataService.Idle.class, service.backfillGenres());
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode patched() {
        com.fasterxml.jackson.databind.node.ObjectNode m =
                FakeAniList.media(154587, "Sousou no Frieren", "Frieren: Beyond Journey's End", 2023, "TV", 28);
        m.putArray("genres").add("Drama").add("Slice of Life").add("Mystery");
        return m;
    }
}
