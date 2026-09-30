package fr.plexwish.animeserver.auth;

import fr.plexwish.animeserver.user.User;
import io.smallrye.jwt.algorithm.SignatureAlgorithm;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/** Access token JWT HS256, 15 min, gardé en mémoire par le client (§5.1–5.2). */
@ApplicationScoped
public class AccessTokenService {

    public static final String ISSUER = "anime-server";

    @Inject
    AuthConfig config;
    @Inject
    JwtKeys keys;

    public String issue(User user) {
        return Jwt.issuer(ISSUER)
                .subject(String.valueOf(user.id))
                .upn(user.username)
                .groups(Set.of(user.role.name()))
                .expiresIn(config.accessTokenSeconds())
                .jws().algorithm(SignatureAlgorithm.HS256)
                .sign(keys.key());
    }

    public int lifetimeSeconds() {
        return config.accessTokenSeconds();
    }
}
