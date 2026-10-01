package fr.plexwish.animeserver.auth;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.Optional;

/** Configuration de l'authentification (préfixe {@code auth.}, voir application.properties). */
@ConfigMapping(prefix = "anime.auth")
public interface AuthConfig {

    Optional<String> jwtSecret();

    Optional<String> streamSigningSecret();

    /** Origine publique (https://anime.mondomaine), utilisée pour contrôler le header Origin. */
    Optional<String> publicUrl();

    @WithDefault("true")
    boolean cookieSecure();

    /** Fenêtre pendant laquelle un refresh token tout juste remplacé est encore toléré (§5.1). */
    @WithDefault("20")
    @Min(10)
    @Max(30)
    int refreshReuseGraceSeconds();

    @WithDefault("900")
    int accessTokenSeconds();

    @WithDefault("30")
    int refreshTokenDays();

    InitialAdmin initialAdmin();

    LoginLimits loginLimits();

    interface InitialAdmin {
        Optional<String> username();

        Optional<String> password();
    }

    /** Anti brute force (§5.4) : compteurs en mémoire. */
    interface LoginLimits {
        /** Échecs tolérés pour un couple (IP, identifiant) sur la fenêtre. */
        @WithDefault("5")
        int maxPerIpAndLogin();

        /** Échecs tolérés pour une IP, tous identifiants confondus. */
        @WithDefault("20")
        int maxPerIp();

        @WithDefault("15")
        int windowMinutes();

        @WithDefault("15")
        int blockMinutes();
    }
}
