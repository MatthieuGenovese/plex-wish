package fr.plexwish.animeserver.user;

import fr.plexwish.animeserver.auth.PasswordService;
import fr.plexwish.animeserver.auth.RefreshToken.RevokedReason;
import fr.plexwish.animeserver.auth.RefreshTokenService;
import fr.plexwish.animeserver.common.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/** Gestion des comptes par l'admin. Pas d'inscription publique. */
@ApplicationScoped
public class UserService {

    private static final Logger LOG = Logger.getLogger(UserService.class);

    @Inject
    PasswordService passwords;
    @Inject
    RefreshTokenService refreshTokens;
    @Inject
    InvitationService invitations;

    @Transactional
    public User create(String username, String email, String password, Role role) {
        String name = username.trim();
        String mail = email == null || email.isBlank() ? null : email.trim();
        if (User.usernameTaken(name)) {
            throw new ApiException(409, "USERNAME_TAKEN", "Ce nom d'utilisateur existe déjà");
        }
        if (mail != null && User.emailTaken(mail)) {
            throw new ApiException(409, "EMAIL_TAKEN", "Cet email est déjà utilisé");
        }
        User user = new User();
        user.username = name;
        user.email = mail;
        // Sans mot de passe : compte invité (D1.4), qui choisira le sien par le lien d'invitation.
        user.passwordHash = password == null ? null : passwords.hash(password);
        user.role = role;
        user.enabled = true;
        user.persist();
        LOG.infof("Utilisateur créé : '%s' (%s)", user.username, role);
        return user;
    }

    /**
     * Modification par un admin. Un admin ne peut ni se désactiver ni se retirer le rôle ADMIN
     * (il se verrouillerait dehors). Désactivation et changement de mot de passe coupent les sessions.
     */
    @Transactional
    public User update(Long id, Boolean enabled, Role role, String newPassword, Long actingUserId) {
        User user = User.findById(id);
        if (user == null) {
            throw new ApiException(404, "USER_NOT_FOUND", "Utilisateur introuvable");
        }
        boolean self = user.id.equals(actingUserId);
        if (self && (Boolean.FALSE.equals(enabled) || (role != null && role != Role.ADMIN))) {
            throw new ApiException(409, "SELF_LOCKOUT", "Un admin ne peut pas se désactiver ni retirer son propre rôle ADMIN");
        }
        if (role != null && role != user.role) {
            LOG.infof("Rôle de '%s' : %s → %s", user.username, user.role, role);
            user.role = role;
        }
        if (enabled != null && enabled != user.enabled) {
            user.enabled = enabled;
            LOG.infof("Utilisateur '%s' %s", user.username, enabled ? "réactivé" : "désactivé");
            if (!enabled) {
                refreshTokens.revokeAll(user.id, RevokedReason.USER_DISABLED);
                invitations.revokeAll(user.id);
            }
        }
        if (newPassword != null) {
            user.passwordHash = passwords.hash(newPassword);
            refreshTokens.revokeAll(user.id, RevokedReason.PASSWORD_RESET);
            LOG.infof("Mot de passe de '%s' réinitialisé", user.username);
        }
        return user;
    }
}
