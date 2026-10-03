package fr.plexwish.animeserver.poster;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.List;

/**
 * Affiches stockées sur le NAS (ARCHITECTURE §17). Dossier en écriture, distinct de /media (qui reste en lecture
 * seule). Sans dossier utilisable, la tâche ne démarre pas et les affiches restent chargées depuis leur source.
 */
@ConfigMapping(prefix = "anime.posters")
public interface PosterConfig {

    /** Dossier des affiches dans le conteneur (volume Docker en écriture). */
    @WithDefault("/data/posters")
    String path();

    /** Tâche de téléchargement (false : affiches distantes uniquement). */
    @WithDefault("true")
    boolean enabled();

    /** Seuls hôtes d'où l'on télécharge (jamais d'autre, même par redirection). */
    @WithDefault("image.tmdb.org,s4.anilist.co")
    List<String> allowedHosts();

    /** http:// accepté (tests uniquement : faux serveur local). */
    @WithDefault("false")
    boolean allowHttp();

    /** Taille maximale d'une affiche. */
    @WithDefault("5242880")
    long maxBytes();

    /** Base des affiches TMDB (taille w500 : environ 500 × 750 px). */
    @WithDefault("https://image.tmdb.org/t/p/w500")
    String tmdbImageBase();

    /** Espacement des téléchargements (ne pas charger les serveurs d'images). */
    @WithDefault("500ms")
    Duration minInterval();

    /** Attente quand il n'y a rien à faire (réveillée à la fin d'un scan). */
    @WithDefault("5m")
    Duration pollInterval();

    /** Pause quand le serveur d'images est injoignable. */
    @WithDefault("5m")
    Duration unavailablePause();

    /** Affiches TMDB : retéléchargées après 5 mois, effacées à 6 (conditions de l'API TMDB, §1.C). */
    @WithDefault("150d")
    Duration tmdbRefreshAfter();

    @WithDefault("180d")
    Duration tmdbMaxAge();

    /** Cache navigateur d'une affiche locale (son URL change quand l'image change). */
    @WithDefault("30d")
    Duration browserCache();
}
