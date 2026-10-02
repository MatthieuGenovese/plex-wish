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
        assertEquals(4, requests, fake.requests().toString());
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
        fake.force(new Forced(200, Map.of(), "{\"data\":{\"Page\":{\"media\":[]}}}"), new Forced(500, Map.of(), "{}"));
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
        assertEquals(3, after.size(), after.toString());
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
}
