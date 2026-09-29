package fr.plexwish.animeserver.common;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Format unique des erreurs de l'API : {@code {"status": 404, "error": "NOT_FOUND", "message": "..."}}.
 * {@code error} est un code stable (utilisable par le front), {@code message} un texte lisible.
 */
public record ErrorResponse(int status, String error, String message) {

    public static Response of(int status, String error, String message) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorResponse(status, error, message))
                .build();
    }

    /** Code dérivé du statut HTTP : 404 → NOT_FOUND, 405 → METHOD_NOT_ALLOWED… */
    public static String codeFor(int status) {
        Response.Status known = Response.Status.fromStatusCode(status);
        return known != null ? known.name() : "HTTP_" + status;
    }
}
