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
}
