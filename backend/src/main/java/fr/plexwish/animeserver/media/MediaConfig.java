package fr.plexwish.animeserver.media;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

/** Traitement média (phase 9, ARCHITECTURE §22), préfixe {@code anime.media.}. */
@ConfigMapping(prefix = "anime.media")
public interface MediaConfig {

    /** Analyse ffprobe en tâche de fond (MEDIA_PROBE_ENABLED=false : arrêtée ; l'analyse déjà faite reste). */
    @WithDefault("true")
    boolean probeEnabled();

    /** Exécutables (dans l'image : /usr/local/bin). Jamais une valeur venue d'un client. */
    @WithDefault("ffprobe")
    String ffprobePath();

    @WithDefault("ffmpeg")
    String ffmpegPath();

    /** Délai maximal d'une analyse (en-têtes seulement : quelques dixièmes de seconde en temps normal). */
    @WithDefault("60s")
    Duration probeTimeout();

    /** Délai maximal d'un remux de test (lecture de tout le fichier). */
    @WithDefault("20m")
    Duration remuxTestTimeout();

    /** Priorité basse (nice 19, ionice « idle ») quand ces outils existent dans le conteneur. */
    @WithDefault("true")
    boolean lowPriority();

    /** Attente quand il n'y a rien à analyser (réveil anticipé à la fin d'un scan). */
    @WithDefault("10m")
    Duration pollInterval();

    // --- Remux à la demande (phase 9.2, ARCHITECTURE §23) ---------------------------------------------------------

    /** Fil d'exécution des remux (désactivé dans les tests, qui appellent le service directement). */
    @WithDefault("true")
    boolean remuxWorkerEnabled();

    /** Dossier du cache des copies remuxées (volume en écriture, hors /media). */
    @WithDefault("/data/remux-cache")
    String remuxCachePath();

    /** Taille maximale du cache (Go décimaux) ; au-delà, les copies les moins récemment lues sont effacées. */
    @WithDefault("50")
    double remuxCacheMaxGb();

    /** Espace disque à toujours laisser libre sur le volume du cache. */
    @WithDefault("2")
    double remuxCacheReserveGb();

    /** Délai maximal d'un remux (un épisode de 200 Mo prend quelques secondes à une minute). */
    @WithDefault("30m")
    java.time.Duration remuxTimeout();

    /**
     * Une copie lue depuis moins longtemps que ce délai est « en cours de lecture » : jamais effacée par la purge
     * (pause longue comprise).
     */
    @WithDefault("3h")
    java.time.Duration remuxInUse();
}
