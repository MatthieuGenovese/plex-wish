package fr.plexwish.animeserver.setup;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Réglages de l'installation (table app_setting), en cache : relus souvent (plafond du cache de remux). */
@ApplicationScoped
public class AppSettings {

    @Inject
    AppSettingRepository repository;

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
        try {
            repository.upsert(key, value);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        cache.put(key, Optional.of(value));
    }

    /**
     * Écrit la valeur seulement si la clé n'existe pas ; vrai si c'est cet appel qui l'a écrite (un seul gagnant).
     * Appelée dans une transaction JTA, l'écriture en fait partie.
     */
    public boolean putIfAbsent(String key, String value) {
        try {
            boolean inserted = repository.insertIfAbsent(key, value);
            cache.remove(key);
            return inserted;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void delete(String key) {
        try {
            repository.delete(key);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        cache.remove(key);
    }

    /** Oublie le cache (tests, ou modification directe de la base). */
    public void forget() {
        cache.clear();
    }

    private Optional<String> load(String key) {
        try {
            return repository.find(key);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
