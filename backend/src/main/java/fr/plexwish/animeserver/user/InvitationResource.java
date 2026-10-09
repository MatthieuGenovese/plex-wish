package fr.plexwish.animeserver.user;

import fr.plexwish.animeserver.common.ClientIp;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

/**
 * Page publique du lien d'invitation (D1.4). Le jeton est toujours dans le CORPS de la requête (jamais dans l'adresse :
 * il n'apparaît donc dans aucun journal d'accès).
 */
@Path("/api/invitation")
@PermitAll
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InvitationResource {

    public record CheckRequest(@NotNull @Size(max = 100) String token) {
        @Override
        public String toString() {
            return "CheckRequest[token=***]";
        }
    }

    public record AcceptRequest(@NotNull @Size(max = 100) String token, @NotNull @Size(max = 200) String password) {
        @Override
        public String toString() {
            return "AcceptRequest[token=***, password=***]";
        }
    }

    public record Accepted(String username) {
    }

    @Inject
    InvitationService invitations;

    @POST
    @Path("/check")
    public InvitationService.Preview check(@Valid @NotNull CheckRequest request, @Context HttpServerRequest http) {
        return invitations.preview(request.token(), ClientIp.of(http));
    }

    @POST
    @Path("/accept")
    public Accepted accept(@Valid @NotNull AcceptRequest request, @Context HttpServerRequest http) {
        return new Accepted(invitations.accept(request.token(), request.password(), ClientIp.of(http)));
    }
}
