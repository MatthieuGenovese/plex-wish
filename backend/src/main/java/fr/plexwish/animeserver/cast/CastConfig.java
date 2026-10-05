package fr.plexwish.animeserver.cast;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

/** Distribution (étape 6.3, ARCHITECTURE §19), préfixe {@code anime.cast.}. Source unique : AniList. */
@ConfigMapping(prefix = "anime.cast")
public interface CastConfig {

    /** Tâche de fond (CAST_ENABLED=false : plus de récupération ; la distribution déjà là reste affichée). */
    @WithDefault("true")
    boolean enabled();

    /** Rôles gardés par animé (principaux d'abord, puis secondaires), toutes saisons confondues (CAST_MAX_ROLES). */
    @WithDefault("20")
    int maxRoles();

    /** Suites AniList suivies au plus (saisons d'un dossier). */
    @WithDefault("6")
    int maxSeasons();

    /** Distribution redemandée après ce délai (AniList n'impose rien ; nouveaux personnages d'une série en cours). */
    @WithDefault("180d")
    Duration refreshAfter();

    /** Taille maximale d'une photo de comédien. */
    @WithDefault("1048576")
    long imageMaxBytes();

    /** Attente quand il n'y a rien à faire, ou quand les métadonnées sont prioritaires. */
    @WithDefault("10m")
    Duration pollInterval();

    @WithDefault("1m")
    Duration yieldInterval();

    @WithDefault("5m")
    Duration unavailablePause();

    @WithDefault("5")
    int maxAttempts();
}
