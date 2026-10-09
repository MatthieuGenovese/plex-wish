package fr.plexwish.animeserver.setup;

import fr.plexwish.animeserver.user.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Installation terminée ou non (fin de l'assistant de premier lancement). Tant qu'elle ne l'est pas, le site ne sert
 * que l'assistant, depuis le réseau local ({@link EntryFilter}).
 */
@ApplicationScoped
public class SetupState {

    public static final String COMPLETED = "setup.completed_at";
    private static final Logger LOG = Logger.getLogger(SetupState.class);

    @Inject
    AppSettings settings;

    private volatile Boolean completed;

    public boolean completed() {
        Boolean c = completed;
        if (c == null) {
            c = settings.get(COMPLETED).isPresent();
            completed = c;
        }
        return c;
    }

    public void markCompleted(String reason) {
        if (settings.get(COMPLETED).isEmpty()) {
            settings.put(COMPLETED, Instant.now().toString());
            LOG.infof("Installation terminée (%s) : le site est ouvert", reason);
        }
        completed = true;
    }

    /** Tests : relit l'état en base. */
    public void reload() {
        settings.forget();
        completed = null;
    }

    /**
     * Mise à jour d'une installation d'avant l'assistant : un administrateur existe déjà, l'installation est considérée
     * comme terminée.
     */
    void onStart(@Observes StartupEvent event) {
        if (!completed() && QuarkusTransaction.requiringNew().call(User::countAdmins) > 0) {
            markCompleted("un administrateur existe déjà");
        }
        if (!completed()) {
            LOG.warn("Installation non terminée : ouvrir l'adresse du NAS sur le réseau local (port de l'assistant, 8080 par défaut)"
                    + " pour l'assistant de premier lancement. D'ici là, le site public répond « installation en cours ».");
        }
    }
}
