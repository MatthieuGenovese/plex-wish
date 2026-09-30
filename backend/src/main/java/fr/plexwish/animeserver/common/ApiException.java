package fr.plexwish.animeserver.common;

/** Erreur métier attendue, renvoyée telle quelle au client ({@link ErrorResponse}). */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message, null, false, false); // pas de stack trace : ce n'est pas un bug
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
