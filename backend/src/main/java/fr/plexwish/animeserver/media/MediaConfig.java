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

    // --- Préparation pour le navigateur (phase 10, docs/WEB-PLAYER.md) -------------------------------------------

    /** Fil d'exécution de la préparation web (désactivé dans les tests, qui appellent le service directement). */
    @WithDefault("true")
    boolean webWorkerEnabled();

    /** Dossier du cache web dans le conteneur (volume en écriture, hors /media). */
    @WithDefault("/data/web")
    String webCachePath();

    /** Le même dossier vu du NAS (WEB_CACHE_PATH de nas.env), seulement pour l'afficher à l'admin. */
    java.util.Optional<String> webCacheHostPath();

    /** Plafond du cache web (Go décimaux) ; absent : 15 % du volume, 200 Go au plus (réglable dans l'interface). */
    java.util.Optional<Double> webCacheMaxGb();

    /** Espace disque à toujours laisser libre sur le volume du cache web. */
    @WithDefault("10")
    double webCacheReserveGb();

    /** Délai maximal d'une préparation (analyse + sous-titres + copie HLS sans ré-encodage). */
    @WithDefault("60m")
    java.time.Duration webPrepTimeout();

    /**
     * Délai maximal d'une conversion pour le navigateur (temps de calcul : une conversion suspendue pendant les
     * lectures ne compte pas). Un épisode de 24 min 1080p prend ~30 min sur le DS923+ (estimation, §6).
     */
    @WithDefault("4h")
    java.time.Duration webConvertTimeout();

    /** Fenêtre de nuit du travail préventif (heures locales, variable TZ) : début inclus, fin exclue. */
    @WithDefault("1")
    int webNightStart();

    @WithDefault("7")
    int webNightEnd();

    /** Fils de calcul de x264 (le R1600 en a 4 : 2 restent pour servir les lectures). */
    @WithDefault("2")
    int webConvertThreads();

    /** Une copie web lue depuis moins longtemps que ce délai n'est jamais effacée par la purge. */
    @WithDefault("3h")
    java.time.Duration webInUse();

    /**
     * Copies sans conversion (remux HLS) : effacées quand personne ne les a lues depuis ce délai (refaites en une minute
     * environ à la demande). Les conversions, chères à refaire, suivent seulement le plafond du cache.
     */
    @WithDefault("48h")
    java.time.Duration webCopyKeep();

    /** Polices jointes d'un épisode : taille maximale d'une police (Mo) et du total (Mo). */
    @WithDefault("30")
    int webFontMaxMb();

    @WithDefault("80")
    int webFontsTotalMaxMb();
}
