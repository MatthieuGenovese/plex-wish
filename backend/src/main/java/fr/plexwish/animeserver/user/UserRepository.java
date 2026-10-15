package fr.plexwish.animeserver.user;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Comptes utilisateurs (Panache). */
@ApplicationScoped
public class UserRepository implements PanacheRepository<User> {

    /** Connexion par username ou email, sans tenir compte de la casse. */
    public Optional<User> findByLogin(String login) {
        String l = login.trim().toLowerCase(Locale.ROOT);
        return find("lower(username) = ?1 or lower(email) = ?1", l).firstResultOptional();
    }

    public boolean usernameTaken(String username) {
        return count("lower(username) = ?1", username.toLowerCase(Locale.ROOT)) > 0;
    }

    public boolean emailTaken(String email) {
        return count("lower(email) = ?1", email.toLowerCase(Locale.ROOT)) > 0;
    }

    public long countAdmins() {
        return count("role", Role.ADMIN);
    }

    public List<User> listByUsername() {
        return list("order by lower(username)");
    }
}
