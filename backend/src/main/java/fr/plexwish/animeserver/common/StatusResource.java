package fr.plexwish.animeserver.common;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Endpoint public minimal : permet au front (et à un humain) de vérifier que la chaîne
 * navigateur → nginx → backend fonctionne. Ne renvoie aucune information sensible.
 * Le health check technique (base incluse) est sur /q/health, non exposé par nginx.
 */
@Path("/api/status")
public class StatusResource {

    public record Status(String status) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Status status() {
        return new Status("UP");
    }
}
