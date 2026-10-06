package fr.plexwish.animeserver.stream;

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
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lecture définitive (§6) : URL signée, Range Requests, contrôles à chaque requête (signature, expiration,
 * utilisateur actif, fichier disponible, chemin sous la racine). Fichiers réels avec contenu connu.
 */
@QuarkusTest
class StreamTest {

    static final Path ROOT = Path.of("target/test-library");
    static final int SIZE = 10_000;
    static final byte[] CONTENT = new byte[SIZE];

    static {
        for (int i = 0; i < SIZE; i++) {
            CONTENT[i] = (byte) (i % 251);
        }
    }

    @Inject
    AgroalDataSource ds;
    @Inject
    StreamSigner signer;

    long aliceId;
    String alice;

    @BeforeEach
    void library() throws Exception {
        truncateLibrary(ds);
        deleteTree(ROOT);
        Files.createDirectories(ROOT.resolve("Show"));
        Files.write(ROOT.resolve("Show/Show - S01E01.mkv"), CONTENT);
        for (String ext : new String[]{"mp4", "ogm", "ts", "avi"}) {
            int n = switch (ext) { case "mp4" -> 2; case "ogm" -> 3; case "ts" -> 4; default -> 5; };
            Files.write(ROOT.resolve("Show/Show - S01E0" + n + "." + ext), new byte[]{1, 2, 3});
        }
        assertEquals("SUCCESS", scan().getString("status"));
        String name = unique("viewer");
        aliceId = createUser(name, "viewer-password-1", "USER");
        alice = accessToken(name, "viewer-password-1");
    }

    private long episodeId(int n) throws Exception {
        return count(ds, "SELECT e.id FROM episode e JOIN season s ON s.id = e.season_id WHERE s.season_number = 1"
                + " AND e.episode_number = " + n);
    }

    private long fileId(String path) throws Exception {
        return count(ds, "SELECT id FROM media_file WHERE relative_path = '" + path + "'");
    }

    private JsonPath streamUrl(String token, int episode) throws Exception {
        return given().auth().oauth2(token).get("/api/episodes/" + episodeId(episode) + "/stream-url")
                .then().statusCode(200).extract().jsonPath();
    }

    private String url(int episode) throws Exception {
        return streamUrl(alice, episode).getString("url");
    }

    private static byte[] slice(int start, int endInclusive) {
        return Arrays.copyOfRange(CONTENT, start, endInclusive + 1);
    }

    // --- URL signée ----------------------------------------------------------------------------

    @Test
    void streamUrlIsShortLivedAndBoundToFileAndUser() throws Exception {
        JsonPath r = streamUrl(alice, 1);
        String url = r.getString("url");
        long file = fileId("Show/Show - S01E01.mkv");
        assertTrue(url.startsWith("/api/stream/" + file + "?u=" + aliceId + "&exp="), url);
        assertTrue(!url.contains("Show"), "aucun chemin dans l'URL");
        assertEquals("video/x-matroska", r.getString("mimeType"));
        assertEquals(SIZE, r.getLong("fileSize"));
        Duration left = Duration.between(Instant.now(), Instant.parse(r.getString("expiresAt")));
        assertTrue(left.compareTo(Duration.ofHours(5)) > 0 && left.compareTo(Duration.ofHours(6).plusMinutes(1)) < 0, left.toString());

        given().get("/api/episodes/" + episodeId(1) + "/stream-url").then().statusCode(401);
        given().auth().oauth2(alice).get("/api/episodes/999999/stream-url").then().statusCode(404)
                .body("error", equalTo("EPISODE_NOT_FOUND"));
    }

    @Test
    void wholeFileWithoutRange() throws Exception {
        byte[] body = given().get(url(1)).then().statusCode(200)
                .header("Accept-Ranges", "bytes")
                .header("Content-Length", String.valueOf(SIZE))
                .header("Cache-Control", startsWith("private"))
                .contentType("video/x-matroska")
                .extract().asByteArray();
        assertArrayEquals(CONTENT, body);
    }

