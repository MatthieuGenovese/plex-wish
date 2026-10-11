package fr.plexwish.animeserver.library.subs;

import fr.plexwish.animeserver.webplay.WebCache;
import fr.plexwish.animeserver.webplay.WebPrepService;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.accessToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sous-titres externes au scan (1.3.0) : même nom que la vidéo, dossier séparé avec « Épisode N », forcés, sans
 * épisode, contrôle de durée, corrections de l'admin gardées par les rescans, disparition, clé de préparation web.
 */
@QuarkusTest
class ExternalSubtitleScanTest {

    static final Path ROOT = Path.of("target/test-library");

    @Inject
    AgroalDataSource ds;
    @Inject
    WebPrepService prep;

    static String ass(String end) {
        return """
                [Script Info]
                ScriptType: v4.00+

                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Bonjour
                Comment: 0,0:00:01.00,9:00:00.00,Default,,0,0,0,,ignoré
                Dialogue: 0,0:00:05.00,%s,Default,,0,0,0,,Fin, avec virgule
                """.formatted(end);
    }

    @BeforeEach
    void library() throws Exception {
        truncateLibrary(ds);
        deleteTree(ROOT);
        write("Days/Days - S01E01.mkv", "video");
        write("Days/Days - S01E02.mkv", "video");
        write("Days/Days - S01E01.fre.ass", ass("0:30:00.00"));
        write("Days/Subs/[Team] Days - Épisode 2.forced.ass", ass("0:20:00.50"));
        write("Days/Subs/[Team] Days - Épisode 2.ass", ass("0:20:00.50"));
        write("Days/Subs/[Team] Days - Episode 9.ass", ass("0:20:00.00"));
        write("Days/Subs/Polices/police.ttf", "font");
        write("Days/notes.ass", ass("0:01:00.00"));
        assertEquals("SUCCESS", scan(true).getString("status"));
    }

