package fr.plexwish.animeserver.auth;

import fr.plexwish.animeserver.auth.RefreshTokenService.Grace;
import fr.plexwish.animeserver.auth.RefreshTokenService.Rejected;
import fr.plexwish.animeserver.auth.RefreshTokenService.Rotated;
import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.common.ClientIp;
import fr.plexwish.animeserver.user.User;
import fr.plexwish.animeserver.user.UserDto;
import io.vertx.core.http.HttpServerRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.inject.Inject;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Connexion des clients natifs (app Android), ARCHITECTURE §5.1.1 : le refresh token est dans le corps (requête et
 * réponse), jamais dans un cookie. Réservé aux clients non navigateur : une requête qui porte un en-tête
 * {@code Origin} (tout POST d'une page web) est refusée, donc une page ou un XSS ne peut pas obtenir de refresh
 * token par ici. Même logique serveur que le web : même table, rotation, détection de réutilisation, anti brute force.
 */
@Path("/api/auth/app")
@Produces(MediaType.APPLICATION_JSON)
public class AppAuthResource {

    private static final Logger LOG = Logger.getLogger(AppAuthResource.class);
    static final String CLIENT = "ANDROID";

    /** {@code device} : libellé facultatif de l'appareil (ex. « Galaxy S24 »). Mot de passe masqué dans toString(). */
    public record AppLoginRequest(
            @NotBlank @Size(max = 255) String login,
            @NotNull @Size(max = 200) String password,
            @Size(max = 100) String device) {
        @Override
        public String toString() {
            return "AppLoginRequest[login=" + login + ", password=***, device=" + device + "]";
        }
    }

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {
        @Override
        public String toString() {
            return "RefreshRequest[refreshToken=***]";
        }
    }

    public record AppTokenResponse(String accessToken, String tokenType, int expiresIn, String refreshToken, UserDto user) {
        @Override
        public String toString() {
            return "AppTokenResponse[user=" + user + ", tokens=***]";
        }
    }

    @Inject
    LoginService login;
    @Inject
    AccessTokenService accessTokens;
    @Inject
    RefreshTokenService refreshTokens;

    @POST
    @Path("/login")
    public AppTokenResponse login(@HeaderParam("Origin") String origin, @Valid @NotNull AppLoginRequest request,
                                  @Context HttpServerRequest http) {
        refuseBrowsers(origin);
        User user = login.authenticate(request.login(), request.password(), ClientIp.of(http));
        String device = request.device() == null || request.device().isBlank() ? null : request.device().trim();
        return tokens(user, refreshTokens.create(user, CLIENT, device));
    }

    @POST
    @Path("/refresh")
    public AppTokenResponse refresh(@HeaderParam("Origin") String origin, @Valid @NotNull RefreshRequest request) {
        refuseBrowsers(origin);
        // Dans la fenêtre de tolérance, un nouveau refresh token (réponse précédente perdue) : voir RefreshTokenService.
        return switch (refreshTokens.refresh(request.refreshToken(), true)) {
            case Rotated r -> tokens(r.user(), r.newToken());
            case Grace g -> throw new IllegalStateException("grâce sans rotation pour un client natif");
            case Rejected ignored -> throw new ApiException(401, "INVALID_REFRESH_TOKEN", "Session expirée, reconnectez-vous");
        };
    }

    @POST
    @Path("/logout")
    public Response logout(@HeaderParam("Origin") String origin, @Valid @NotNull RefreshRequest request) {
        refuseBrowsers(origin);
        refreshTokens.revoke(request.refreshToken(), RefreshToken.RevokedReason.LOGOUT);
        return Response.noContent().build();
    }

    /** Changement de mot de passe (S4) depuis l'app : {@code refreshToken} = session de l'app, gardée. Secrets masqués. */
    public record AppChangePasswordRequest(@NotNull @Size(max = 200) String currentPassword,
                                           @NotNull @Size(max = 200) String newPassword,
                                           @Size(max = 200) String refreshToken) {
        @Override
        public String toString() {
            return "AppChangePasswordRequest[currentPassword=***, newPassword=***, refreshToken=***]";
        }
    }

    /** Comme {@code POST /api/auth/password} (§24.5), pour l'app : jeton d'accès exigé, pas d'en-tête Origin. */
    @POST
    @Path("/password")
    @io.quarkus.security.Authenticated
    public AuthResource.ChangePasswordResponse changePassword(@HeaderParam("Origin") String origin,
                                                              @Valid @NotNull AppChangePasswordRequest request,
                                                              @Context HttpServerRequest http) {
        refuseBrowsers(origin);
        return new AuthResource.ChangePasswordResponse(passwordChange.change(Long.parseLong(jwt.getSubject()),
                request.currentPassword(), request.newPassword(), request.refreshToken(), ClientIp.of(http)));
    }

    @Inject
    PasswordChangeService passwordChange;
    @Inject
    org.eclipse.microprofile.jwt.JsonWebToken jwt;

    private static void refuseBrowsers(String origin) {
        if (origin != null) {
            LOG.warnf("Requête /api/auth/app refusée : en-tête Origin '%s' (réservé aux clients natifs)", origin);
            throw new ApiException(403, "NATIVE_CLIENT_ONLY", "Réservé à l'application (pas aux navigateurs)");
        }
    }

    private AppTokenResponse tokens(User user, String refreshToken) {
        return new AppTokenResponse(accessTokens.issue(user), "Bearer", accessTokens.lifetimeSeconds(), refreshToken, UserDto.of(user));
    }
}
