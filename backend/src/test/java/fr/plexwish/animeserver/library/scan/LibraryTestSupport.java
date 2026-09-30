package fr.plexwish.animeserver.library.scan;

import io.agroal.api.AgroalDataSource;
import io.restassured.path.json.JsonPath;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

import static fr.plexwish.animeserver.auth.AuthTestSupport.adminToken;
import static io.restassured.RestAssured.given;

/** Arborescence média de test (fichiers de quelques octets) et lancement de scans via l'API. */
public final class LibraryTestSupport {

    private LibraryTestSupport() {
    }

    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void touch(Path root, String relativePath) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3});
    }

    /** Vide les tables de la bibliothèque (les comptes utilisateurs ne sont pas touchés). */
    public static void truncateLibrary(AgroalDataSource ds) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE scan_issue, scan_run, episode, season, anime, media_file, media_file_override RESTART IDENTITY CASCADE");
        }
    }

    public static long count(AgroalDataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Lance un scan et attend sa fin ; renvoie le rapport. */
    public static JsonPath scan() {
        return scan(false);
    }

    public static JsonPath scan(boolean confirmMassRemoval) {
        String token = adminToken();
        long id = ((Number) given().auth().oauth2(token).queryParam("confirmMassRemoval", confirmMassRemoval)
                .post("/api/admin/library/scan")
                .then().statusCode(202).extract().path("scanId")).longValue();
        return waitFor(token, id, Duration.ofMinutes(5));
    }

    public static JsonPath waitFor(String token, long scanId, Duration timeout) {
        Instant limit = Instant.now().plus(timeout);
        while (true) {
            JsonPath report = given().auth().oauth2(token).queryParam("scanId", scanId)
                    .get("/api/admin/library/scan-report").then().statusCode(200).extract().jsonPath();
            if (!"RUNNING".equals(report.getString("status"))) {
                return report;
            }
            if (Instant.now().isAfter(limit)) {
                throw new AssertionError("scan " + scanId + " toujours RUNNING après " + timeout);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
