package fr.plexwish.animeserver.auth;

import fr.plexwish.animeserver.auth.RefreshToken.RevokedReason;
import fr.plexwish.animeserver.user.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/** Refresh tokens : création, rotation avec fenêtre de tolérance, révocation (ARCHITECTURE §5.1). */
@ApplicationScoped
public class RefreshTokenService {

    private static final Logger LOG = Logger.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Résultat d'un refresh. */
    public sealed interface Outcome permits Rotated, Grace, Rejected {
    }

    /** Nouveau refresh token émis (à poser dans le cookie). */
    public record Rotated(User user, String newToken) implements Outcome {
    }

    /** Token remplacé il y a quelques secondes (requêtes concurrentes) : access token seulement, cookie inchangé. */
    public record Grace(User user) implements Outcome {
    }

    public record Rejected() implements Outcome {
    }

    @Inject
    AuthConfig config;

    /** Crée un refresh token pour l'utilisateur et renvoie sa valeur en clair (jamais stockée). */
    @Transactional
    public String create(User user) {
        return create(user, "WEB", null);
    }

    /** {@code client} : WEB ou ANDROID ; {@code device} : libellé facultatif de l'appareil. */
    @Transactional
    public String create(User user, String client, String device) {
        String token = newToken();
        RefreshToken rt = new RefreshToken();
        rt.client = client;
        rt.device = device;
        rt.userId = user.id;
        rt.tokenHash = hash(token);
        rt.createdAt = Instant.now();
        rt.expiresAt = rt.createdAt.plus(Duration.ofDays(config.refreshTokenDays()));
        rt.persist();
        return token;
    }

    @Transactional
    public Outcome refresh(String token) {
        return refresh(token, false);
    }

    /**
     * {@code rotateOnGrace} (app native) : dans la fenêtre de tolérance, un nouveau refresh token est émis au lieu
     * du seul access token. Cas réel : la réponse d'un refresh est perdue (réseau coupé, app tuée) et l'app
     * réessaie avec l'ancien token ; sans nouveau token, son refresh suivant passerait pour un vol et couperait
     * toutes ses sessions. Le navigateur, lui, a déjà reçu le bon cookie.
     */
    @Transactional
    public Outcome refresh(String token, boolean rotateOnGrace) {
        if (token == null || token.isBlank()) {
            return new Rejected();
        }
        RefreshToken rt = RefreshToken.find("tokenHash", hash(token)).firstResult();
        Instant now = Instant.now();
        if (rt == null || rt.expiresAt.isBefore(now)) {
            return new Rejected();
        }
        User user = User.findById(rt.userId);
        if (user == null || !user.enabled) {
            return new Rejected();
        }
        if (rt.revokedAt != null) {
            Outcome o = reuse(rt, user, now);
            return o instanceof Grace && rotateOnGrace ? new Rotated(user, create(user, rt.client, rt.device)) : o;
        }
        // Révocation atomique : si deux requêtes arrivent en même temps, une seule gagne.
        int updated = RefreshToken.update(
                "revokedAt = ?1, revokedReason = ?2, lastUsedAt = ?1 where id = ?3 and revokedAt is null",
                now, RevokedReason.ROTATED, rt.id);
        if (updated == 0) {
            // L'autre requête vient de le faire tourner.
            return rotateOnGrace ? new Rotated(user, create(user, rt.client, rt.device)) : new Grace(user);
        }
        return new Rotated(user, create(user, rt.client, rt.device));
    }

    private Outcome reuse(RefreshToken rt, User user, Instant now) {
        Instant graceLimit = now.minusSeconds(config.refreshReuseGraceSeconds());
        if (rt.revokedReason == RevokedReason.ROTATED && rt.revokedAt.isAfter(graceLimit)) {
            return new Grace(user);
        }
        // Token révoqué présenté hors de la fenêtre : vol probable. On coupe toutes les sessions.
        int count = revokeAll(user.id, RevokedReason.REUSE_DETECTED);
        LOG.warnf("Réutilisation d'un refresh token révoqué (utilisateur %s) : %d session(s) révoquée(s)",
                user.username, count);
        return new Rejected();
    }

    @Transactional
    public void revoke(String token, RevokedReason reason) {
        if (token == null || token.isBlank()) {
            return;
        }
        RefreshToken.update("revokedAt = ?1, revokedReason = ?2 where tokenHash = ?3 and revokedAt is null",
                Instant.now(), reason, hash(token));
    }

    @Transactional
    public int revokeAll(Long userId, RevokedReason reason) {
        return RefreshToken.update("revokedAt = ?1, revokedReason = ?2 where userId = ?3 and revokedAt is null",
                Instant.now(), reason, userId);
    }

    static String newToken() {
        byte[] bytes = new byte[32]; // 256 bits
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
