package fr.plexwish.animeserver.auth;

import fr.plexwish.animeserver.common.ClientIp;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

/**
 * IP du client telle que le backend la voit (ARCHITECTURE §5.4.1).
 * Sert à vérifier la chaîne reverse proxy → nginx → backend (scripts/check-client-ip.sh).
 */
@Path("/api/admin/debug/client-ip")
@RolesAllowed("ADMIN")
public class ClientIpDebugResource {

    public record ClientIpResponse(String ip) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ClientIpResponse clientIp(@Context HttpServerRequest request) {
        return new ClientIpResponse(ClientIp.of(request));
    }
}
