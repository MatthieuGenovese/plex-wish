package fr.plexwish.animeserver.webplay;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Path;
import java.util.List;

/**
 * Étapes de la préparation web après l'analyse (docs/WEB-PLAYER.md §4.2). 10.2.1 : rien n'est encore produit (seuls les
 * fichiers lisibles tels quels passent) ; 10.2.2 : sous-titres, polices jointes et copie HLS sans ré-encodage.
 */
@ApplicationScoped
public class WebPrepSteps {

    /** Avancement : {@code phase} (null = inchangée) et fraction 0..1 de l'ensemble. */
    @FunctionalInterface
    public interface Progress {
        void update(String phase, double value);
    }

    /** Produit les fichiers dans {@code part} et renvoie le manifeste complété. */
    public WebManifest run(Path source, Path part, WebManifest m, Progress progress) throws WebPrepService.StepFailure, InterruptedException {
        return m.withProduced(m.subtitles(), List.of(), false);
    }

    /** La copie HLS en cours d'écriture est-elle déjà lisible (premiers segments écrits) ? */
    public boolean playableEarly(Path part, WebManifest m) {
        return false;
    }
}
