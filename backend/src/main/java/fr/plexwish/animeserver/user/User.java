package fr.plexwish.animeserver.user;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Table {@code app_user} ("user" est réservé en PostgreSQL). */
@Entity
@Table(name = "app_user")
public class User extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 50)
    public String username;

    @Column(length = 255)
    public String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    public String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    public Role role;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Connexion par username ou email, sans tenir compte de la casse. */
    public static Optional<User> findByLogin(String login) {
        String l = login.trim().toLowerCase(Locale.ROOT);
        return find("lower(username) = ?1 or lower(email) = ?1", l).firstResultOptional();
    }

    public static boolean usernameTaken(String username) {
        return count("lower(username) = ?1", username.toLowerCase(Locale.ROOT)) > 0;
    }

    public static boolean emailTaken(String email) {
        return count("lower(email) = ?1", email.toLowerCase(Locale.ROOT)) > 0;
    }

    public static long countAdmins() {
        return count("role", Role.ADMIN);
    }

    public static List<User> listByUsername() {
        return list("order by lower(username)");
    }
}
