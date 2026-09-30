package fr.plexwish.animeserver.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.stream.Collectors;

/**
 * Transforme toutes les exceptions en {@link ErrorResponse} JSON.
 * Les erreurs inattendues donnent un 500 générique : le détail ne part que dans les logs.
 */
public class ErrorMappers {

    private static final Logger LOG = Logger.getLogger(ErrorMappers.class);

    @ServerExceptionMapper
    public Response webApplication(WebApplicationException e) {
        int status = e.getResponse().getStatus();
        String message = switch (status) {
            case 404 -> "Ressource introuvable";
            case 405 -> "Méthode non autorisée";
            case 401 -> "Authentification requise";
            case 403 -> "Accès refusé";
            default -> status >= 500 ? "Erreur interne" : "Requête invalide";
        };
        if (status >= 500) {
            LOG.error("Erreur serveur", e);
        }
        return ErrorResponse.of(status, ErrorResponse.codeFor(status), message);
    }

    @ServerExceptionMapper
    public Response api(ApiException e) {
        return ErrorResponse.of(e.status(), e.code(), e.getMessage());
    }

    @ServerExceptionMapper
    public Response validation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ErrorMappers::describe)
                .sorted()
                .collect(Collectors.joining("; "));
        return ErrorResponse.of(400, "VALIDATION_ERROR", message);
    }

    @ServerExceptionMapper
    public Response unexpected(Exception e) {
        LOG.error("Erreur inattendue", e);
        return ErrorResponse.of(500, "INTERNAL_ERROR", "Erreur interne");
    }

    /** "login.arg0.username: ne doit pas être vide" → "username: ne doit pas être vide". */
    private static String describe(ConstraintViolation<?> v) {
        String path = v.getPropertyPath().toString();
        String field = path.substring(path.lastIndexOf('.') + 1);
        return field + ": " + v.getMessage();
    }
}
