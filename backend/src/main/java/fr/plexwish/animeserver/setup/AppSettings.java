package fr.plexwish.animeserver.setup;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Réglages de l'installation (table app_setting), en cache : relus souvent (plafond du cache de remux). */
@ApplicationScoped
public class AppSettings {

    @Inject
    AgroalDataSource dataSource;

    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();

    public Optional<String> get(String key) {
        return cache.computeIfAbsent(key, this::load);
    }

    public Optional<Double> getDouble(String key) {
        return get(key).flatMap(v -> {
            try {
                return Optional.of(Double.parseDouble(v));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    public void put(String key, String value) {
        run("INSERT INTO app_setting (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()",
                key, value);
        cache.put(key, Optional.of(value));
    }

    /** Écrit la valeur seulement si la clé n'existe pas ; vrai si c'est cet appel qui l'a écrite (un seul gagnant). */
    public boolean putIfAbsent(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("INSERT INTO app_setting (key, value) VALUES (?, ?) ON CONFLICT (key) DO NOTHING")) {
            st.setString(1, key);
            st.setString(2, value);
            boolean inserted = st.executeUpdate() == 1;
            cache.remove(key);
            return inserted;
        }
    }

    public void delete(String key) {
        run("DELETE FROM app_setting WHERE key = ?", key);
        cache.remove(key);
    }

    /** Oublie le cache (tests, ou modification directe de la base). */
    public void forget() {
        cache.clear();
    }

    private Optional<String> load(String key) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT value FROM app_setting WHERE key = ?")) {
            st.setString(1, key);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void run(String sql, String... params) {
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                st.setString(i + 1, params[i]);
            }
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
