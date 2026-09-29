package fr.plexwish.animeserver.stream;

/**
 * Plage d'octets inclusive {@code [start, end]} issue d'un header HTTP {@code Range} (RFC 9110 §14).
 * <p>
 * Règles appliquées :
 * <ul>
 *     <li>pas de header, unité inconnue ou syntaxe invalide → on ignore le header et on sert tout le fichier (200) ;</li>
 *     <li>plusieurs plages → seule la première est servie (les lecteurs vidéo n'en demandent jamais plusieurs) ;</li>
 *     <li>plage qui commence après la fin du fichier, ou suffixe nul → 416.</li>
 * </ul>
 */
public record ByteRange(long start, long end) {

    public long length() {
        return end - start + 1;
    }

    public String contentRange(long size) {
        return "bytes " + start + "-" + end + "/" + size;
    }

    /** Résultat de l'évaluation d'un header Range par rapport à la taille du fichier. */
    public sealed interface Result permits Full, Partial, Unsatisfiable {
    }

    /** Servir tout le fichier (200). */
    public record Full() implements Result {
    }

    /** Servir une partie (206). */
    public record Partial(ByteRange range) implements Result {
    }

    /** Plage impossible à satisfaire (416). */
    public record Unsatisfiable() implements Result {
    }

    private static final Full FULL = new Full();
    private static final Unsatisfiable UNSATISFIABLE = new Unsatisfiable();

    public static Result evaluate(String header, long size) {
        if (header == null || header.isBlank()) {
            return FULL;
        }
        String value = header.trim();
        if (!value.regionMatches(true, 0, "bytes=", 0, 6)) {
            return FULL;
        }
        String spec = value.substring(6);
        int comma = spec.indexOf(',');
        if (comma >= 0) {
            spec = spec.substring(0, comma);
        }
        spec = spec.trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return FULL;
        }
        String first = spec.substring(0, dash).trim();
        String last = spec.substring(dash + 1).trim();
        try {
            if (first.isEmpty()) {
                // Suffixe : "bytes=-500" = les 500 derniers octets.
                if (last.isEmpty()) {
                    return FULL;
                }
                long suffix = parseDigits(last);
                if (suffix == 0 || size == 0) {
                    return UNSATISFIABLE;
                }
                return new Partial(new ByteRange(Math.max(0, size - suffix), size - 1));
            }
            long start = parseDigits(first);
            long end = last.isEmpty() ? Long.MAX_VALUE : parseDigits(last);
            if (end < start) {
                return FULL; // "bytes=10-5" : syntaxiquement invalide → ignoré
            }
            if (start >= size) {
                return UNSATISFIABLE;
            }
            return new Partial(new ByteRange(start, Math.min(end, size - 1)));
        } catch (NumberFormatException e) {
            return FULL;
        }
    }

    private static long parseDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                throw new NumberFormatException(s);
            }
        }
        return Long.parseLong(s); // NumberFormatException si dépassement
    }
}
