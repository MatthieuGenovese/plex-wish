package fr.plexwish.animeserver.user;

import fr.plexwish.animeserver.common.ApiException;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** Utilisateur connecté (le front s'en sert pour afficher ou non l'admin). */
@Path("/api/me")
@Authenticated
public class MeResource {

    @Inject
    JsonWebToken jwt;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public UserDto me() {
        User user = User.findById(Long.valueOf(jwt.getSubject()));
        if (user == null || !user.enabled) {
            throw new ApiException(401, "UNAUTHORIZED", "Authentification requise");
        }
        return UserDto.of(user);
    }
}
