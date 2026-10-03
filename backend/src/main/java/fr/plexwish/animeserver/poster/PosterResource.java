package fr.plexwish.animeserver.poster;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import java.sql.SQLException;

/**
 * Affiche locale : {@code GET /api/posters/{publicId}} (ARCHITECTURE §17.3). Sans authentification (une balise
 * <img> n'envoie pas l'en-tête Authorization) : l'identifiant est aléatoire (128 bits), introuvable sans passer par
 * l'API authentifiée. Aucun chemin ne vient du client : l'identifiant est cherché en base.
 */
@Path("/api")
public class PosterResource {

    @Inject
    PosterService service;
    @Inject
    PosterConfig config;

    @GET
    @Path("/posters/{publicId}")
    @PermitAll
    public Response poster(@PathParam("publicId") String publicId) throws SQLException {
        if (publicId == null || !publicId.matches("[0-9a-f]{32}")) {
            return Response.status(404).build();
        }
        return service.served(publicId)
                .map(s -> Response.ok(s.file().toFile(), s.contentType())
                        // L'URL change quand l'image change : cache long, sans revalidation.
                        .header("Cache-Control", "private, max-age=" + config.browserCache().toSeconds() + ", immutable")
                        .header("ETag", "\"" + s.sha256() + "\"")
                        .header("X-Content-Type-Options", "nosniff")
                        .build())
                .orElseGet(() -> Response.status(404).build());
    }
}
