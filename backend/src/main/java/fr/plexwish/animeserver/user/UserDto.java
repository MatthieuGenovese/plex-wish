package fr.plexwish.animeserver.user;

import java.time.Instant;

/** Vue publique d'un utilisateur : jamais de hash de mot de passe. */
public record UserDto(Long id, String username, String email, Role role, boolean enabled, Instant createdAt) {

    public static UserDto of(User u) {
        return new UserDto(u.id, u.username, u.email, u.role, u.enabled, u.createdAt);
    }
}
