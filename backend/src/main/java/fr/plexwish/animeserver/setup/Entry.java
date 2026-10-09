package fr.plexwish.animeserver.setup;

import java.util.Locale;

/**
 * Porte par laquelle une requête est arrivée, posée par nginx dans l'en-tête {@value #HEADER} (le client ne peut pas
 * la choisir : nginx remplace toujours cet en-tête, et le serveur n'est joignable que par nginx).
 */
public enum Entry {
    /** Internet, par Caddy (ou Funnel) : le site normal, jamais l'assistant. */
    PUBLIC,
    /** Port du réseau local du NAS : l'assistant de premier lancement seulement. */
    LAN,
    /** Pile de développement ou de démonstration : tout est permis (comme avant D1). */
    LOCAL;

    public static final String HEADER = "X-Anime-Entry";

    public static Entry of(String header, String fallback) {
        String value = header == null || header.isBlank() ? fallback : header;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PUBLIC; // valeur inconnue : le cas le plus restrictif
        }
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
