package fr.plexwish.animeserver.library;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Configuration de la bibliothèque (préfixe {@code library.}). */
@ConfigMapping(prefix = "anime.library")
public interface LibraryConfig {

    /** Racine des médias, montée en lecture seule (/media dans le conteneur). */
    @WithDefault("/media")
    String mediaRoot();

    /** Vidéos traitées par transaction pendant un scan. */
    @WithDefault("500")
    int batchSize();
}
