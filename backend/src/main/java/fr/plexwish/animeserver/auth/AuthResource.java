package fr.plexwish.animeserver.auth;

import fr.plexwish.animeserver.auth.RefreshToken.RevokedReason;
import fr.plexwish.animeserver.auth.RefreshTokenService.Grace;
import fr.plexwish.animeserver.auth.RefreshTokenService.Rejected;
import fr.plexwish.animeserver.auth.RefreshTokenService.Rotated;
import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.common.ClientIp;
import fr.plexwish.animeserver.common.ErrorResponse;
import fr.plexwish.animeserver.user.User;
import fr.plexwish.animeserver.user.UserDto;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Login / refresh / logout (ARCHITECTURE §5.1).
 * Access token dans le corps de la réponse (gardé en mémoire par le client),
 * refresh token dans un cookie HttpOnly limité à /api/auth.
 */
@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    public static final String COOKIE = "refresh_token";
    private static final String COOKIE_PATH = "/api/auth";
    private static final Logger LOG = Logger.getLogger(AuthResource.class);

    /** Le mot de passe n'apparaît jamais dans toString() (logs, messages d'erreur). */
    public record LoginRequest(
            @NotBlank @Size(max = 255) String login,
            @NotNull @Size(max = 200) String password) {
        @Override
        public String toString() {
            return "LoginRequest[login=" + login + ", password=***]";
        }
    }

    public record TokenResponse(String accessToken, String tokenType, int expiresIn, UserDto user) {
        @Override
        public String toString() {
            return "TokenResponse[user=" + user + "]";
        }
    }

    @Inject
    LoginService login;
    @Inject
    AccessTokenService accessTokens;
    @Inject
    RefreshTokenService refreshTokens;
    @Inject
    AuthConfig config;

    @POST
    @Path("/login")
    public Response login(@Valid @NotNull LoginRequest request, @Context HttpServerRequest http) {
        User user = login.authenticate(request.login(), request.password(), ClientIp.of(http));
        return Response.ok(tokens(user)).cookie(refreshCookie(refreshTokens.create(user))).build();
    }

    @POST
    @Path("/refresh")
    public Response refresh(@CookieParam(COOKIE) String token) {
        return switch (refreshTokens.refresh(token)) {
            case Rotated r -> Response.ok(tokens(r.user())).cookie(refreshCookie(r.newToken())).build();
            // Requête concurrente : le navigateur a déjà reçu le bon cookie, on n'y touche pas.
            case Grace g -> Response.ok(tokens(g.user())).build();
            case Rejected ignored -> Response.status(401)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorResponse(401, "INVALID_REFRESH_TOKEN", "Session expirée, reconnectez-vous"))
                    .cookie(clearCookie())
                    .build();
        };
    }

    @POST
    @Path("/logout")
    public Response logout(@CookieParam(COOKIE) String token) {
        refreshTokens.revoke(token, RevokedReason.LOGOUT);
        return Response.noContent().cookie(clearCookie()).build();
    }

    /** Changement de mot de passe (S4) : mot de passe actuel et nouveau ; masqués dans toString(). */
    public record ChangePasswordRequest(@NotNull @Size(max = 200) String currentPassword, @NotNull @Size(max = 200) String newPassword) {
        @Override
        public String toString() {
            return "ChangePasswordRequest[currentPassword=***, newPassword=***]";
        }
    }

    /** Nombre de sessions fermées (les autres appareils et navigateurs). */
    public record ChangePasswordResponse(int closedSessions) {
    }

    /**
     * Changement de son propre mot de passe (navigateur, ARCHITECTURE §24.5). Jeton d'accès exigé ; la session de
     * ce navigateur (cookie, envoyé ici car sous /api/auth) est gardée, toutes les autres sont fermées.
     */
    @POST
    @Path("/password")
    @io.quarkus.security.Authenticated
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_JSON)
    public ChangePasswordResponse changePassword(@Valid @NotNull ChangePasswordRequest request, @CookieParam(COOKIE) String cookie,
                                                 @Context HttpServerRequest http) {
        return new ChangePasswordResponse(passwordChange.change(Long.parseLong(jwt.getSubject()), request.currentPassword(),
                request.newPassword(), cookie, ClientIp.of(http)));
    }

    @Inject
    PasswordChangeService passwordChange;
    @Inject
    org.eclipse.microprofile.jwt.JsonWebToken jwt;

    TokenResponse tokens(User user) {
        return new TokenResponse(accessTokens.issue(user), "Bearer", accessTokens.lifetimeSeconds(), UserDto.of(user));
    }

    private NewCookie refreshCookie(String value) {
        return cookie(value, (int) Duration.ofDays(config.refreshTokenDays()).toSeconds());
    }

    private NewCookie clearCookie() {
        return cookie("", 0);
    }

    private NewCookie cookie(String value, int maxAge) {
        return new NewCookie.Builder(COOKIE)
                .value(value)
                .path(COOKIE_PATH)
                .httpOnly(true)
                .secure(config.cookieSecure())
                .sameSite(NewCookie.SameSite.STRICT)
                .maxAge(maxAge)
                .build();
    }
}
