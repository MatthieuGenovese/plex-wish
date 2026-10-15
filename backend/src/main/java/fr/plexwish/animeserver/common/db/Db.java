package fr.plexwish.animeserver.common.db;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Accès à la base pour les repositories JDBC : chaque appel simple prend une connexion du pool et la rend.
 * Pour plusieurs requêtes atomiques, {@link #inTransaction} fournit une connexion en transaction manuelle
 * (commit à la fin, rollback sur erreur) que l'appelant passe aux méthodes de repository qui la prennent.
 */
@ApplicationScoped
public class Db {

    @Inject
    AgroalDataSource dataSource;

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    public <T> List<T> list(String sql, Jdbc.RowMapper<T> mapper, Object... params) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return Jdbc.list(c, sql, mapper, params);
        }
    }

    public <T> Optional<T> one(String sql, Jdbc.RowMapper<T> mapper, Object... params) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return Jdbc.one(c, sql, mapper, params);
        }
    }

    public int update(String sql, Object... params) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return Jdbc.update(c, sql, params);
        }
    }

    /** Plusieurs requêtes sur une même connexion, en auto-commit. */
    public <T> T withConnection(Jdbc.SqlWork<T> work) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return work.run(c);
        }
    }

    /** Plusieurs requêtes dans une transaction : commit si tout passe, rollback sinon. */
    public <T> T inTransaction(Jdbc.SqlWork<T> work) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (Throwable e) {
                try {
                    c.rollback();
                } catch (SQLException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public void runInTransaction(Jdbc.SqlVoidWork work) throws SQLException {
        inTransaction(c -> {
            work.run(c);
            return null;
        });
    }
}
