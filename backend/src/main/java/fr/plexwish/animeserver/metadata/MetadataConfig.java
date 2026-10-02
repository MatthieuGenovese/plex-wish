package fr.plexwish.animeserver.metadata;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

/** Récupération des métadonnées (préfixe {@code anime.metadata.}). */
@ConfigMapping(prefix = "anime.metadata")
public interface MetadataConfig {

    /** Tâche de fond active (désactivée dans les tests, qui l'appellent directement). */
    @WithDefault("true")
    boolean enabled();

    @WithDefault("https://graphql.anilist.co")
    String anilistUrl();

    /**
     * Intervalle minimal entre deux appels à AniList. Limite publique : 90 requêtes/min, abaissée à 30/min
     * (« degraded state ») en 2025-2026 ; 2,5 s = 24/min, sous la limite avec de la marge.
     */
    @WithDefault("2500ms")
    Duration minInterval();

    /** Pause quand le fournisseur est indisponible (panne, 403, 5xx) sans délai indiqué. */
    @WithDefault("5m")
    Duration unavailablePause();

    /** Quand il n'y a rien à faire : délai avant de revérifier (un scan réveille la tâche plus tôt). */
    @WithDefault("10m")
    Duration pollInterval();

    /** Erreurs propres à un animé (réponse illisible…) avant de l'abandonner en « non apparié ». */
    @WithDefault("5")
    int maxAttempts();
}
