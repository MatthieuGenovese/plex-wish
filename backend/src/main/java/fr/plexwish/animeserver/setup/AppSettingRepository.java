package fr.plexwish.animeserver.setup;

import fr.plexwish.animeserver.common.db.Db;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.SQLException;
import java.util.Optional;

/** Table {@code app_setting} : réglages clé → valeur modifiables depuis l'application. */
@ApplicationScoped
public class AppSettingRepository {

    @Inject
    Db db;

    public Optional<String> find(String key) throws SQLException {
        return db.one("SELECT value FROM app_setting WHERE key = ?", rs -> rs.getString(1), key);
    }

    public void upsert(String key, String value) throws SQLException {
        db.update("INSERT INTO app_setting (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()",
                key, value);
    }

    /** Vrai si cet appel a inséré la clé (elle n'existait pas) : un seul gagnant en cas de concurrence. */
    public boolean insertIfAbsent(String key, String value) throws SQLException {
        return db.update("INSERT INTO app_setting (key, value) VALUES (?, ?) ON CONFLICT (key) DO NOTHING", key, value) == 1;
    }

    public void delete(String key) throws SQLException {
        db.update("DELETE FROM app_setting WHERE key = ?", key);
    }
}