    @Test
    void rangesStartMiddleEndAndInvalid() throws Exception {
        String url = url(1);
        assertArrayEquals(slice(0, 99), given().header("Range", "bytes=0-99").get(url).then().statusCode(206)
                .header("Content-Range", "bytes 0-99/" + SIZE).header("Content-Length", "100")
                .header("Accept-Ranges", "bytes").contentType("video/x-matroska").extract().asByteArray());
        assertArrayEquals(slice(5000, 5999), given().header("Range", "bytes=5000-5999").get(url).then().statusCode(206)
                .header("Content-Range", "bytes 5000-5999/" + SIZE).extract().asByteArray());
        assertArrayEquals(slice(9900, 9999), given().header("Range", "bytes=9900-").get(url).then().statusCode(206)
                .header("Content-Range", "bytes 9900-9999/" + SIZE).extract().asByteArray());
        assertArrayEquals(slice(9500, 9999), given().header("Range", "bytes=-500").get(url).then().statusCode(206)
                .header("Content-Range", "bytes 9500-9999/" + SIZE).extract().asByteArray());
        given().header("Range", "bytes=9990-20000").get(url).then().statusCode(206)
                .header("Content-Range", "bytes 9990-9999/" + SIZE).header("Content-Length", "10");
        given().header("Range", "bytes=" + SIZE + "-").get(url).then().statusCode(416)
                .header("Content-Range", "bytes */" + SIZE).header("Accept-Ranges", "bytes");
        given().header("Range", "bytes=-0").get(url).then().statusCode(416);
        given().header("Range", "bytes=abc").get(url).then().statusCode(200).header("Content-Length", String.valueOf(SIZE));
    }

    @Test
    void headGivesSizeAndTypeWithoutBody() throws Exception {
        byte[] body = given().head(url(1)).then().statusCode(200)
                .header("Content-Length", String.valueOf(SIZE)).contentType("video/x-matroska")
                .header("Accept-Ranges", "bytes").extract().asByteArray();
        assertEquals(0, body.length);
    }

    @Test
    void mimeTypeByExtension() throws Exception {
        Map<Integer, String> expected = Map.of(2, "video/mp4", 4, "video/mp2t");
        for (var e : expected.entrySet()) {
            JsonPath r = streamUrl(alice, e.getKey());
            assertEquals(e.getValue(), r.getString("mimeType"));
            given().get(r.getString("url")).then().statusCode(200).contentType(e.getValue());
        }
        // OGM et AVI : jamais l'URL de l'original (illisible sur Android) ; préparation de la copie (202), ou
        // conversion indisponible si ffmpeg manque (503). Détail : RemuxTest.
        for (int ep : new int[]{3, 5}) {
            io.restassured.response.Response r = given().auth().oauth2(alice).get("/api/episodes/" + episodeId(ep) + "/stream-url");
            assertTrue(r.statusCode() == 202 || r.statusCode() == 503, r.statusCode() + " " + r.asString());
            assertTrue(r.asString().indexOf("\"url\"") < 0, r.asString());
        }
    }

    // --- URL falsifiée, expirée, d'un autre utilisateur ------------------------------------------------

    @Test
    void forgedUrlsAreRefused() throws Exception {
        String url = url(1);
        String sig = url.substring(url.indexOf("&sig=") + 5);
        String flipped = (sig.charAt(0) == 'A' ? 'B' : 'A') + sig.substring(1);
        given().get(url.replace("&sig=" + sig, "&sig=" + flipped)).then().statusCode(403)
                .body("error", equalTo("STREAM_URL_INVALID"));
        given().get(url.replace("&sig=" + sig, "")).then().statusCode(403).body("error", equalTo("STREAM_URL_INVALID"));
        given().get(url.substring(0, url.indexOf('?'))).then().statusCode(403);

        // Même signature, autre fichier.
        long other = fileId("Show/Show - S01E02.mp4");
        given().get(url.replace("/api/stream/" + fileId("Show/Show - S01E01.mkv"), "/api/stream/" + other))
                .then().statusCode(403).body("error", equalTo("STREAM_URL_INVALID"));
        // Expiration repoussée à la main.
        String exp = url.replaceAll(".*&exp=(\\d+).*", "$1");
        given().get(url.replace("&exp=" + exp, "&exp=" + (Long.parseLong(exp) + 86_400))).then().statusCode(403)
                .body("error", equalTo("STREAM_URL_INVALID"));
        given().get("/api/stream/abc?u=1&exp=1&sig=x").then().statusCode(404);
    }

