package fr.plexwish.animeserver.common.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Petits outils JDBC partagés par les repositories : le SQL natif (PostgreSQL) reste écrit tel quel, seul le
 * code répétitif (PreparedStatement, ResultSet, fermetures) est factorisé. Les paramètres sont liés dans
 * l'ordre avec {@link PreparedStatement#setObject(int, Object)} (Long → int8, Integer → int4, String → varchar,
 * comme les setters typés ; {@code null} accepté).
 */
public final class Jdbc {

    private Jdbc() {
    }

    /** Lit une ligne du ResultSet courant. */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** Travail sur une connexion (lecture, écriture ou transaction). */
    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    /** Travail sans résultat. */
    @FunctionalInterface
    public interface SqlVoidWork {
        void run(Connection c) throws SQLException;
    }

    public static PreparedStatement prepare(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement st = c.prepareStatement(sql);
        try {
            bind(st, params);
            return st;
        } catch (SQLException | RuntimeException e) {
            st.close();
            throw e;
        }
    }

    public static void bind(PreparedStatement st, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            if (params[i] instanceof Typed t) {
                if (t.value() == null) {
                    st.setNull(i + 1, t.sqlType());
                } else {
                    st.setObject(i + 1, t.value(), t.sqlType());
                }
            } else {
                st.setObject(i + 1, params[i]);
            }
        }
    }

    /**
     * Paramètre au type SQL imposé. Utile pour un {@code null} dont PostgreSQL ne peut pas deviner le type
     * (ex. {@code ? IS NULL} sans cast) : un {@code null} simple est envoyé sans type.
     */
    public record Typed(Object value, int sqlType) {
    }

    /** Texte, {@code null} compris (comme {@code setString}). */
    public static Typed varchar(String value) {
        return new Typed(value, java.sql.Types.VARCHAR);
    }

    /**
     * Placeholders d'un VALUES multi-lignes : {@code "(?,?),(?,?)"} pour 2 lignes de {@code "?", "?"} (chaque
     * colonne peut porter un cast, ex. {@code "?::bigint"}). Une requête par lot, jamais une par ligne.
     */
    public static String values(int rows, String... columnPlaceholders) {
        java.util.StringJoiner row = new java.util.StringJoiner(",", "(", ")");
        for (String p : columnPlaceholders) {
            row.add(p);
        }
        String one = row.toString();
        java.util.StringJoiner all = new java.util.StringJoiner(",");
        for (int i = 0; i < rows; i++) {
            all.add(one);
        }
        return all.toString();
    }

    /** Toutes les lignes. */
    public static <T> List<T> list(Connection c, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement st = prepare(c, sql, params); ResultSet rs = st.executeQuery()) {
            List<T> out = new ArrayList<>();
            while (rs.next()) {
                out.add(mapper.map(rs));
            }
            return out;
        }
    }

    /** La première ligne, s'il y en a une. Le mapper ne doit pas renvoyer {@code null} (sinon : vide). */
    public static <T> Optional<T> one(Connection c, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement st = prepare(c, sql, params); ResultSet rs = st.executeQuery()) {
            return rs.next() ? Optional.ofNullable(mapper.map(rs)) : Optional.empty();
        }
    }

    /** INSERT / UPDATE / DELETE : nombre de lignes touchées. */
    public static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement st = prepare(c, sql, params)) {
            return st.executeUpdate();
        }
    }

    /** Colonne {@code timestamptz} lue en {@link Instant} ({@code null} si absente). */
    public static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    /** Colonne {@code timestamptz} lue en {@link Instant} ({@code null} si absente). */
    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    /** Entier nullable ({@code null} si la colonne est NULL). */
    public static Integer integer(ResultSet rs, int column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    /** Long nullable ({@code null} si la colonne est NULL). */
    public static Long longOrNull(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
