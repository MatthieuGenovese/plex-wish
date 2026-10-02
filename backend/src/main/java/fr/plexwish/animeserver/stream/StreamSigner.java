package fr.plexwish.animeserver.stream;

import fr.plexwish.animeserver.auth.AuthConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * URL de lecture signée (ARCHITECTURE §6) : {@code /api/stream/{fichier}?u={utilisateur}&exp={epoch}&sig={hmac}}.
 * La signature HMAC-SHA256 ({@code STREAM_SIGNING_SECRET}, distinct du secret JWT) lie l'URL à un fichier, à un
 * utilisateur et à une date d'expiration : changer l'un des trois invalide la signature.
 */
@ApplicationScoped
public class StreamSigner {

    /** Préfixe de version : permettra de changer le format sans accepter d'anciennes signatures par erreur. */
    private static final String VERSION = "v1";

    private final byte[] key;
    private final java.time.Duration lifetime;
    private final Clock clock;

    @Inject
    public StreamSigner(AuthConfig config) {
        this(config.streamSigningSecret().orElseThrow(
                () -> new IllegalStateException("STREAM_SIGNING_SECRET absent")), config.streamUrlLifetime(), Clock.systemUTC());
    }

    StreamSigner(String secret, java.time.Duration lifetime, Clock clock) {
        this.key = secret.getBytes(StandardCharsets.UTF_8);
        this.lifetime = lifetime;
        this.clock = clock;
    }

    public record SignedUrl(String url, Instant expiresAt) {
    }

    /** Résultat de la vérification, du plus grave au plus bénin. */
    public enum Check { INVALID, EXPIRED, VALID }

    public SignedUrl sign(long mediaFileId, long userId) {
        long exp = clock.instant().plus(lifetime).getEpochSecond();
        return new SignedUrl("/api/stream/" + mediaFileId + "?u=" + userId + "&exp=" + exp + "&sig="
                + signature(mediaFileId, userId, exp), Instant.ofEpochSecond(exp));
    }

    /** Signature d'abord (comparaison à temps constant), expiration ensuite. */
    public Check check(long mediaFileId, long userId, long exp, String sig) {
        if (sig == null || sig.isEmpty()) {
            return Check.INVALID;
        }
        byte[] expected = signature(mediaFileId, userId, exp).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.US_ASCII))) {
            return Check.INVALID;
        }
        return clock.instant().getEpochSecond() >= exp ? Check.EXPIRED : Check.VALID;
    }

    public String signature(long mediaFileId, long userId, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] raw = mac.doFinal((VERSION + ":" + mediaFileId + ":" + userId + ":" + exp).getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
