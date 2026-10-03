package fr.plexwish.animeserver.auth;

import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.user.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Vérification d'un login, commune au navigateur et à l'app native : anti brute force (429 avec le délai à attendre),
 * réponse neutre si l'identifiant n'existe pas, compte désactivé refusé. Jamais de mot de passe dans les logs.
 */
@ApplicationScoped
public class LoginService {

    private static final Logger LOG = Logger.getLogger(LoginService.class);

    @Inject
    PasswordService passwords;
    @Inject
    LoginAttemptLimiter limiter;

    /** L'utilisateur si les identifiants sont bons ; sinon {@link ApiException} 429 ou 401. */
    public User authenticate(String rawLogin, String password, String ip) {
        String login = rawLogin.trim();
        if (limiter.isBlocked(ip, login)) {
            LOG.warnf("Connexion bloquée (trop d'échecs) : identifiant '%s' depuis %s", login, ip);
            long minutes = Math.max(1, (limiter.blockedForMillis(ip, login) + 59_999) / 60_000);
            throw new ApiException(429, "TOO_MANY_ATTEMPTS", "Trop de tentatives de connexion. Réessayez dans "
                    + minutes + (minutes > 1 ? " minutes." : " minute."));
        }
        User user = checkCredentials(login, password);
        if (user == null) {
            limiter.recordFailure(ip, login);
            LOG.infof("Échec de connexion : identifiant '%s' depuis %s", login, ip);
            throw new ApiException(401, "INVALID_CREDENTIALS", "Identifiant ou mot de passe incorrect");
        }
        limiter.recordSuccess(ip, login);
        LOG.infof("Connexion de '%s' depuis %s", user.username, ip);
        return user;
    }

    /** Renvoie l'utilisateur si identifiants valides ET compte actif ; sinon null (réponse neutre). */
    User checkCredentials(String login, String password) {
        User user = User.findByLogin(login).orElse(null);
        if (user == null) {
            passwords.burnTime(password); // même durée que si le compte existait
            return null;
        }
        boolean ok = passwords.matches(password, user.passwordHash);
        return ok && user.enabled ? user : null;
    }
}
