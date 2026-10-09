package fr.plexwish.animeserver.tmdb;

import fr.plexwish.animeserver.setup.SecretStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Clé TMDB en vigueur (D1.3) : celle saisie dans l'interface (secret, fichier 600) en priorité, sinon celle de la
 * configuration (TMDB_READ_TOKEN / TMDB_API_KEY, pour la pile de développement). Relue à chaque appel : une clé saisie
 * ou retirée dans l'interface vaut tout de suite, sans redémarrage. Jamais journalisée ni renvoyée au navigateur.
 */
@ApplicationScoped
public class TmdbCredentials {

    /** Ancienne clé v3 : 32 caractères hexadécimaux. Tout le reste est traité comme un jeton de lecture (Bearer). */
    private static final Pattern V3_KEY = Pattern.compile("[0-9a-fA-F]{32}");

    /** {@code bearer} ou {@code apiKey}, l'un des deux. */
    public record Credential(String bearer, String apiKey) {
        @Override
        public String toString() {
            return "Credential[***]";
        }
    }

    @Inject
    TmdbConfig config;
    @Inject
    SecretStore secrets;

    public Optional<Credential> current() {
        Optional<String> saved = secrets.read(SecretStore.TMDB);
        if (saved.isPresent()) {
            String v = saved.get();
            return Optional.of(V3_KEY.matcher(v).matches() ? new Credential(null, v) : new Credential(v, null));
        }
        Optional<String> token = config.readToken().filter(t -> !t.isBlank());
        if (token.isPresent()) {
            return Optional.of(new Credential(token.get(), null));
        }
        return config.apiKey().filter(k -> !k.isBlank()).map(k -> new Credential(null, k));
    }

    public boolean configured() {
        return config.enabled() && current().isPresent();
    }

    /** « interface » (saisie par l'admin), « configuration » (variable d'environnement) ou vide. */
    public Optional<String> source() {
        if (secrets.exists(SecretStore.TMDB)) {
            return Optional.of("interface");
        }
        return current().map(c -> "configuration");
    }
}
