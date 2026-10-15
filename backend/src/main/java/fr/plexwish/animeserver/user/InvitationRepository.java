package fr.plexwish.animeserver.user;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Optional;

/** Liens d'invitation et de réinitialisation (Panache). */
@ApplicationScoped
public class InvitationRepository implements PanacheRepository<Invitation> {

    /** Lien encore valable d'un utilisateur (au plus un : en créer un nouveau révoque l'ancien). */
    public Optional<Invitation> pendingFor(Long userId) {
        return find("userId = ?1 and usedAt is null and revokedAt is null and expiresAt > ?2 order by createdAt desc",
                userId, Instant.now()).firstResultOptional();
    }

    public Invitation findByTokenHash(String tokenHash) {
        return find("tokenHash", tokenHash).firstResult();
    }

    /** Révoque les liens en attente de l'utilisateur ; nombre de liens révoqués. */
    public int revokePending(Long userId, Instant now) {
        return update("revokedAt = ?1 where userId = ?2 and usedAt is null and revokedAt is null", now, userId);
    }

    /** Consommation atomique : 1 si cet appel a consommé le lien, 0 s'il était déjà utilisé ou révoqué. */
    public int claim(Long id, Instant now) {
        return update("usedAt = ?1 where id = ?2 and usedAt is null and revokedAt is null", now, id);
    }
}
