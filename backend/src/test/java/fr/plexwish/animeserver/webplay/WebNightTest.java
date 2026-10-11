package fr.plexwish.animeserver.webplay;

import fr.plexwish.animeserver.setup.AppSettings;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.accessToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Planificateur de nuit du lecteur web (10.3.3, faux ffprobe / ffmpeg) : préparation préventive des épisodes suivants
 * et des ajouts récents, seulement la nuit ; conversion vidéo préventive seulement si le réglage le permet ; une
 * demande d'utilisateur passe devant ; nettoyage du cache (sources changées, puis 85 % du plafond).
 */
@QuarkusTest
class WebNightTest {

    static final Path ROOT = Path.of("target/test-library");
    static final Path CACHE = Path.of("target/test-web-cache");

    @Inject
    AgroalDataSource ds;
    @Inject
    WebPrepService prep;
    @Inject
    WebNightService night;
    @Inject
    WebSettings webSettings;
    @Inject
    AppSettings settings;

    String user;

    @BeforeEach
    void setUp() throws Exception {
        WebPlaybackTest.installFakeTools();
        truncateLibrary(ds);
        sql("DELETE FROM web_job");
        sql("DELETE FROM app_setting WHERE key LIKE 'web.%'");
        settings.forget();
        deleteTree(ROOT);
        deleteTree(CACHE);
        prep.maxBytesForTests(null);
        prep.checkTools();
        webSettings.nightForTests(true);
        String name = unique("night");
        createUser(name, "night-user-password", "USER");
        user = accessToken(name, "night-user-password");
    }

    @AfterEach
    void tearDown() {
        webSettings.nightForTests(null);
        prep.maxBytesForTests(null);
    }

    private void library(String... files) throws Exception {
        for (String f : files) {
            Path p = ROOT.resolve(f);
            Files.createDirectories(p.getParent());
            Files.write(p, new byte[1000]);
        }
        assertEquals("SUCCESS", scan(true).getString("status"));
    }

