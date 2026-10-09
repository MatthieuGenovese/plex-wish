package fr.plexwish.animeserver.auth;

import fr.plexwish.animeserver.common.ErrorResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Protection CSRF complémentaire à SameSite=Strict (ARCHITECTURE §5.1) : toute requête d'écriture
 * sur /api dont le header Origin est présent doit venir de PUBLIC_URL (ou d'une origine CORS_ORIGINS
 * en dev). Sans header Origin (curl, app Android), la requête passe : l'authentification suffit.
 */
public class OriginCheck {

    private static final Logger LOG = Logger.getLogger(OriginCheck.class);
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final Set<String> allowed = new HashSet<>();

    @Inject
    public OriginCheck(AuthConfig config,
                       @ConfigProperty(name = "quarkus.http.cors.origins") Optional<List<String>> corsOrigins) {
        config.publicUrl().flatMap(OriginCheck::originOf).ifPresent(allowed::add);
        corsOrigins.orElse(List.of()).forEach(o -> originOf(o).ifPresent(allowed::add));
    }

    @ServerRequestFilter
    public Response check(ContainerRequestContext ctx) {
        if (SAFE_METHODS.contains(ctx.getMethod()) || !ctx.getUriInfo().getPath().startsWith("/api/")) {
            return null;
        }
        String origin = ctx.getHeaderString("Origin");
        if (origin == null || allowed.contains(origin.toLowerCase(Locale.ROOT))) {
            return null;
        }
        // Assistant de premier lancement (D1.3), ouvert à l'adresse locale du NAS (http://192.168.x.y:8080), pas à
        // PUBLIC_URL : on accepte la même origine que la requête (Origin = Host). Une page d'un autre site ne peut pas
        // imiter son en-tête Origin ; EntryFilter limite déjà ces chemins au réseau local et à l'installation en cours.
        if (ctx.getUriInfo().getPath().startsWith("/api/setup/") && sameOrigin(origin, ctx.getHeaderString("Host"))) {
            return null;
        }
        LOG.warnf("Requête %s %s refusée : Origin '%s' différent de PUBLIC_URL", ctx.getMethod(), ctx.getUriInfo().getPath(), origin);
        return ErrorResponse.of(403, "ORIGIN_NOT_ALLOWED", "Origine de la requête non autorisée");
    }

    static boolean sameOrigin(String origin, String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(origin.trim());
            return uri.getRawAuthority() != null && uri.getRawAuthority().equalsIgnoreCase(host.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** "https://Anime.Example.com:443/chemin" → "https://anime.example.com" ; vide si ce n'est pas une URL http(s). */
    static Optional<String> originOf(String url) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (uri.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
                return Optional.empty();
            }
            int port = uri.getPort();
            boolean defaultPort = port == -1 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
            return Optional.of(scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
