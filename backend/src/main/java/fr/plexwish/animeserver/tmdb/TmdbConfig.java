package fr.plexwish.animeserver.tmdb;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Optional;

/**
 * TMDB, second fournisseur (synopsis et titre en français, ARCHITECTURE §16). Sans clé, il est simplement inactif :
 * le démarrage n'échoue pas et le synopsis anglais d'AniList reste affiché. Les clés ne sont lues que par le
 * backend : jamais renvoyées au client, jamais journalisées.
 */
@ConfigMapping(prefix = "anime.tmdb")
public interface TmdbConfig {

    /** Jeton de lecture (« API Read Access Token », envoyé en Bearer) : méthode recommandée par TMDB. */
    Optional<String> readToken();

    /** Ancienne clé v3 (paramètre api_key), si pas de jeton. */
    Optional<String> apiKey();

    @WithDefault("true")
    boolean enabled();

    /** Tâche de fond (désactivée dans les tests, qui appellent le service directement). */
    @WithDefault("true")
    boolean workerEnabled();

    @WithDefault("https://api.themoviedb.org/3")
    String apiUrl();

    /** Langue demandée (synopsis, titre). */
    @WithDefault("fr-FR")
    String language();

    /** Espacement des appels : bien en dessous de la limite technique (~40/s), pas de « consommation excessive ». */
    @WithDefault("250ms")
    Duration minInterval();

    /** Conditions de l'API TMDB : rien de conservé plus de 6 mois. Rafraîchi après 5 mois… */
    @WithDefault("150d")
    Duration refreshAfter();

    /** …et effacé à 6 mois s'il n'a pas pu l'être. */
    @WithDefault("180d")
    Duration maxAge();

    @WithDefault("5m")
    Duration unavailablePause();

    @WithDefault("30m")
    Duration pollInterval();

    default boolean configured() {
        return enabled() && (readToken().filter(t -> !t.isBlank()).isPresent() || apiKey().filter(k -> !k.isBlank()).isPresent());
    }
}
