package fr.plexwish.animeserver.tmdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.plexwish.animeserver.tmdb.FakeTmdb.Forced;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synopsis français TMDB contre un faux TMDB (aucun appel réseau réel) : appariement (titres AniList, saisons,
 * genre Animation), repli sur l'anglais, API indisponible, correction manuelle jamais écrasée, rafraîchissement
 * à 5 mois et effacement à 6 mois (conditions de l'API TMDB), purge, jeton jamais journalisé.
 */
@QuarkusTest
@QuarkusTestResource(value = FakeTmdbResource.class, restrictToAnnotatedClass = true)
class TmdbTest {

    static final Path ROOT = Path.of("target/test-library");
    static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    AgroalDataSource ds;
    @Inject
    TmdbService service;
    @Inject
    TmdbWorker worker;

    FakeTmdb fake;

    @BeforeEach
    void library() throws Exception {
        fake = FakeTmdbResource.server;
        fake.reset();
        truncateLibrary(ds);
        deleteTree(ROOT);
        for (String folder : List.of("Frieren", "Frieren S2", "Obscure", "Kimi no Na wa")) {
            touch(ROOT, folder + "/" + folder + " - 01.mkv");
        }
        assertEquals("SUCCESS", scan().getString("status"));
        anilist("Frieren", "Sousou no Frieren", "Frieren: Beyond Journey's End", "葬送のフリーレン", "TV", 2023);
        anilist("Frieren S2", "Sousou no Frieren 2nd Season", "Frieren Season 2", "葬送のフリーレン 第2期", "TV", 2026);
        anilist("Obscure", "Obscure Anime", null, "無名", "TV", 2010);
        anilist("Kimi no Na wa", "Kimi no Na wa.", "Your Name.", "君の名は。", "MOVIE", 2016);

        fake.onSearch("tv", "葬送のフリーレン",
                FakeTmdb.tv(209867, "Frieren", "葬送のフリーレン", 2023, true, "Synopsis en français de Frieren."),
                // Adaptation en prises de vue réelles, même titre : écartée grâce au genre Animation.
                FakeTmdb.tv(990001, "Frieren", "葬送のフリーレン", 2023, false, "Série live."));
        fake.onSearch("tv", "Frieren",
                FakeTmdb.tv(209867, "Frieren", "葬送のフリーレン", 2023, true, "Synopsis en français de Frieren."));
        fake.onSearch("movie", "君の名は。", FakeTmdb.movie(372058, "Your Name.", "君の名は。", 2016, ""));
    }

    // --- Utilitaires --------------------------------------------------------------------------------

    private long animeId(String title) throws Exception {
        return count(ds, "SELECT id FROM anime WHERE title = '" + title.replace("'", "''") + "'");
    }

    /** Fiche AniList déjà appariée (phase 6) : titres, format, année, synopsis anglais. */
    private void anilist(String folder, String romaji, String english, String nativeTitle, String format, int year) throws Exception {
        long id = animeId(folder);
        Map<String, Object> candidate = new java.util.LinkedHashMap<>();
        candidate.put("providerId", "A" + id);
        candidate.put("title", english != null ? english : romaji);
        candidate.put("romaji", romaji);
        candidate.put("english", english);
        candidate.put("nativeTitle", nativeTitle);
        candidate.put("format", format);
        candidate.put("year", year);
        try (Connection c = ds.getConnection();
             PreparedStatement st = c.prepareStatement("INSERT INTO anime_metadata_match (anime_id, status, provider, provider_id,"
                     + " candidates, updated_by) VALUES (?, 'MATCHED', 'ANILIST', ?, ?::jsonb, 'auto')");
             PreparedStatement up = c.prepareStatement("UPDATE anime SET year = ?, synopsis = ?, synopsis_language = 'en',"
                     + " metadata_provider = 'ANILIST', metadata_fetched_at = now() WHERE id = ?")) {
            st.setLong(1, id);
            st.setString(2, "A" + id);
            st.setString(3, JSON.writeValueAsString(List.of(candidate)));
            st.executeUpdate();
            up.setInt(1, year);
            up.setString(2, "English synopsis of " + romaji + ".");
            up.setLong(3, id);
            up.executeUpdate();
        }
    }

    private void processAll() throws Exception {
        for (int i = 0; i < 50; i++) {
            if (service.processNext() instanceof TmdbService.Idle) {
                return;
            }
        }
        throw new AssertionError("la tâche TMDB ne s'arrête pas");
    }

    private String column(String folder, String column) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT t." + column + " FROM anime_tmdb t JOIN anime a ON a.id = t.anime_id"
                     + " WHERE a.title = '" + folder + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private void sql(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static RequestSpecification admin() {
        return given().auth().oauth2(adminToken());
    }

    // --- Appariement ----------------------------------------------------------------------------------

    @Test
    void matchesWithAniListTitlesAndShowsFrenchSynopsis() throws Exception {
        processAll();
        assertEquals("MATCHED", column("Frieren", "status"));
        assertEquals("209867", column("Frieren", "tmdb_id"));
        assertEquals("fr", column("Frieren", "language"));
        // Recherche par le titre japonais d'AniList, en français, sans contenu adulte, jeton dans l'en-tête.
        FakeTmdb.Request first = fake.requests().get(0);
        assertEquals("/search/tv", first.path());
        assertEquals("false", first.params().get("include_adult"));
        assertEquals("fr-FR", first.params().get("language"));
        assertEquals("Bearer " + FakeTmdbResource.TOKEN, first.authorization());
        assertTrue(fake.requests().stream().noneMatch(r -> r.params().containsKey("api_key")));

        given().auth().oauth2(adminToken()).get("/api/anime/" + animeId("Frieren")).then().statusCode(200)
                .body("synopsis", equalTo("Synopsis en français de Frieren."))
                .body("synopsisLanguage", equalTo("fr")).body("synopsisSource", equalTo("TMDB"))
                .body("frenchTitle", equalTo("Frieren"))
                .body("tmdbUrl", equalTo("https://www.themoviedb.org/tv/209867"));
    }

    @Test
    void sequelSeasonMatchesTheWholeSeries() throws Exception {
        processAll();
        // « Frieren Season 2 » (2026) → série TMDB « Frieren » commencée en 2023 : TMDB regroupe les saisons.
        assertEquals("MATCHED", column("Frieren S2", "status"));
        assertEquals("209867", column("Frieren S2", "tmdb_id"));
        assertTrue(fake.requests().stream().anyMatch(r -> "Frieren".equals(r.params().get("query"))));
    }

    @Test
    void movieWithoutFrenchOverviewFallsBackToEnglish() throws Exception {
        processAll();
        assertEquals("MATCHED", column("Kimi no Na wa", "status"));
        assertEquals("movie", column("Kimi no Na wa", "tmdb_type"));
        assertNull(column("Kimi no Na wa", "synopsis"));
        given().auth().oauth2(adminToken()).get("/api/anime/" + animeId("Kimi no Na wa")).then().statusCode(200)
                .body("synopsis", equalTo("English synopsis of Kimi no Na wa..")).body("synopsisLanguage", equalTo("en"))
                .body("synopsisSource", equalTo("AniList")).body("frenchTitle", equalTo("Your Name."));
    }

    @Test
    void unmatchedKeepsEnglishAndIsListedForTheAdmin() throws Exception {
        processAll();
        assertEquals("UNMATCHED", column("Obscure", "status"));
        assertEquals("NO_RESULT", column("Obscure", "reason"));
        given().auth().oauth2(adminToken()).get("/api/anime/" + animeId("Obscure")).then()
                .body("synopsis", equalTo("English synopsis of Obscure Anime.")).body("synopsisSource", equalTo("AniList"));
        admin().queryParam("noFrench", true).get("/api/admin/tmdb").then().statusCode(200)
                .body("items.title", contains("Kimi no Na wa", "Obscure"));
        admin().get("/api/admin/tmdb/summary").then().statusCode(200)
                .body("configured", equalTo(true)).body("total", equalTo(4)).body("withFrenchSynopsis", equalTo(2))
                .body("counts.MATCHED", equalTo(3)).body("counts.UNMATCHED", equalTo(1));
        // Idempotent : plus rien à faire, aucune nouvelle requête.
        int before = fake.requests().size();
        processAll();
        assertEquals(before, fake.requests().size());
    }

    // --- Panne ----------------------------------------------------------------------------------------

    @Test
    void unavailableApiPausesWithoutLosingWorkAndLibraryStillWorks() throws Exception {
        fake.always(new Forced(500, Map.of(), "{}"));
        assertInstanceOf(TmdbService.Unavailable.class, service.processNext());
        assertEquals(0, count(ds, "SELECT count(*) FROM anime_tmdb"));
        given().auth().oauth2(adminToken()).get("/api/anime/" + animeId("Frieren")).then().statusCode(200)
                .body("synopsis", equalTo("English synopsis of Sousou no Frieren."));

        fake.always(null);
        fake.force(new Forced(429, Map.of("Retry-After", "2"), "{}"));
        TmdbService.Step s = service.processNext();
        assertEquals(Duration.ofSeconds(2), assertInstanceOf(TmdbService.Unavailable.class, s).retryAfter());

        fake.force(new Forced(401, Map.of(), "{\"status_code\":7}"));
        assertEquals(Duration.ofHours(1), ((TmdbService.Unavailable) service.processNext()).retryAfter());
        admin().get("/api/admin/tmdb/summary").then().body("lastUnavailable", equalTo("TMDB : clé refusée (401), vérifier TMDB_READ_TOKEN"));

        // Reprise : le travail continue là où il s'était arrêté.
        Thread.sleep(10);
        processAll();
        assertEquals("MATCHED", column("Frieren", "status"));
        admin().get("/api/admin/tmdb/summary").then().body("lastUnavailable", nullValue());
    }

    // --- Correction manuelle --------------------------------------------------------------------------

    @Test
    void manualMatchIsLockedAndNeverOverwritten() throws Exception {
        long obscure = animeId("Obscure");
        fake.sheet("tv", FakeTmdb.tv(555, "Obscur", "無名", 2010, true, "Synopsis manuel en français."));
        admin().queryParam("type", "tv").queryParam("tmdbId", 555).get("/api/admin/anime/" + obscure + "/tmdb/preview")
                .then().statusCode(200).body("name", equalTo("Obscur")).body("overview", equalTo("Synopsis manuel en français."));
        admin().contentType(ContentType.JSON).body(Map.of("type", "tv", "tmdbId", 555))
                .put("/api/admin/anime/" + obscure + "/tmdb").then().statusCode(200)
                .body("status", equalTo("MANUAL")).body("locked", equalTo(true)).body("hasFrenchSynopsis", equalTo(true));

        // La tâche automatique ne le reprend pas, et un appariement forcé n'écrase pas la correction.
        fake.onSearch("tv", "無名", FakeTmdb.tv(777, "Autre", "無名", 2010, true, "Autre chose."));
        processAll();
        service.match(obscure);
        assertEquals("MANUAL", column("Obscure", "status"));
        assertEquals("555", column("Obscure", "tmdb_id"));
        given().auth().oauth2(adminToken()).get("/api/anime/" + obscure).then()
                .body("synopsis", equalTo("Synopsis manuel en français.")).body("synopsisSource", equalTo("TMDB"));

        // Remplacer par une autre fiche : 409, puis confirmation.
        fake.sheet("tv", FakeTmdb.tv(556, "Obscur 2", "無名", 2010, true, "Deuxième."));
        admin().contentType(ContentType.JSON).body(Map.of("type", "tv", "tmdbId", 556))
                .put("/api/admin/anime/" + obscure + "/tmdb").then().statusCode(409)
                .body("error", equalTo("TMDB_CONFLICT")).body("current.tmdbId", equalTo(555)).body("proposed.tmdbId", equalTo(556));
        assertEquals("555", column("Obscure", "tmdb_id"));
        admin().contentType(ContentType.JSON).body(Map.of("type", "tv", "tmdbId", 556)).queryParam("replace", true)
                .put("/api/admin/anime/" + obscure + "/tmdb").then().statusCode(200).body("tmdbId", equalTo(556));

        // « Aucune fiche TMDB » : verrouillé, synopsis anglais.
        admin().contentType(ContentType.JSON).body(Map.of()).queryParam("replace", true)
                .put("/api/admin/anime/" + obscure + "/tmdb").then().statusCode(200)
                .body("status", equalTo("MANUAL")).body("tmdbId", nullValue());
        given().auth().oauth2(adminToken()).get("/api/anime/" + obscure).then().body("synopsisSource", equalTo("AniList"));

        // Déverrouiller : la tâche automatique reprend la main.
        admin().delete("/api/admin/anime/" + obscure + "/tmdb").then().statusCode(204);
        processAll();
        assertEquals("777", column("Obscure", "tmdb_id"));
        admin().delete("/api/admin/anime/" + obscure + "/tmdb").then().statusCode(404).body("error", equalTo("NOT_LOCKED"));
    }

    @Test
    void manualErrorsAndPermissions() throws Exception {
        long obscure = animeId("Obscure");
        admin().queryParam("type", "tv").queryParam("tmdbId", 4242).get("/api/admin/anime/" + obscure + "/tmdb/preview")
                .then().statusCode(404).body("error", equalTo("PROVIDER_ENTRY_NOT_FOUND"));
        admin().queryParam("type", "book").queryParam("tmdbId", 1).get("/api/admin/anime/" + obscure + "/tmdb/preview")
                .then().statusCode(400);
        admin().contentType(ContentType.JSON).body(Map.of("tmdbId", 555)).put("/api/admin/anime/" + obscure + "/tmdb")
                .then().statusCode(400);
        admin().contentType(ContentType.JSON).body(Map.of("type", "tv", "tmdbId", 1)).put("/api/admin/anime/999999/tmdb")
                .then().statusCode(404).body("error", equalTo("ANIME_NOT_FOUND"));
        fake.always(new Forced(503, Map.of(), "{}"));
        admin().queryParam("type", "tv").queryParam("tmdbId", 1).get("/api/admin/anime/" + obscure + "/tmdb/preview")
                .then().statusCode(503).body("error", equalTo("METADATA_PROVIDER_UNAVAILABLE"));
        fake.always(null);

        String name = unique("tmdbuser");
        createUser(name, "tmdb-user-password", "USER");
        String user = accessToken(name, "tmdb-user-password");
        given().auth().oauth2(user).get("/api/admin/tmdb/summary").then().statusCode(403);
        given().auth().oauth2(user).post("/api/admin/tmdb/purge?confirm=true").then().statusCode(403);
        given().get("/api/admin/tmdb").then().statusCode(401);
    }

    // --- Conditions de l'API TMDB : 5 mois / 6 mois ---------------------------------------------------

    @Test
    void staleSheetsAreRefreshedEvenWhenLocked() throws Exception {
        processAll();
        long frieren = animeId("Frieren");
        sql("UPDATE anime_tmdb SET fetched_at = now() - interval '160 days', locked = TRUE, status = 'MANUAL'"
                + " WHERE anime_id = " + frieren);
        fake.sheet("tv", FakeTmdb.tv(209867, "Frieren", "葬送のフリーレン", 2023, true, "Synopsis mis à jour."));
        admin().get("/api/admin/tmdb/summary").then().body("refreshDue", equalTo(1));

        int searches = (int) fake.requests().stream().filter(r -> r.path().startsWith("/search")).count();
        processAll();
        assertEquals("Synopsis mis à jour.", column("Frieren", "synopsis"));
        assertEquals("MANUAL", column("Frieren", "status"));
        assertTrue(count(ds, "SELECT count(*) FROM anime_tmdb WHERE anime_id = " + frieren
                + " AND fetched_at > now() - interval '1 minute'") == 1);
        // Rafraîchir n'est pas réapparier : aucune nouvelle recherche.
        assertEquals(searches, fake.requests().stream().filter(r -> r.path().startsWith("/search")).count());

        // Fiche supprimée chez TMDB : rien n'est conservé, nouvel essai dans un mois.
        sql("UPDATE anime_tmdb SET fetched_at = now() - interval '160 days' WHERE anime_id = " + frieren);
        fake.removeSheet("tv", 209867);
        processAll();
        assertNull(column("Frieren", "synopsis"));
        assertNull(column("Frieren", "fetched_at"));
        assertEquals(1, count(ds, "SELECT count(*) FROM anime_tmdb WHERE anime_id = " + frieren
                + " AND next_attempt_at > now() + interval '29 days'"));
    }

    @Test
    void nothingIsShownOrKeptBeyondSixMonths() throws Exception {
        processAll();
        long frieren = animeId("Frieren");
        sql("UPDATE anime_tmdb SET updated_at = now() - interval '181 days',"
                + " fetched_at = CASE WHEN fetched_at IS NULL THEN NULL ELSE now() - interval '181 days' END");
        // Avant même l'effacement, une fiche de plus de 6 mois n'est plus affichée.
        given().auth().oauth2(adminToken()).get("/api/anime/" + frieren).then()
                .body("synopsis", equalTo("English synopsis of Sousou no Frieren.")).body("synopsisSource", equalTo("AniList"));
        assertEquals(3, service.purgeExpired());
        assertEquals(0, count(ds, "SELECT count(*) FROM anime_tmdb WHERE synopsis IS NOT NULL OR title IS NOT NULL"
                + " OR poster_path IS NOT NULL OR candidates IS NOT NULL OR fetched_at IS NOT NULL"));
        // Les identifiants restent : la fiche est redemandée (rafraîchissement), pas réappariée.
        processAll();
        assertEquals("Synopsis en français de Frieren.", column("Frieren", "synopsis"));
    }

    @Test
    void purgeDeletesEverythingAfterConfirmation() throws Exception {
        processAll();
        admin().post("/api/admin/tmdb/purge").then().statusCode(400).body("error", equalTo("CONFIRMATION_REQUIRED"));
        admin().post("/api/admin/tmdb/purge?confirm=true").then().statusCode(200).body("purged", equalTo(4));
        assertEquals(0, count(ds, "SELECT count(*) FROM anime_tmdb"));
    }

    @Test
    void requeueRetriesUnmatched() throws Exception {
        processAll();
        fake.onSearch("tv", "無名", FakeTmdb.tv(888, "Obscure Anime", "無名", 2010, true, "Trouvé."));
        admin().post("/api/admin/tmdb/requeue?status=UNMATCHED").then().statusCode(200).body("requeued", equalTo(1));
        processAll();
        assertEquals("MATCHED", column("Obscure", "status"));
        admin().post("/api/admin/tmdb/requeue?status=PENDING").then().statusCode(400);
    }

    // --- Jeton ----------------------------------------------------------------------------------------

    @Test
    void tokenNeverLogged() throws Exception {
        StringBuilder text = new StringBuilder();
        Handler capture = new Handler() {
            @Override
            public synchronized void publish(LogRecord r) {
                text.append(r.getMessage()).append(' ').append(java.util.Arrays.toString(r.getParameters())).append('\n');
                if (r.getThrown() != null) {
                    text.append(r.getThrown()).append('\n');
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(capture);
        try {
            fake.force(new Forced(401, Map.of(), "{}"));
            service.processNext();
            fake.force(new Forced(429, Map.of("Retry-After", "1"), "{}"));
            service.processNext();
            fake.force(new Forced(200, Map.of(), "not json"));
            service.processNext();
            processAll();
            admin().queryParam("type", "tv").queryParam("tmdbId", 1).get("/api/admin/anime/" + animeId("Obscure") + "/tmdb/preview");
        } finally {
            root.removeHandler(capture);
        }
        assertTrue(text.toString().contains("TMDB"), "des logs TMDB ont bien été capturés");
        assertFalse(text.toString().contains(FakeTmdbResource.TOKEN));
        assertFalse(admin().get("/api/admin/tmdb/summary").asString().contains(FakeTmdbResource.TOKEN));
        assertFalse(worker.running(), "tâche de fond désactivée en test");
    }
}
