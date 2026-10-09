package fr.plexwish.animeserver.setup;

import fr.plexwish.animeserver.auth.AuthConfig;
import fr.plexwish.animeserver.common.ClientIp;
import fr.plexwish.animeserver.common.ErrorResponse;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

/**
 * Qui a le droit de quoi selon la porte d'entrée et l'état de l'installation (D1.3). Avant toute authentification :
 * <ul>
 *     <li>installation non terminée : seul l'assistant ({@code /api/setup/…}) répond, et seulement depuis le réseau
 *         local (porte « lan » ou « local », adresse privée) ; tout le reste répond 503 « installation en cours » ;</li>
 *     <li>installation terminée : l'assistant est fermé pour de bon (404) ; la porte du réseau local ne sert plus que
 *         l'état (le site se consulte par son adresse publique, en HTTPS) ;</li>
 *     <li>{@code /api/setup/status} répond toujours (le navigateur sait ainsi quoi afficher).</li>
 * </ul>
 */
public class EntryFilter {

    public static final String STATUS_PATH = "/api/setup/status";
    private static final Logger LOG = Logger.getLogger(EntryFilter.class);

    @Inject
    SetupState setup;
    @Inject
    SetupConfig config;
    @Inject
    AuthConfig auth;

    @ServerRequestFilter(preMatching = true, priority = 100)
    public Response filter(ContainerRequestContext ctx, HttpServerRequest http) {
        String path = ctx.getUriInfo().getPath();
        if (!path.startsWith("/api/") || path.equals(STATUS_PATH)) {
            return null;
        }
        Entry entry = Entry.of(ctx.getHeaderString(Entry.HEADER), config.missingEntry());
        boolean setupPath = path.startsWith("/api/setup/");
        if (!setup.completed()) {
            if (!setupPath) {
                return ErrorResponse.of(503, "SETUP_REQUIRED",
                        "Installation en cours : le site sera disponible à la fin de l'assistant de premier lancement.");
            }
            String ip = ClientIp.of(http);
            if (entry == Entry.PUBLIC || !LocalNetwork.isLocal(ip)) {
                LOG.warnf("Assistant de premier lancement refusé : %s depuis %s (porte %s)", path, ip, entry.id());
                return ErrorResponse.of(403, "SETUP_LOCAL_ONLY",
                        "L'installation se fait uniquement depuis le réseau local du NAS.");
            }
            return null;
        }
        if (setupPath) {
            return ErrorResponse.of(404, "SETUP_DONE", "L'installation est terminée.");
        }
        if (entry == Entry.LAN) {
            return ErrorResponse.of(403, "USE_PUBLIC_URL",
                    "Le site s'ouvre à son adresse publique : " + auth.publicUrl().orElse("(adresse publique)"));
        }
        return null;
    }
}