    private static void write(String path, String content) throws Exception {
        Path p = ROOT.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private void sql(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private long id(String fileName) throws Exception {
        return count(ds, "SELECT id FROM external_subtitle WHERE file_name = '" + fileName + "'");
    }

    private JsonPath list(String filter) {
        return given().auth().oauth2(adminToken()).queryParam("filter", filter).get("/api/admin/library/subtitles")
                .then().statusCode(200).extract().jsonPath();
    }

    @Test
    void scanLinksSubtitlesToEpisodes() throws Exception {
        JsonPath all = list("ALL");
        assertEquals(5, all.getInt("summary.total"));
        assertEquals(3, all.getInt("summary.linked"));
        assertEquals(2, all.getInt("summary.unmatched"));
        Map<String, Object> e01 = all.getMap("items.find { it.relativePath == 'Days/Days - S01E01.fre.ass' }");
        assertEquals("SAME_NAME", e01.get("match"));
        assertEquals("fre", e01.get("language"));
        assertEquals(1800.0, ((Number) e01.get("lastCueSeconds")).doubleValue(), 0.001, "les commentaires ne comptent pas");
        Map<String, Object> forced = all.getMap("items.find { it.relativePath == 'Days/Subs/[Team] Days - Épisode 2.forced.ass' }");
        assertEquals("NAME", forced.get("match"));
        assertEquals(true, forced.get("forced"));
        assertEquals(2, ((Map<?, ?>) forced.get("episode")).get("episodeNumber"));

        JsonPath report = given().auth().oauth2(adminToken()).get("/api/admin/library/scan-report").then().statusCode(200)
                .extract().jsonPath();
        assertEquals(5, report.getInt("stats.externalSubtitles"));
        assertEquals(2, report.getInt("stats.subtitlesUnmatched"));
        List<String> unmatched = given().auth().oauth2(adminToken()).queryParam("category", "SUBTITLE_UNMATCHED")
                .get("/api/admin/library/issues").then().statusCode(200).extract().jsonPath().getList("items.relativePath");
        assertEquals(List.of("Days/Subs/[Team] Days - Episode 9.ass", "Days/notes.ass"), unmatched.stream().sorted().toList());

        // Contrôle de durée : vidéo de 23 min (analyse ffprobe), dernier sous-titre à 30:00.
        sql("INSERT INTO media_probe (media_file_id, probed_size, status, duration_seconds) SELECT id, file_size, 'OK', 1400"
                + " FROM media_file WHERE file_name IN ('Days - S01E01.mkv', 'Days - S01E02.mkv')");
        assertEquals(1, scan(true).getInt("stats.subtitleDurationIssues"));
        List<String> details = given().auth().oauth2(adminToken()).queryParam("category", "SUBTITLE_DURATION")
                .get("/api/admin/library/issues").then().statusCode(200).extract().jsonPath().getList("items.detail");
        assertEquals(List.of("dernier sous-titre à 30:00, vidéo de 23:20 : mauvais épisode ?"), details);
        assertEquals(1, list("DURATION").getInt("total"));
    }

    @Test
    void adminCorrectionsSurviveRescans() throws Exception {
        long nine = id("[Team] Days - Episode 9.ass");
        String admin = adminToken();
        given().auth().oauth2(admin).contentType("application/json").body(Map.of("seasonNumber", 1, "episodeNumber", 7))
                .put("/api/admin/library/subtitles/" + nine + "/episode").then().statusCode(404);
        given().auth().oauth2(admin).contentType("application/json").body(Map.of("seasonNumber", 1, "episodeNumber", 2))
                .put("/api/admin/library/subtitles/" + nine + "/episode").then().statusCode(200)
                .body("match", equalTo("MANUAL")).body("manual", equalTo(true)).body("episode.episodeNumber", equalTo(2));
        long e01 = id("Days - S01E01.fre.ass");
        given().auth().oauth2(admin).delete("/api/admin/library/subtitles/" + e01 + "/episode").then().statusCode(200)
                .body("episode", nullValue()).body("manual", equalTo(true));

        JsonPath report = scan(true);
        assertEquals(1, report.getInt("stats.subtitlesUnmatched"), "le détaché n'est plus signalé");
        JsonPath all = list("ALL");
        assertEquals("MANUAL", all.getString("items.find { it.id == " + nine + " }.match"));
        assertNull(all.get("items.find { it.id == " + e01 + " }.episode"));
        assertEquals(2, list("MANUAL").getInt("total"));

        // Retour à l'automatique : recalculé tout de suite.
        given().auth().oauth2(admin).delete("/api/admin/library/subtitles/" + e01 + "/override").then().statusCode(200)
                .body("match", equalTo("SAME_NAME")).body("manual", equalTo(false));
        // Un utilisateur ordinaire n'y a pas accès.
        String name = unique("subs");
        createUser(name, "subs-user-password", "USER");
        given().auth().oauth2(accessToken(name, "subs-user-password")).get("/api/admin/library/subtitles").then().statusCode(403);
    }

    @Test
    void disappearedSubtitleIsNoLongerOfferedAndWebKeyFollowsSubtitles() throws Exception {
        long fileId = count(ds, "SELECT id FROM media_file WHERE file_name = 'Days - S01E01.mkv'");
        long size = count(ds, "SELECT file_size FROM media_file WHERE id = " + fileId);
        OffsetDateTime modified;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT last_modified FROM media_file WHERE id = " + fileId)) {
            rs.next();
            modified = rs.getObject(1, OffsetDateTime.class);
        }
        String withSub = prep.baseKey(fileId, size, modified);
        assertNotEquals(WebCache.key(WebPrepService.BASE, fileId, size, modified), withSub, "sous-titre externe : autre préparation");

        Files.delete(ROOT.resolve("Days/Days - S01E01.fre.ass"));
        scan(true);
        assertEquals(0, count(ds, "SELECT count(*) FROM external_subtitle WHERE available AND file_name = 'Days - S01E01.fre.ass'"));
        assertEquals(WebCache.key(WebPrepService.BASE, fileId, size, modified), prep.baseKey(fileId, size, modified),
                "plus de sous-titre externe : clé d'avant 1.3.0");
        assertTrue(list("ALL").getList("items.relativePath").stream().noneMatch("Days/Days - S01E01.fre.ass"::equals));
    }
}
