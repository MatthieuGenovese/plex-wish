package fr.plexwish.animeserver.user;

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

/** Table {@code app_user} ("user" est réservé en PostgreSQL). */
@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 50)
    public String username;

    @Column(length = 255)
    public String email;

    /** Null : compte invité dont le lien d'invitation n'a pas encore servi (aucune connexion possible). */
    @Column(name = "password_hash", length = 100)
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
}
