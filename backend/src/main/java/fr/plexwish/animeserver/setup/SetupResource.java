package fr.plexwish.animeserver.setup;

import fr.plexwish.animeserver.auth.AccessTokenService;
import fr.plexwish.animeserver.auth.AuthConfig;
import fr.plexwish.animeserver.auth.AuthResource.LoginRequest;
import fr.plexwish.animeserver.auth.AuthResource.TokenResponse;
import fr.plexwish.animeserver.auth.LoginService;
import fr.plexwish.animeserver.auth.PasswordService;
import fr.plexwish.animeserver.common.ApiException;
import fr.plexwish.animeserver.common.ClientIp;
import fr.plexwish.animeserver.library.scan.ScanService;
import fr.plexwish.animeserver.user.Role;
import fr.plexwish.animeserver.user.User;
import fr.plexwish.animeserver.user.UserDto;
import fr.plexwish.animeserver.user.UserService;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * Assistant de premier lancement (D1.3), depuis le réseau local uniquement et seulement tant que l'installation
 * n'est pas terminée ({@link EntryFilter}). Le compte administrateur est créé par la personne, avec le mot de passe
 * qu'elle choisit : jamais de mot de passe par défaut. Pas de cookie de session ici (le réseau local est en HTTP) :
 * le jeton d'accès renvoyé suffit pour finir l'assistant.
 */
@Path("/api/setup")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SetupResource {

    private static final Logger LOG = Logger.getLogger(SetupResource.class);
    static final String ADMIN_CREATED = "setup.admin_created_at";

    public record Status(boolean installed, String entry, boolean adminExists, String publicUrl, String version) {
    }

    public record AdminRequest(
            @NotNull @Pattern(regexp = "[A-Za-z0-9._-]{3,50}", message = "3 à 50 caractères : lettres, chiffres, point, tiret, underscore") String username,
            @NotNull @Size(max = 200) String password) {
        @Override
        public String toString() {
            return "AdminRequest[username=" + username + ", password=***]";
        }
    }

    public record SecretRequest(@NotNull @Size(max = 2048) String token) {
        @Override
        public String toString() {
            return "SecretRequest[token=***]";
        }
    }

    public record ScanState(Long scanId, String status, Long videos, Long episodes) {
    }

    @Inject
    SetupState setup;
    @Inject
    SetupConfig config;
    @Inject
    AuthConfig auth;
    @Inject
    UserService users;
    @Inject
    LoginService login;
    @Inject
    AccessTokenService accessTokens;
    @Inject
    AppSettings settings;
    @Inject
    AgroalDataSource dataSource;
    @Inject
    InstallationSettings installation;
    @Inject
    DdnsService ddns;
    @Inject
    ScanService scans;
    @Inject
    JsonWebToken jwt;
    @ConfigProperty(name = "anime.app-version", defaultValue = "dev")
    String version;

    @GET
    @Path("/status")
    @PermitAll
    public Status status(@HeaderParam(Entry.HEADER) String entryHeader) {
        return new Status(setup.completed(), Entry.of(entryHeader, config.missingEntry()).id(),
                QuarkusTransaction.requiringNew().call(User::countAdmins) > 0, auth.publicUrl().orElse(null), version);
    }

    @GET
    @Path("/checks")
    @PermitAll
    public List<InstallationSettings.Check> checks() {
        return installation.checks();
    }

    /**
     * Création de l'unique compte administrateur par l'assistant. Atomique : la première requête gagne (ligne
     * app_setting insérée dans la même transaction que le compte), les suivantes reçoivent 409.
     */
    @POST
    @Path("/admin")
    @PermitAll
    public TokenResponse createAdmin(@Valid @NotNull AdminRequest request, @Context HttpServerRequest http) {
        PasswordService.validate(request.password());
        User user = QuarkusTransaction.requiringNew().call(() -> {
            if (User.countAdmins() > 0 || !claimAdminCreation()) {
                throw new ApiException(409, "ADMIN_EXISTS", "Le compte administrateur existe déjà : se connecter avec lui.");
            }
            return users.create(request.username(), null, request.password(), Role.ADMIN);
        });
        LOG.infof("Assistant : compte administrateur '%s' créé depuis %s", user.username, ClientIp.of(http));
        return new TokenResponse(accessTokens.issue(user), "Bearer", accessTokens.lifetimeSeconds(), UserDto.of(user));
    }

    private boolean claimAdminCreation() {
        try (Connection c = dataSource.getConnection()) {
            return settings.putIfAbsent(c, ADMIN_CREATED, Instant.now().toString());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reprise de l'assistant après fermeture du navigateur : connexion de l'administrateur (anti brute force). */
    @POST
    @Path("/login")
    @PermitAll
    public TokenResponse login(@Valid @NotNull LoginRequest request, @Context HttpServerRequest http) {
        User user = login.authenticate(request.login(), request.password(), ClientIp.of(http));
        if (user.role != Role.ADMIN) {
            throw new ApiException(403, "FORBIDDEN", "Seul l'administrateur peut terminer l'installation.");
        }
        return new TokenResponse(accessTokens.issue(user), "Bearer", accessTokens.lifetimeSeconds(), UserDto.of(user));
    }

    @GET
    @Path("/disk")
    @RolesAllowed("ADMIN")
    public InstallationSettings.DiskView disk() {
        return installation.disk();
    }

    @PUT
    @Path("/disk")
    @RolesAllowed("ADMIN")
    public InstallationSettings.DiskView setDisk(@NotNull DiskAdvice.Thresholds thresholds) {
        return installation.setDisk(thresholds);
    }

    @GET
    @Path("/tmdb")
    @RolesAllowed("ADMIN")
    public InstallationSettings.TmdbView tmdb() {
        return installation.tmdb();
    }

    @PUT
    @Path("/tmdb")
    @RolesAllowed("ADMIN")
    public InstallationSettings.TmdbView setTmdb(@Valid @NotNull SecretRequest request) {
        return installation.setTmdb(request.token());
    }

    @GET
    @Path("/ddns")
    @RolesAllowed("ADMIN")
    public DdnsService.Status ddns() {
        return ddns.status();
    }

    @PUT
    @Path("/ddns")
    @RolesAllowed("ADMIN")
    public DdnsService.Status setDdns(@Valid @NotNull SecretRequest request) throws java.io.IOException {
        return ddns.setToken(request.token());
    }

    @POST
    @Path("/scan")
    @RolesAllowed("ADMIN")
    public ScanState startScan() {
        return new ScanState(scans.start(jwt.getName(), false), "RUNNING", null, null);
    }

    @GET
    @Path("/scan")
    @RolesAllowed("ADMIN")
    public ScanState scan() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("""
                     SELECT id, status, (stats->>'videos')::bigint, (stats->>'episodes')::bigint
                     FROM scan_run ORDER BY id DESC LIMIT 1""");
             ResultSet rs = st.executeQuery()) {
            if (!rs.next()) {
                return new ScanState(null, null, null, null);
            }
            return new ScanState(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3), (Long) rs.getObject(4));
        }
    }

    /** Fin de l'assistant : le site s'ouvre à son adresse publique, l'assistant se ferme définitivement. */
    @POST
    @Path("/finish")
    @RolesAllowed("ADMIN")
    public Status finish(@HeaderParam(Entry.HEADER) String entryHeader) {
        setup.markCompleted("assistant terminé par " + jwt.getName());
        return status(entryHeader);
    }
}
