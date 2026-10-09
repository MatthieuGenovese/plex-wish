package fr.plexwish.animeserver.user;

import java.time.Instant;

/**
 * Vue publique d'un utilisateur : jamais de hash de mot de passe. {@code passwordSet} : faux pour un compte invité
 * qui n'a pas encore choisi son mot de passe ; {@code invitationExpiresAt} : lien en attente (D1.4), sinon null.
 */
public record UserDto(Long id, String username, String email, Role role, boolean enabled, Instant createdAt,
                      boolean passwordSet, Instant invitationExpiresAt) {

    public static UserDto of(User u) {
        return new UserDto(u.id, u.username, u.email, u.role, u.enabled, u.createdAt, u.passwordHash != null, null);
    }

    /** Pour l'administration : avec le lien en attente (requête en base). */
    public static UserDto withInvitation(User u) {
        return new UserDto(u.id, u.username, u.email, u.role, u.enabled, u.createdAt, u.passwordHash != null,
                Invitation.pendingFor(u.id).map(i -> i.expiresAt).orElse(null));
    }
}
