package fr.plexwish.animeserver.common.db;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Helper JDBC des repositories : lecture, écriture, paramètres nuls, transaction (commit et rollback). */
@QuarkusTest
class DbTest {

    private static final String PREFIX = "dbtest.";

    @Inject
    Db db;

    @AfterEach
    void cleanup() throws SQLException {
        db.update("DELETE FROM app_setting WHERE key LIKE ?", PREFIX + "%");
    }

    private void put(String key, String value) throws SQLException {
        db.update("INSERT INTO app_setting (key, value) VALUES (?, ?)", PREFIX + key, value);
    }

    private Optional<String> get(String key) throws SQLException {
        return db.one("SELECT value FROM app_setting WHERE key = ?", rs -> rs.getString(1), PREFIX + key);
    }

    @Test
    void listOneAndUpdate() throws SQLException {
        put("b", "2");
        put("a", "1");
        List<String> keys = db.list("SELECT key FROM app_setting WHERE key LIKE ? ORDER BY key", rs -> rs.getString(1), PREFIX + "%");
        assertEquals(List.of(PREFIX + "a", PREFIX + "b"), keys);
        assertEquals(Optional.of("1"), get("a"));
        assertEquals(Optional.empty(), get("absent"));
        assertEquals(1, db.update("UPDATE app_setting SET value = ? WHERE key = ?", "3", PREFIX + "a"));
        assertEquals(0, db.update("UPDATE app_setting SET value = ? WHERE key = ?", "3", PREFIX + "absent"));
    }

    @Test
    void nullParametersUntypedAndTyped() throws SQLException {
        // null sans type : PostgreSQL le déduit du contexte (cast, colonne).
        assertTrue(db.one("SELECT ?::bigint IS NULL", rs -> rs.getBoolean(1), (Object) null).orElseThrow());
        // null typé : nécessaire quand rien ne permet de déduire le type.
        assertTrue(db.one("SELECT ? IS NULL", rs -> rs.getBoolean(1), Jdbc.varchar(null)).orElseThrow());
        assertEquals("x", db.one("SELECT COALESCE(?, 'x')", rs -> rs.getString(1), Jdbc.varchar(null)).orElseThrow());
        assertEquals(7L, db.one("SELECT ?::bigint", rs -> rs.getLong(1), 7L).orElseThrow());
        assertNull(db.one("SELECT NULL::int", rs -> Jdbc.integer(rs, 1)).orElse(null));
        assertNull(db.one("SELECT NULL::timestamptz", rs -> Jdbc.instant(rs, 1)).orElse(null));
    }

    @Test
    void transactionCommitsOrRollsBack() throws SQLException {
        int written = db.inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO app_setting (key, value) VALUES (?, ?)", PREFIX + "tx1", "1");
            return Jdbc.update(c, "INSERT INTO app_setting (key, value) VALUES (?, ?)", PREFIX + "tx2", "2");
        });
        assertEquals(1, written);
        assertEquals(Optional.of("2"), get("tx2"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> db.runInTransaction(c -> {
            Jdbc.update(c, "INSERT INTO app_setting (key, value) VALUES (?, ?)", PREFIX + "rolled", "1");
            throw new IllegalStateException("boom");
        }));
        assertEquals("boom", e.getMessage());
        assertEquals(Optional.empty(), get("rolled"), "rollback : rien d'écrit");

        // La connexion rendue au pool est revenue en auto-commit.
        put("after", "1");
        assertEquals(Optional.of("1"), get("after"));
    }
}
