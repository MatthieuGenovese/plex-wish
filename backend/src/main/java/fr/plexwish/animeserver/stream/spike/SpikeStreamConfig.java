package fr.plexwish.animeserver.stream.spike;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "spike.stream")
public interface SpikeStreamConfig {

    /** {@code DEV_SPIKE_STREAM_ENABLED} : jamais en production. */
    @WithDefault("false")
    boolean enabled();

    /** {@code DEV_MEDIA_PATH} : dossier de vidéos de test. */
    @WithDefault("../dev-media")
    String mediaDir();
}
