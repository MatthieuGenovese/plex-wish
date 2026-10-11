package fr.plexwish.animeserver.stream;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static fr.plexwish.animeserver.auth.AuthTestSupport.accessToken;
import static fr.plexwish.animeserver.auth.AuthTestSupport.createUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.patchUser;
import static fr.plexwish.animeserver.auth.AuthTestSupport.unique;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.count;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.deleteTree;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.scan;
import static fr.plexwish.animeserver.library.scan.LibraryTestSupport.truncateLibrary;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sous-titres externes pour Android (1.3.1) : listés avec stream-url, chacun avec une URL signée liée au fichier vidéo
 * et au sous-titre ; contrôles à chaque requête (signature, expiration, utilisateur actif, sous-titre associé à ce
 * fichier et disponible).
 */
@QuarkusTest
class SubtitleStreamTest {

    static final Path ROOT = Path.of("target/test-library");
    static final String ASS = """
            [Script Info]
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Bonjour depuis le fichier externe
            """;

    @Inject
    AgroalDataSource ds;
    @Inject
    StreamSigner signer;

    long userId;
    String user;

    @BeforeEach
    void library() throws Exception {
        truncateLibrary(ds);
        deleteTree(ROOT);
        Files.createDirectories(ROOT.resolve("Show/Subs"));
        Files.write(ROOT.resolve("Show/Show - S01E01.mkv"), new byte[100]);
        Files.write(ROOT.resolve("Show/Show - S01E02.mkv"), new byte[100]);
        Files.writeString(ROOT.resolve("Show/Show - S01E01.fre.ass"), ASS);
        Files.writeString(ROOT.resolve("Show/Subs/Show - Épisode 1.forced.ass"), ASS);
        Files.writeString(ROOT.resolve("Show/Show - S01E02.ass"), ASS);
        assertEquals("SUCCESS", scan(true).getString("status"));
        String name = unique("subviewer");
        userId = createUser(name, "subviewer-password", "USER");
        user = accessToken(name, "subviewer-password");
    }

    private long episode(int n) throws Exception {
        return count(ds, "SELECT e.id FROM episode e WHERE e.episode_number = " + n);
    }

    private long file(int n) throws Exception {
        return count(ds, "SELECT id FROM media_file WHERE file_name = 'Show - S01E0" + n + ".mkv'");
    }

    private long subtitle(String fileName) throws Exception {
        return count(ds, "SELECT id FROM external_subtitle WHERE file_name = '" + fileName + "'");
    }

    private JsonPath streamUrl(int n) throws Exception {
        return given().auth().oauth2(user).get("/api/episodes/" + episode(n) + "/stream-url").then().statusCode(200)
                .extract().jsonPath();
    }

    @Test
    void streamUrlListsExternalSubtitlesWithTheirOwnSignedUrls() throws Exception {
        JsonPath r = streamUrl(1);
        assertEquals(2, r.getInt("subtitles.size()"));
        assertEquals("Français (externe)", r.getString("subtitles[0].label"));
        assertTrue(r.getBoolean("subtitles[0].isDefault"));
        assertFalse(r.getBoolean("subtitles[0].forced"));
        assertEquals("fre", r.getString("subtitles[0].language"));
        assertEquals("Sous-titres externes — forcés", r.getString("subtitles[1].label"));
        assertTrue(r.getBoolean("subtitles[1].forced"));
        assertFalse(r.getBoolean("subtitles[1].isDefault"));
        assertEquals("text/x-ssa", r.getString("subtitles[0].mimeType"));
        String url = r.getString("subtitles[0].url");
        assertTrue(url.startsWith("/api/stream/" + file(1) + "/subtitle/" + subtitle("Show - S01E01.fre.ass") + "?u=" + userId), url);
        assertFalse(r.prettify().contains("Show"), "aucun chemin ni nom de fichier");

        given().get(url).then().statusCode(200).contentType(startsWith("text/x-ssa"))
                .header("Cache-Control", "private, max-age=0").body(containsString("Bonjour depuis le fichier externe"));
        assertEquals(0, streamUrl(2).getInt("subtitles.findAll { it.forced }.size()"));
    }

    @Test
    void signatureIsBoundToFileSubtitleUserAndExpiry() throws Exception {
        long f1 = file(1);
        long s1 = subtitle("Show - S01E01.fre.ass");
        long s2 = subtitle("Show - S01E02.ass");
        long exp = Instant.now().plusSeconds(600).getEpochSecond();
        String good = signer.subtitleUrl(f1, s1, userId, exp);
        given().get(good).then().statusCode(200);

        // Autre sous-titre, autre fichier, autre utilisateur : signature invalide.
        given().get(good.replace("/subtitle/" + s1 + "?", "/subtitle/" + s2 + "?")).then().statusCode(403)
                .body("error", equalTo("STREAM_URL_INVALID"));
        given().get(good.replace("/stream/" + f1 + "/", "/stream/" + file(2) + "/")).then().statusCode(403);
        given().get(good.replace("u=" + userId, "u=" + (userId + 1))).then().statusCode(403);
        // L'URL de la vidéo n'ouvre pas un sous-titre.
        String video = streamUrl(1).getString("url");
        given().get("/api/stream/" + f1 + "/subtitle/" + s1 + video.substring(video.indexOf('?'))).then().statusCode(403);
        // Bien signée, mais pour un sous-titre d'un autre épisode : introuvable.
        given().get(signer.subtitleUrl(f1, s2, userId, exp)).then().statusCode(404);
        // Expirée.
        given().get(signer.subtitleUrl(f1, s1, userId, Instant.now().minusSeconds(5).getEpochSecond())).then().statusCode(403)
                .body("error", equalTo("STREAM_URL_EXPIRED"));
        // Sans paramètres.
        given().get("/api/stream/" + f1 + "/subtitle/" + s1).then().statusCode(403);

        // Compte désactivé depuis l'émission du lien.
        patchUser(userId, Map.of("enabled", false));
        given().get(good).then().statusCode(403).body("error", equalTo("USER_DISABLED"));
    }

    @Test
    void removedSubtitleIsNoLongerServed() throws Exception {
        String url = streamUrl(1).getString("subtitles[0].url");
        Files.delete(ROOT.resolve("Show/Show - S01E01.fre.ass"));
        given().get(url).then().statusCode(404);
        scan(true);
        assertEquals(1, streamUrl(1).getInt("subtitles.size()"));
        given().get(url).then().statusCode(404);
    }
}
