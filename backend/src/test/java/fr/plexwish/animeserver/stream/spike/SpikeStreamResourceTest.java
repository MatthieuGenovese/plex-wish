package fr.plexwish.animeserver.stream.spike;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Le profil de test pointe sur target/test-media (voir application.properties). */
@QuarkusTest
class SpikeStreamResourceTest {

    private static final int SIZE = 10_000;
    private static final byte[] CONTENT = new byte[SIZE];
    private static int mp4Id;
    private static int mkvId;

    @BeforeAll
    static void createMedia() throws IOException {
        for (int i = 0; i < SIZE; i++) {
            CONTENT[i] = (byte) (i % 251);
        }
        Path root = Path.of("target/test-media");
        Files.createDirectories(root.resolve("Show/Season 01"));
        Files.write(root.resolve("sample.mp4"), CONTENT);
        Files.write(root.resolve("Show/Season 01/Show - S01E01.mkv"), new byte[]{1, 2, 3});
        Files.writeString(root.resolve("notes.txt"), "pas une vidéo");
        // Fichier hors de la racine : ne doit jamais être atteignable.
        Files.writeString(Path.of("target/secret.mp4"), "secret");
    }

    private static int idOf(String name) {
        List<Map<String, Object>> files = given().get("/api/dev/files").then().statusCode(200)
                .extract().jsonPath().getList("$");
        return files.stream().filter(f -> name.equals(f.get("name")))
                .map(f -> (Integer) f.get("id")).findFirst().orElseThrow();
    }

    private int ids() {
        if (mp4Id == 0) {
            mp4Id = idOf("sample.mp4");
            mkvId = idOf("Show/Season 01/Show - S01E01.mkv");
        }
        return mp4Id;
    }

    private static byte[] slice(int start, int endInclusive) {
        return Arrays.copyOfRange(CONTENT, start, endInclusive + 1);
    }

    @Test
    void listsOnlyVideosWithPlayableUrls() {
        given().get("/api/dev/files").then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("url", org.hamcrest.Matchers.everyItem(startsWith("http://")));
    }

    @Test
    void withoutRangeServesWholeFile() {
        byte[] body = given().get("/api/dev/stream/" + ids()).then()
                .statusCode(200)
                .header("Accept-Ranges", "bytes")
                .header("Content-Length", String.valueOf(SIZE))
                .contentType("video/mp4")
                .extract().asByteArray();
        assertArrayEquals(CONTENT, body);
    }

    @Test
    void rangeAtStart() {
        byte[] body = given().header("Range", "bytes=0-99").get("/api/dev/stream/" + ids()).then()
                .statusCode(206)
                .header("Accept-Ranges", "bytes")
                .header("Content-Range", "bytes 0-99/" + SIZE)
                .header("Content-Length", "100")
                .contentType("video/mp4")
                .extract().asByteArray();
        assertArrayEquals(slice(0, 99), body);
    }

    @Test
    void rangeInTheMiddle() {
        byte[] body = given().header("Range", "bytes=5000-5999").get("/api/dev/stream/" + ids()).then()
                .statusCode(206)
                .header("Content-Range", "bytes 5000-5999/" + SIZE)
                .extract().asByteArray();
        assertArrayEquals(slice(5000, 5999), body);
    }

    @Test
    void openRangeAndSuffixRangeReachTheEnd() {
        byte[] open = given().header("Range", "bytes=9900-").get("/api/dev/stream/" + ids()).then()
                .statusCode(206)
                .header("Content-Range", "bytes 9900-9999/" + SIZE)
                .extract().asByteArray();
        assertArrayEquals(slice(9900, 9999), open);

        byte[] suffix = given().header("Range", "bytes=-500").get("/api/dev/stream/" + ids()).then()
                .statusCode(206)
                .header("Content-Range", "bytes 9500-9999/" + SIZE)
                .extract().asByteArray();
        assertArrayEquals(slice(9500, 9999), suffix);
    }

    @Test
    void rangeEndBeyondFileIsClamped() {
        given().header("Range", "bytes=9990-20000").get("/api/dev/stream/" + ids()).then()
                .statusCode(206)
                .header("Content-Range", "bytes 9990-9999/" + SIZE)
                .header("Content-Length", "10");
    }

    @Test
    void unsatisfiableRangeGives416() {
        given().header("Range", "bytes=" + SIZE + "-").get("/api/dev/stream/" + ids()).then()
                .statusCode(416)
                .header("Content-Range", "bytes */" + SIZE);
    }

    @Test
    void malformedRangeIsIgnored() {
        given().header("Range", "bytes=abc").get("/api/dev/stream/" + ids()).then()
                .statusCode(200)
                .header("Content-Length", String.valueOf(SIZE));
    }

    @Test
    void matroskaMimeType() {
        ids();
        given().get("/api/dev/stream/" + mkvId).then()
                .statusCode(200)
                .contentType("video/x-matroska");
    }

    @Test
    void pathTraversalAndUnknownIdsAreRejected() {
        for (String id : new String[]{"0", "-1", "999", "abc", "sample.mp4", "..%2Fsecret.mp4",
                "..%2F..%2Fpom.xml", "%2E%2E%2Fsecret.mp4", "..%5Csecret.mp4"}) {
            given().urlEncodingEnabled(false).get("/api/dev/stream/" + id).then()
                    .statusCode(anyOf(is(400), is(404)));
        }
        given().get("/api/dev/stream/../secret.mp4").then().statusCode(anyOf(is(400), is(404)));
    }

    @Test
    void notesAreNotListed() {
        given().get("/api/dev/files").then().body("name", org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.hasItem(equalTo("notes.txt"))));
    }
}
