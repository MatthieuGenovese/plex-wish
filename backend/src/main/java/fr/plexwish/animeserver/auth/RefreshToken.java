package fr.plexwish.animeserver.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Refresh token : seul son SHA-256 est stocké, jamais la valeur envoyée au client. */
@Entity
@Table(name = "refresh_token")
public class RefreshToken extends PanacheEntityBase {

    public enum RevokedReason {
        ROTATED, LOGOUT, USER_DISABLED, REUSE_DETECTED, PASSWORD_RESET
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = 64)
    public String tokenHash;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "revoked_at")
    public Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 20)
    public RevokedReason revokedReason;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "last_used_at")
    public Instant lastUsedAt;

    /** WEB (cookie) ou ANDROID (corps de la réponse), ARCHITECTURE §5.1.1. */
    @Column(name = "client", nullable = false, length = 10)
    public String client = "WEB";

    @Column(name = "device", length = 100)
    public String device;
}
