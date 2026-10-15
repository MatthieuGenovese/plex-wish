package fr.plexwish.animeserver.auth;

import fr.plexwish.animeserver.auth.RefreshToken.RevokedReason;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;

/** Refresh tokens (Panache). Les jetons sont désignés par leur SHA-256, jamais par leur valeur. */
@ApplicationScoped
public class RefreshTokenRepository implements PanacheRepository<RefreshToken> {

    public RefreshToken findByHash(String tokenHash) {
        return find("tokenHash", tokenHash).firstResult();
    }

    /** Vrai si ce jeton est encore valable et appartient à l'utilisateur. */
    public boolean isActive(String tokenHash, Long userId, Instant now) {
        return count("tokenHash = ?1 and userId = ?2 and revokedAt is null and expiresAt > ?3", tokenHash, userId, now) > 0;
    }

    /** Rotation atomique : 1 si cet appel a révoqué le jeton, 0 si une autre requête l'a fait avant. */
    public int markRotated(Long id, Instant now) {
        return update("revokedAt = ?1, revokedReason = ?2, lastUsedAt = ?1 where id = ?3 and revokedAt is null",
                now, RevokedReason.ROTATED, id);
    }

    public int revokeByHash(String tokenHash, RevokedReason reason, Instant now) {
        return update("revokedAt = ?1, revokedReason = ?2 where tokenHash = ?3 and revokedAt is null", now, reason, tokenHash);
    }

    public int revokeAllOf(Long userId, RevokedReason reason, Instant now) {
        return update("revokedAt = ?1, revokedReason = ?2 where userId = ?3 and revokedAt is null", now, reason, userId);
    }

    /** Toutes les sessions de l'utilisateur sauf celle de ce jeton. */
    public int revokeAllOfExcept(Long userId, String keptHash, RevokedReason reason, Instant now) {
        return update("revokedAt = ?1, revokedReason = ?2 where userId = ?3 and revokedAt is null and tokenHash <> ?4",
                now, reason, userId, keptHash);
    }
}
