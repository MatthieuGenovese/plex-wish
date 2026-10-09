package fr.plexwish.animeserver.setup;

import fr.plexwish.animeserver.auth.AuthConfig;
import fr.plexwish.animeserver.setup.SetupResource.SecretRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;

/**
 * Administration > Réglages (D1.3) : mêmes réglages que l'assistant, modifiables ensuite. Les secrets ne sont jamais
 * renvoyés : seulement « configuré » ou non.
 */
@Path("/api/admin/settings")
@RolesAllowed("ADMIN")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SettingsAdminResource {

    public record Overview(String version, String publicUrl, InstallationSettings.TmdbView tmdb, DdnsService.Status ddns,
                           InstallationSettings.DiskView disk, InstallationSettings.BackupStatus backup) {
    }

    @Inject
    InstallationSettings installation;
    @Inject
    DdnsService ddns;
    @Inject
    AuthConfig auth;
    @ConfigProperty(name = "anime.app-version", defaultValue = "dev")
    String version;

    @GET
    public Overview overview() {
        return new Overview(version, auth.publicUrl().orElse(null), installation.tmdb(), ddns.status(), installation.disk(),
                installation.backup().orElse(null));
    }

    @PUT
    @Path("/tmdb")
    public InstallationSettings.TmdbView setTmdb(@Valid @NotNull SecretRequest request) {
        return installation.setTmdb(request.token());
    }

    @DELETE
    @Path("/tmdb")
    public InstallationSettings.TmdbView clearTmdb() {
        return installation.clearTmdb();
    }

    @PUT
    @Path("/ddns")
    public DdnsService.Status setDdns(@Valid @NotNull SecretRequest request) throws IOException {
        return ddns.setToken(request.token());
    }

    @DELETE
    @Path("/ddns")
    public DdnsService.Status clearDdns() throws IOException {
        ddns.clearToken();
        return ddns.status();
    }

    @PUT
    @Path("/disk")
    public InstallationSettings.DiskView setDisk(@NotNull DiskAdvice.Thresholds thresholds) {
        return installation.setDisk(thresholds);
    }
}