    private void sql(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private long episode(String fileName) throws Exception {
        return count(ds, "SELECT e.id FROM episode e JOIN media_file f ON f.id = e.media_file_id WHERE f.file_name = '" + fileName + "'");
    }

    private long jobs(String kind, String fileName, String where) throws Exception {
        return count(ds, "SELECT count(*) FROM web_job j JOIN media_file f ON f.id = j.media_file_id WHERE j.kind = '" + kind
                + "' AND f.file_name = '" + fileName + "'" + (where == null ? "" : " AND " + where));
    }

    @Test
    void theNightPreparesNextEpisodesAndRecentAdditionsOnlyAtNight() throws Exception {
        library("Days/Days - S01E01.mp4", "Days/Days - S01E02.mp4", "Days/Days - S01E03.mp4", "Days/Days - S01E04.mp4",
                "Air Gear/Air Gear - S01E01.avi");
        // Ajouts récents : on ne garde que l'animé en cours pour vérifier « les 2 suivants ».
        sql("UPDATE media_file SET first_seen_at = now() - interval '30 days'");
        given().auth().oauth2(user).contentType("application/json").body(Map.of("positionSeconds", 1400, "durationSeconds", 1440))
                .put("/api/episodes/" + episode("Days - S01E01.mp4") + "/progress").then().statusCode(200);

        WebNightService.NightReport r = night.runNight();
        assertEquals(2, r.queued(), "les 2 épisodes suivants");
        assertEquals(1, jobs("BASE", "Days - S01E02.mp4", "priority = 2"));
        assertEquals(1, jobs("BASE", "Days - S01E03.mp4", "priority = 2"));
        assertEquals(0, jobs("BASE", "Days - S01E04.mp4", null));

        // Le jour, le travail de nuit attend (et ne bloque pas l'analyse de fond).
        webSettings.nightForTests(false);
        assertFalse(prep.busy());
        assertFalse(prep.processNext());
        webSettings.nightForTests(true);
        assertTrue(prep.processNext());
        assertTrue(prep.processNext());
        assertFalse(prep.processNext());

        // Ajout récent à convertir (Xvid) : préparation de base, pas de conversion vidéo préventive par défaut…
        sql("UPDATE media_file SET first_seen_at = now() WHERE file_name = 'Air Gear - S01E01.avi'");
        assertEquals(1, night.runNight().queued());
        assertTrue(prep.processNext());
        assertEquals(0, jobs("CONV", "Air Gear - S01E01.avi", null));
        // … seulement si l'admin l'a permis.
        webSettings.save(720, true, true);
        assertEquals(1, night.runNight().queued());
        assertEquals(1, jobs("CONV", "Air Gear - S01E01.avi", "priority = 2 AND status = 'QUEUED'"));

        // Quelqu'un ouvre l'épisode : sa conversion passe devant (priorité 0).
        given().auth().oauth2(user).queryParam("caps", "h264,aac").get("/api/episodes/" + episode("Air Gear - S01E01.avi") + "/web-playback")
                .then().statusCode(202).body("preparing.conversion", equalTo(true));
        assertEquals(1, jobs("CONV", "Air Gear - S01E01.avi", "priority = 0"));
    }

    @Test
    void copiesWithoutConversionExpireAfter48HoursWithoutReading() throws Exception {
        library("Days/Days - S01E01.mkv", "Days/Days - S01E02.mkv", "Days/Days - S01E03.mkv", "Days/Days - S01E04.mp4");
        webSettings.save(720, false, false);
        for (String f : new String[]{"Days - S01E01.mkv", "Days - S01E02.mkv", "Days - S01E03.mkv", "Days - S01E04.mp4"}) {
            given().auth().oauth2(user).queryParam("caps", "h264,aac").get("/api/episodes/" + episode(f) + "/web-playback")
                    .then().statusCode(202);
        }
        // Préparations prêtes : trois copies HLS (E01 jamais relue depuis 3 jours, E02 lue il y a 1 h, E03 avec une
        // conversion prête qui s'en sert), un MP4 lu tel quel (sous-titres seulement).
        sql("UPDATE web_job SET status = 'READY', bytes = 10, finished_at = now() - interval '3 days',"
                + " manifest = '{\"hls\": true, \"wantsHls\": true, \"direct\": false, \"audio\": [], \"subtitles\": [], \"fonts\": []}'::jsonb");
        sql("UPDATE web_job SET manifest = jsonb_set(manifest, '{hls}', 'false') FROM media_file f WHERE f.id = web_job.media_file_id"
                + " AND f.file_name = 'Days - S01E04.mp4'");
        sql("UPDATE web_job SET last_read_at = now() - interval '1 hour' FROM media_file f WHERE f.id = web_job.media_file_id"
                + " AND f.file_name = 'Days - S01E02.mkv'");
        sql("INSERT INTO web_job (media_file_id, kind, status, cache_key, source_size, source_modified, bytes)"
                + " SELECT f.id, 'CONV', 'READY', repeat('e', 64), f.file_size, f.last_modified, 10 FROM media_file f"
                + " WHERE f.file_name = 'Days - S01E03.mkv'");
        assertEquals(1, night.expireCopies());
        assertEquals(0, jobs("BASE", "Days - S01E01.mkv", null), "copie non lue depuis plus de 48 h : effacée");
        assertEquals(1, jobs("BASE", "Days - S01E02.mkv", null), "lue récemment : gardée");
        assertEquals(1, jobs("BASE", "Days - S01E03.mkv", null), "sous-titres d'une conversion : gardée");
        assertEquals(1, jobs("BASE", "Days - S01E04.mp4", null), "pas de copie (lu tel quel) : gardée");
    }

    @Test
    void preventiveOffQueuesNothing() throws Exception {
        library("Days/Days - S01E01.mp4", "Days/Days - S01E02.mp4");
        webSettings.save(720, false, false);
        assertEquals(0, night.runNight().queued());
        assertEquals(0, count(ds, "SELECT count(*) FROM web_job"));
    }

    @Test
    void cleanupRemovesChangedSourcesThenTrimsTo85Percent() throws Exception {
        library("Days/Days - S01E01.mp4", "Days/Days - S01E02.mp4", "Days/Days - S01E03.mp4", "Days/Days - S01E04.mp4");
        webSettings.save(720, false, false);
        for (String f : new String[]{"Days - S01E01.mp4", "Days - S01E02.mp4", "Days - S01E03.mp4", "Days - S01E04.mp4"}) {
            given().auth().oauth2(user).queryParam("caps", "h264,aac").get("/api/episodes/" + episode(f) + "/web-playback")
                    .then().statusCode(202);
            assertTrue(prep.processNext());
        }
        sql("UPDATE web_job SET bytes = 30");
        sql("UPDATE web_job SET finished_at = now() - interval '3 days' FROM media_file f WHERE f.id = web_job.media_file_id"
                + " AND f.file_name = 'Days - S01E01.mp4'");
        sql("UPDATE web_job SET last_read_at = now() FROM media_file f WHERE f.id = web_job.media_file_id"
                + " AND f.file_name = 'Days - S01E04.mp4'");
        // Source remplacée (autre taille) : sa préparation ne vaut plus rien.
        sql("UPDATE media_file SET file_size = 2000 WHERE file_name = 'Days - S01E02.mp4'");
        prep.maxBytesForTests(80L); // 3 × 30 = 90 > 85 % de 80 (68)

        WebNightService.NightReport r = night.runNight();
        assertEquals(1, r.orphans());
        assertEquals(0, jobs("BASE", "Days - S01E02.mp4", null));
        assertEquals(1, r.evicted(), "la plus ancienne, jusqu'à 68 octets au plus");
        assertEquals(0, jobs("BASE", "Days - S01E01.mp4", null));
        assertEquals(1, jobs("BASE", "Days - S01E04.mp4", null), "lue récemment : gardée");
        assertEquals(60, prep.usedBytes());
    }
}
