package fr.plexwish.animeserver.library.scan;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.StringJoiner;

/** Petits outils JDBC pour les requêtes multi-lignes du scan (une requête par lot, pas par fichier). */
final class Sql {

    private Sql() {
    }

    /** "(?,?,?),(?,?,?)" pour {@code rows} lignes de {@code columns} colonnes, avec cast éventuel par colonne. */
    static String values(int rows, String... columnPlaceholders) {
        StringJoiner row = new StringJoiner(",", "(", ")");
        for (String p : columnPlaceholders) {
            row.add(p);
        }
        String one = row.toString();
        StringJoiner all = new StringJoiner(",");
        for (int i = 0; i < rows; i++) {
            all.add(one);
        }
        return all.toString();
    }

    static PreparedStatement prepare(Connection c, String sql, List<Object> params) throws SQLException {
        PreparedStatement st = c.prepareStatement(sql);
        for (int i = 0; i < params.size(); i++) {
            st.setObject(i + 1, params.get(i));
        }
        return st;
    }
}
