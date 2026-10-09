package fr.plexwish.animeserver.setup;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

/** Installation et réglages du déploiement (D1.3), préfixe {@code anime.setup.}. */
@ConfigMapping(prefix = "anime.setup")
public interface SetupConfig {

    /**
     * Entrée supposée quand nginx n'a pas posé l'en-tête {@link Entry#HEADER} : « public » en production (le cas le
     * plus restrictif), « local » en développement et en test (pas de nginx).
     */
    @WithDefault("public")
    String missingEntry();

    /** Dossier des secrets saisis dans l'interface (clé TMDB, jeton du DNS dynamique) : un fichier 600 chacun. */
    String secretsDir();

    /** Dossier des sauvegardes (lecture seule) : le serveur y lit status.json, écrit par le conteneur backup. */
    @WithDefault("/data/backups")
    String backupsDir();

    Ddns ddns();

    interface Ddns {
        /** Mise à jour automatique de l'adresse du DNS dynamique (désactivée dans les tests). */
        @WithDefault("true")
        boolean enabled();

        @WithDefault("https://www.duckdns.org/update")
        String duckdnsUrl();

        @WithDefault("5m")
        Duration interval();
    }
}
