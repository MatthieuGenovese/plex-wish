package fr.plexwish.animeserver.auth;

import fr.plexwish.animeserver.auth.RefreshToken.RevokedReason;
import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.user.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Changement de mot de passe par l'utilisateur lui-même (S4, ARCHITECTURE §24.5). Mêmes règles que la
 * réinitialisation par l'admin (10 caractères au moins, 72 octets au plus, bcrypt) ; le mot de passe actuel est
 * exigé et ses erreurs comptent dans l'anti brute force de la connexion (couple IP + identifiant) ; toutes les
 * autres sessions sont fermées (refresh tokens révoqués), la session courante est gardée.
 */
@ApplicationScoped
public class PasswordChangeService {

    private static final Logger LOG = Logger.getLogger(PasswordChangeService.class);

    @Inject
    PasswordService passwords;
    @Inject
    LoginAttemptLimiter limiter;

    /**
     * @param keepRefreshToken refresh token de la session courante (cookie du navigateur, ou corps pour l'app) :
     *                         gardé s'il est valide et appartient à l'utilisateur ; sinon toutes les sessions sont fermées
     * @return nombre de sessions fermées
     */
    @Transactional
    public int change(long userId, String currentPassword, String newPassword, String keepRefreshToken, String ip) {
        User user = User.findById(userId);
        if (user == null || !user.enabled) {
            throw new ApiException(401, "UNAUTHORIZED", "Authentification requise");
        }
        if (limiter.isBlocked(ip, user.username)) {
            long minutes = Math.max(1, (limiter.blockedForMillis(ip, user.username) + 59_999) / 60_000);
            LOG.warnf("Changement de mot de passe bloqué (trop d'échecs) : '%s' depuis %s", user.username, ip);
            throw new ApiException(429, "TOO_MANY_ATTEMPTS", "Trop de tentatives. Réessayez dans "
                    + minutes + (minutes > 1 ? " minutes." : " minute."));
        }
        if (!passwords.matches(currentPassword, user.passwordHash)) {
            limiter.recordFailure(ip, user.username);
            LOG.infof("Changement de mot de passe refusé (mot de passe actuel incorrect) : '%s' depuis %s", user.username, ip);
            // 400 et non 401 : un 401 ferait rafraîchir la session et rejouer la requête par les clients.
            throw new ApiException(400, "WRONG_PASSWORD", "Le mot de passe actuel est incorrect");
        }
        if (passwords.matches(newPassword, user.passwordHash)) {
            throw new ApiException(400, "SAME_PASSWORD", "Le nouveau mot de passe doit être différent de l'actuel");
        }
        user.passwordHash = passwords.hash(newPassword); // valide aussi la longueur (400 WEAK_PASSWORD / PASSWORD_TOO_LONG)
        limiter.recordSuccess(ip, user.username);
        String keep = keepRefreshToken == null || keepRefreshToken.isBlank() ? null : RefreshTokenService.hash(keepRefreshToken);
        boolean kept = keep != null && RefreshToken.count("tokenHash = ?1 and userId = ?2 and revokedAt is null and expiresAt > ?3",
                keep, user.id, Instant.now()) > 0;
        int closed = kept
                ? RefreshToken.update("revokedAt = ?1, revokedReason = ?2 where userId = ?3 and revokedAt is null and tokenHash <> ?4",
                        Instant.now(), RevokedReason.PASSWORD_RESET, user.id, keep)
                : RefreshToken.update("revokedAt = ?1, revokedReason = ?2 where userId = ?3 and revokedAt is null",
                        Instant.now(), RevokedReason.PASSWORD_RESET, user.id);
        LOG.infof("Mot de passe de '%s' changé par l'utilisateur : %d autre(s) session(s) fermée(s)%s", user.username, closed,
                kept ? "" : " (session courante non reconnue : fermée aussi)");
        return closed;
    }
}
