package fr.plexwish.animeserver.auth;

import fr.plexwish.animeserver.common.ApiException;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;

/** bcrypt coût 12 (§5.3). Les mots de passe ne sont jamais loggés. */
@ApplicationScoped
public class PasswordService {

    public static final int MIN_LENGTH = 10;
    /** bcrypt ignore tout ce qui dépasse 72 octets : on refuse plutôt que tronquer en silence. */
    public static final int MAX_BYTES = 72;
    private static final int COST = 12;

    /** Hash d'un mot de passe aléatoire : sert à garder un temps de réponse constant quand le compte n'existe pas. */
    private final String dummyHash = BcryptUtil.bcryptHash("not-a-real-password-" + System.nanoTime(), COST);

    public String hash(String password) {
        validate(password);
        return BcryptUtil.bcryptHash(password, COST);
    }

    public boolean matches(String password, String hash) {
        return BcryptUtil.matches(password, hash);
    }

    /** Même coût qu'une vraie vérification, résultat toujours faux. */
    public void burnTime(String password) {
        BcryptUtil.matches(password, dummyHash);
    }

    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new ApiException(400, "WEAK_PASSWORD", "Le mot de passe doit faire au moins " + MIN_LENGTH + " caractères");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new ApiException(400, "PASSWORD_TOO_LONG", "Le mot de passe ne doit pas dépasser " + MAX_BYTES + " octets");
        }
    }
}
