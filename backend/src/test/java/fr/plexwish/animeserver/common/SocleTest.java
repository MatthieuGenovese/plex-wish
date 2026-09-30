package fr.plexwish.animeserver.common;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Démarrage de l'application sur un vrai PostgreSQL (Dev Services) : migrations, health, erreurs, CORS. */
@QuarkusTest
class SocleTest {

    @Inject
    Flyway flyway;

    @Inject
    AgroalDataSource dataSource;

    @Test
    void flywayMigrationsAreApplied() throws SQLException {
        assertEquals("3", flyway.info().current().getVersion().getVersion());
        assertEquals(0, flyway.info().pending().length);

        Set<String> tables = new HashSet<>();
        try (Connection c = dataSource.getConnection();
             ResultSet rs = c.getMetaData().getTables(null, "public", "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        assertTrue(tables.containsAll(Set.of("app_user", "refresh_token")), tables.toString());
    }

    @Test
    void usernameIsUniqueIgnoringCase() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.executeUpdate("INSERT INTO app_user (username, password_hash, role) VALUES ('CaseTest', 'x', 'USER')");
                SQLException e = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () ->
                        st.executeUpdate("INSERT INTO app_user (username, password_hash, role) VALUES ('casetest', 'x', 'USER')"));
                assertEquals("23505", e.getSQLState()); // unique_violation
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void healthIsUpWithDatabaseCheck() {
        given().get("/q/health/ready").then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.name", hasItem("Database connections health check"));
        given().get("/q/health/live").then().statusCode(200).body("status", equalTo("UP"));
    }

    @Test
    void publicStatusEndpoint() {
        given().get("/api/status").then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void unknownRouteGivesJsonError() {
        given().get("/api/does-not-exist").then()
                .statusCode(404)
                .contentType("application/json")
                .body("status", equalTo(404))
                .body("error", equalTo("NOT_FOUND"));
    }

    @Test
    void wrongMethodGivesJsonError() {
        given().delete("/api/status").then()
                .statusCode(405)
                .body("error", equalTo("METHOD_NOT_ALLOWED"));
    }

    @Test
    void otherOriginsAreNotAllowedByDefault() {
        given().header("Origin", "http://evil.example")
                .get("/api/status").then()
                .header("Access-Control-Allow-Origin", nullValue());
        given().header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .options("/api/status").then()
                .header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    void swaggerIsDisabledOutsideDev() {
        given().get("/q/swagger-ui").then().statusCode(404);
        given().get("/q/openapi").then().statusCode(404);
    }
}
