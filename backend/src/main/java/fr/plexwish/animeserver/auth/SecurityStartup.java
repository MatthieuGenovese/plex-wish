package fr.plexwish.animeserver.auth;

import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.user.Role;
import fr.plexwish.animeserver.user.User;
import fr.plexwish.animeserver.user.UserService;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Au démarrage : refuse une configuration dangereuse en prod (secrets absents ou trop courts,
 * PUBLIC_URL invalide) puis crée l'admin initial s'il n'existe aucun admin (§5.5).
 */
@ApplicationScoped
public class SecurityStartup {

    private static final Logger LOG = Logger.getLogger(SecurityStartup.class);
    static final int MIN_SECRET_LENGTH = 32;

    @Inject
    AuthConfig config;
    @Inject
    UserService users;

    void onStart(@Observes StartupEvent event) {
        List<String> problems = configurationProblems(config.jwtSecret(), config.streamSigningSecret(), config.publicUrl());
        if (!problems.isEmpty()) {
            String message = "Configuration de sécurité invalide :\n - " + String.join("\n - ", problems);
            if (LaunchMode.current() == LaunchMode.NORMAL) {
                throw new IllegalStateException(message); // prod : on refuse de démarrer
            }
            LOG.warn(message);
        }
        createInitialAdmin();
    }

    /** Vérifications pures (testées unitairement). Les messages ne contiennent jamais les secrets. */
    static List<String> configurationProblems(Optional<String> jwtSecret, Optional<String> streamSecret, Optional<String> publicUrl) {
        List<String> problems = new ArrayList<>();
        checkSecret("JWT_SECRET", jwtSecret, problems);
        checkSecret("STREAM_SIGNING_SECRET", streamSecret, problems);
        if (jwtSecret.isPresent() && jwtSecret.equals(streamSecret)) {
            problems.add("JWT_SECRET et STREAM_SIGNING_SECRET doivent être différents");
        }
        if (publicUrl.isEmpty()) {
            problems.add("PUBLIC_URL absent (ex. https://anime.mondomaine) : nécessaire au contrôle de l'origine des requêtes");
        } else {
            Optional<String> origin = OriginCheck.originOf(publicUrl.get());
            if (origin.isEmpty()) {
                problems.add("PUBLIC_URL invalide : attendu http(s)://hôte[:port], reçu '" + publicUrl.get() + "'");
            } else if (!origin.get().equals(publicUrl.get())) {
                // Le navigateur envoie l'origine sous cette forme exacte : toute variante (chemin, "/" final,
                // majuscules, port par défaut) ferait échouer la comparaison CORS et bloquerait la connexion.
                problems.add("PUBLIC_URL doit être l'origine exacte, sans chemin ni \"/\" final : mettre '"
                        + origin.get() + "' au lieu de '" + publicUrl.get() + "'");
            }
        }
        return problems;
    }

    private static void checkSecret(String name, Optional<String> value, List<String> problems) {
        if (value.isEmpty()) {
            problems.add(name + " absent");
        } else if (value.get().length() < MIN_SECRET_LENGTH) {
            problems.add(name + " trop court (" + MIN_SECRET_LENGTH + " caractères minimum)");
        }
    }

    void createInitialAdmin() {
        if (User.countAdmins() > 0) {
            return; // les variables INITIAL_ADMIN_* sont ignorées une fois un admin créé
        }
        Optional<String> username = config.initialAdmin().username();
        Optional<String> password = config.initialAdmin().password();
        if (username.isEmpty() || password.isEmpty()) {
            LOG.error("Aucun administrateur : définir INITIAL_ADMIN_USERNAME et INITIAL_ADMIN_PASSWORD puis redémarrer");
            return;
        }
        try {
            users.create(username.get(), null, password.get(), Role.ADMIN);
            LOG.infof("Administrateur initial '%s' créé. INITIAL_ADMIN_PASSWORD peut être retiré du .env", username.get());
        } catch (ApiException e) {
            LOG.errorf("Administrateur initial non créé : %s", e.getMessage());
        }
    }

}