    @Test
    void urlOfAnotherUserCannotBeReused() throws Exception {
        String bobName = unique("bob");
        long bobId = createUser(bobName, "bob-password-12", "USER");
        String bobUrl = streamUrl(accessToken(bobName, "bob-password-12"), 1).getString("url");
        // Bob's URL with Alice's id (or the other way round): the signature no longer matches.
        given().get(bobUrl.replace("?u=" + bobId + "&", "?u=" + aliceId + "&")).then().statusCode(403)
                .body("error", equalTo("STREAM_URL_INVALID"));
        given().get(bobUrl).then().statusCode(200);
    }

    @Test
    void expiredUrlIsRefusedSoTheClientAsksForANewOne() throws Exception {
        long file = fileId("Show/Show - S01E01.mkv");
        long past = Instant.now().minusSeconds(60).getEpochSecond();
        String expired = "/api/stream/" + file + "?u=" + aliceId + "&exp=" + past + "&sig=" + signer.signature(file, aliceId, past);
        given().get(expired).then().statusCode(403).body("error", equalTo("STREAM_URL_EXPIRED"));
        given().header("Range", "bytes=0-9").get(expired).then().statusCode(403);
        given().get(url(1)).then().statusCode(200); // nouvelle URL : ça repart
    }

    // --- Utilisateur et fichier vérifiés à chaque requête ----------------------------------------------

    @Test
    void disabledUserIsRefusedOnEveryRequest() throws Exception {
        String url = url(1);
        given().header("Range", "bytes=0-9").get(url).then().statusCode(206);
        patchUser(aliceId, Map.of("enabled", false)).then().statusCode(200);
        given().header("Range", "bytes=10-19").get(url).then().statusCode(403).body("error", equalTo("USER_DISABLED"));
        given().head(url).then().statusCode(403);
        patchUser(aliceId, Map.of("enabled", true)).then().statusCode(200);
        given().header("Range", "bytes=10-19").get(url).then().statusCode(206);
    }

    @Test
    void unavailableFile() throws Exception {
        String url = url(1);
        long episode = episodeId(1);
        // Fichier supprimé du disque, scan pas encore relancé : 404, jamais 500.
        Files.delete(ROOT.resolve("Show/Show - S01E01.mkv"));
        given().get(url).then().statusCode(404).body("error", equalTo("EPISODE_UNAVAILABLE"));
        // Après le scan, le fichier est marqué indisponible : plus d'URL pour cet épisode.
        scan();
        given().get(url).then().statusCode(404).body("error", equalTo("EPISODE_UNAVAILABLE"));
        given().auth().oauth2(alice).get("/api/episodes/" + episode + "/stream-url").then().statusCode(404)
                .body("error", equalTo("EPISODE_UNAVAILABLE"))
                .body("message", startsWith("Cet épisode n'est pas disponible"));
    }

    @Test
    void pathTraversalIsImpossibleEvenFromTheDatabase() throws Exception {
        Files.writeString(Path.of("target/secret.mkv"), "secret");
        Path link = ROOT.resolve("Show/escape.mkv");
        Files.createSymbolicLink(link, Path.of("target/secret.mkv").toAbsolutePath());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String path : new String[]{"../secret.mkv", "Show/../../secret.mkv",
                    Path.of("target/secret.mkv").toAbsolutePath().toString(), "Show/escape.mkv"}) {
                st.execute("INSERT INTO media_file (relative_path, file_name, file_size, container, kind, first_seen_at, last_seen_at)"
                        + " VALUES ('" + path + "', 'secret.mkv', 6, 'mkv', 'EPISODE', now(), now())");
            }
        }
        for (String path : new String[]{"../secret.mkv", "Show/../../secret.mkv",
                Path.of("target/secret.mkv").toAbsolutePath().toString(), "Show/escape.mkv"}) {
            long file = fileId(path);
            long exp = Instant.now().plusSeconds(600).getEpochSecond();
            String url = "/api/stream/" + file + "?u=" + aliceId + "&exp=" + exp + "&sig=" + signer.signature(file, aliceId, exp);
            String body = given().get(url).then().statusCode(404).extract().asString();
            assertTrue(!body.contains("secret") || body.contains("EPISODE_UNAVAILABLE"), path);
        }
        // Le client ne peut de toute façon pas fournir de chemin : seul un id numérique est accepté.
        given().get("/api/stream/..%2Fsecret.mkv?u=1&exp=1&sig=x").then().statusCode(404);
    }
}
