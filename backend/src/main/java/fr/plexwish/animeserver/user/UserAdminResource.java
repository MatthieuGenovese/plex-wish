package fr.plexwish.animeserver.user;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.net.URI;
import java.util.List;

@Path("/api/admin/users")
@RolesAllowed("ADMIN")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserAdminResource {

    public record CreateUserRequest(
            @NotNull @Pattern(regexp = "[A-Za-z0-9._-]{3,50}",
                    message = "3 à 50 caractères : lettres, chiffres, point, tiret, underscore") String username,
            @Email @Size(max = 255) String email,
            @NotNull String password,
            @NotNull Role role) {
        @Override
        public String toString() {
            return "CreateUserRequest[username=" + username + ", email=" + email + ", role=" + role + ", password=***]";
        }
    }

    /** Champs absents = inchangés. {@code password} = réinitialisation par l'admin. */
    public record UpdateUserRequest(Boolean enabled, Role role, String password) {
        @Override
        public String toString() {
            return "UpdateUserRequest[enabled=" + enabled + ", role=" + role + ", password=" + (password == null ? "null" : "***") + "]";
        }
    }

    @Inject
    UserService users;
    @Inject
    JsonWebToken jwt;

    @GET
    public List<UserDto> list() {
        return User.listByUsername().stream().map(UserDto::of).toList();
    }

    @POST
    public Response create(@Valid @NotNull CreateUserRequest request) {
        User user = users.create(request.username(), request.email(), request.password(), request.role());
        return Response.created(URI.create("/api/admin/users/" + user.id)).entity(UserDto.of(user)).build();
    }

    @PATCH
    @Path("/{id}")
    public UserDto update(@PathParam("id") Long id, @Valid @NotNull UpdateUserRequest request) {
        User user = users.update(id, request.enabled(), request.role(), request.password(), Long.valueOf(jwt.getSubject()));
        return UserDto.of(user);
    }
}
