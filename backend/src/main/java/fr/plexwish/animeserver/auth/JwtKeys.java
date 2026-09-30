package fr.plexwish.animeserver.auth;

import io.smallrye.jwt.algorithm.SignatureAlgorithm;
import io.smallrye.jwt.auth.principal.JWTAuthContextInfo;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Clé HS256 unique, construite à partir de JWT_SECRET, qui sert à signer ET à vérifier (ARCHITECTURE §5.2).
 * L'extension Quarkus ne sait pas lire un secret HS256 depuis une variable d'environnement brute :
 * on lui fournit donc directement la configuration de vérification.
 */
@ApplicationScoped
public class JwtKeys {

    private final SecretKey key;

    public JwtKeys(AuthConfig config) {
        // Si le secret manque, SecurityStartup bloque le démarrage en prod ; ici on évite juste un NPE.
        String secret = config.jwtSecret().orElse("");
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public SecretKey key() {
        return key;
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    JWTAuthContextInfo verificationConfig() {
        JWTAuthContextInfo info = new JWTAuthContextInfo(key, AccessTokenService.ISSUER);
        info.setSignatureAlgorithm(Set.of(SignatureAlgorithm.HS256));
        return info;
    }
}
