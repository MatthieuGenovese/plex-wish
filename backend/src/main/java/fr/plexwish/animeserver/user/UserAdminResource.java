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
            /** Facultatif (D1.4) : sans mot de passe, la réponse contient un lien d'invitation à transmettre. */
            @Size(max = 200) String password,
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

    /** Compte créé, et son lien d'invitation s'il a été créé sans mot de passe. */
    public record Created(Long id, String username, String email, Role role, boolean enabled, java.time.Instant createdAt,
                          boolean passwordSet, java.time.Instant invitationExpiresAt, InvitationService.Link invitation) {
    }

    @Inject
    InvitationService invitations;

    @GET
    @jakarta.transaction.Transactional
    public List<UserDto> list() {
        return User.listByUsername().stream().map(UserDto::withInvitation).toList();
    }

    @POST
    public Response create(@Valid @NotNull CreateUserRequest request) {
        User user = users.create(request.username(), request.email(), request.password(), request.role());
        InvitationService.Link link = user.passwordHash == null ? invitations.create(user.id, jwt.getName()) : null;
        return Response.created(URI.create("/api/admin/users/" + user.id))
                .entity(new Created(user.id, user.username, user.email, user.role, user.enabled, user.createdAt,
                        user.passwordHash != null, link == null ? null : link.expiresAt(), link))
                .build();
    }

    /** Nouveau lien : invitation (pas encore de mot de passe) ou réinitialisation (mot de passe oublié). */
    @POST
    @Path("/{id}/invitation")
    @Consumes(MediaType.WILDCARD)
    public InvitationService.Link newLink(@PathParam("id") Long id) {
        return invitations.create(id, jwt.getName());
    }

    @jakarta.ws.rs.DELETE
    @Path("/{id}/invitation")
    public Response revokeLink(@PathParam("id") Long id) {
        invitations.revokeAll(id);
        return Response.noContent().build();
    }

    @PATCH
    @Path("/{id}")
    public UserDto update(@PathParam("id") Long id, @Valid @NotNull UpdateUserRequest request) {
        User user = users.update(id, request.enabled(), request.role(), request.password(), Long.valueOf(jwt.getSubject()));
        return UserDto.of(user);
    }
}
