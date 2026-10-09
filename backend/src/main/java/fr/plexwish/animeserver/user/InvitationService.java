package fr.plexwish.animeserver.user;

import fr.plexwish.animeserver.auth.AuthConfig;
import fr.plexwish.animeserver.auth.LoginAttemptLimiter;
import fr.plexwish.animeserver.auth.PasswordService;
import fr.plexwish.animeserver.auth.RefreshToken.RevokedReason;
import fr.plexwish.animeserver.auth.RefreshTokenService;
import fr.plexwish.animeserver.common.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Invitations et réinitialisations par lien (D1.4). Le lien {@code <adresse>/invitation#<jeton>} :
 * <ul>
 *     <li>porte le jeton APRÈS le « # » : le navigateur ne l'envoie jamais au serveur dans l'adresse, il n'apparaît donc
 *         dans aucun journal (nginx, Caddy) ; la page le transmet dans le corps d'une requête POST ;</li>
 *     <li>32 octets aléatoires ; seul le SHA-256 est en base (comme les refresh tokens) ;</li>
 *     <li>usage unique, 72 h ; en créer un nouveau révoque l'ancien ; désactiver le compte le révoque aussi ;</li>
 *     <li>les essais de jetons invalides comptent dans l'anti brute force (par IP), comme la connexion.</li>
 * </ul>
 */
@ApplicationScoped
public class InvitationService {

    private static final Logger LOG = Logger.getLogger(InvitationService.class);
    static final Duration LIFETIME = Duration.ofHours(72);
    /** Clé « identifiant » des compteurs anti brute force : tous les essais de liens d'une IP comptent ensemble. */
    static final String LIMITER_KEY = "#invitation";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Link(String url, Instant expiresAt, Invitation.Purpose purpose) {
        @Override
        public String toString() {
            return "Link[***, expiresAt=" + expiresAt + "]";
        }
    }

    public record Preview(String username, Invitation.Purpose purpose, Instant expiresAt) {
    }

    @Inject
    AuthConfig auth;
    @Inject
    PasswordService passwords;
    @Inject
    LoginAttemptLimiter limiter;
    @Inject
    RefreshTokenService refreshTokens;

    /** Nouveau lien pour ce compte (l'ancien, s'il existe, est révoqué). */
    @Transactional
    public Link create(Long userId, String createdBy) {
        User user = User.findById(userId);
        if (user == null) {
            throw new ApiException(404, "USER_NOT_FOUND", "Utilisateur introuvable");
        }
        if (!user.enabled) {
            throw new ApiException(409, "USER_DISABLED", "Compte désactivé : le réactiver avant d'envoyer un lien.");
        }
        revokeAll(userId);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Invitation inv = new Invitation();
        inv.userId = userId;
        inv.tokenHash = RefreshTokenService.hash(token);
        inv.purpose = user.passwordHash == null ? Invitation.Purpose.INVITE : Invitation.Purpose.RESET;
        inv.createdBy = createdBy;
        inv.createdAt = Instant.now();
        inv.expiresAt = inv.createdAt.plus(LIFETIME);
        inv.persist();
        LOG.infof("Lien %s créé pour '%s' par '%s' (valable jusqu'au %s)",
                inv.purpose == Invitation.Purpose.INVITE ? "d'invitation" : "de réinitialisation", user.username, createdBy, inv.expiresAt);
        String base = auth.publicUrl().orElse("").replaceAll("/+$", "");
        return new Link(base + "/invitation#" + token, inv.expiresAt, inv.purpose);
    }

    @Transactional
    public int revokeAll(Long userId) {
        return Invitation.update("revokedAt = ?1 where userId = ?2 and usedAt is null and revokedAt is null", Instant.now(), userId);
    }

    /** Ce que la page affiche avant la saisie : à qui est le lien. Un lien invalide compte comme un essai raté. */
    @Transactional
    public Preview preview(String token, String ip) {
        Invitation inv = usable(token, ip);
        User user = User.findById(inv.userId);
        return new Preview(user.username, inv.purpose, inv.expiresAt);
    }

    /** La personne choisit son mot de passe : lien consommé, sessions existantes fermées. */
    @Transactional
    public String accept(String token, String password, String ip) {
        Invitation inv = usable(token, ip);
        PasswordService.validate(password); // 400 avant de consommer le lien : la personne peut corriger
        // Consommation atomique : une seconde requête simultanée avec le même lien ne passe pas.
        int claimed = Invitation.update("usedAt = ?1 where id = ?2 and usedAt is null and revokedAt is null", Instant.now(), inv.id);
        if (claimed != 1) {
            throw invalid();
        }
        User user = User.findById(inv.userId);
        user.passwordHash = passwords.hash(password);
        refreshTokens.revokeAll(user.id, RevokedReason.PASSWORD_RESET);
        limiter.recordSuccess(ip, LIMITER_KEY);
        LOG.infof("Mot de passe choisi par '%s' (lien %s) depuis %s", user.username,
                inv.purpose == Invitation.Purpose.INVITE ? "d'invitation" : "de réinitialisation", ip);
        return user.username;
    }

    private Invitation usable(String token, String ip) {
        if (limiter.isBlocked(ip, LIMITER_KEY)) {
            long minutes = Math.max(1, (limiter.blockedForMillis(ip, LIMITER_KEY) + 59_999) / 60_000);
            throw new ApiException(429, "TOO_MANY_ATTEMPTS", "Trop d'essais. Réessayez dans " + minutes + (minutes > 1 ? " minutes." : " minute."));
        }
        Invitation inv = token == null || !TOKEN.matcher(token).matches() ? null
                : Invitation.<Invitation>find("tokenHash", RefreshTokenService.hash(token)).firstResult();
        User user = inv == null ? null : User.findById(inv.userId);
        if (inv == null || !inv.usable(Instant.now()) || user == null || !user.enabled) {
            limiter.recordFailure(ip, LIMITER_KEY);
            LOG.infof("Lien d'invitation invalide, utilisé ou expiré, depuis %s", ip); // jamais le jeton
            throw invalid();
        }
        return inv;
    }

    private static ApiException invalid() {
        return new ApiException(410, "INVITATION_INVALID",
                "Ce lien n'est plus valable (déjà utilisé, expiré ou remplacé). Demandez-en un nouveau à l'administrateur.");
    }
}
