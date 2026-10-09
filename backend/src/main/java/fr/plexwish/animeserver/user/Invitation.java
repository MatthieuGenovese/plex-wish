package fr.plexwish.animeserver.user;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Optional;

/** Lien d'invitation ou de réinitialisation (D1.4). Seul le SHA-256 du jeton est stocké. */
@Entity
@Table(name = "invitation")
public class Invitation extends PanacheEntityBase {

    public enum Purpose {
        /** Premier mot de passe d'un compte créé par l'admin. */
        INVITE,
        /** Mot de passe oublié : nouveau mot de passe, toutes les sessions fermées. */
        RESET
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = 64)
    public String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    public Purpose purpose;

    @Column(name = "created_by", length = 50)
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "used_at")
    public Instant usedAt;

    @Column(name = "revoked_at")
    public Instant revokedAt;

    public boolean usable(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    /** Lien encore valable d'un utilisateur (au plus un : en créer un nouveau révoque l'ancien). */
    public static Optional<Invitation> pendingFor(Long userId) {
        return find("userId = ?1 and usedAt is null and revokedAt is null and expiresAt > ?2 order by createdAt desc",
                userId, Instant.now()).firstResultOptional();
    }
}
