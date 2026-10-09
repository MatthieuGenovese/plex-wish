package fr.plexwish.animeserver.setup;

/**
 * Seuils d'espace disque proposés selon l'espace LIBRE mesuré sur le volume du NAS (D1.3, point 10) :
 * <ul>
 *     <li>alerte « espace faible » : 10 % de l'espace libre, entre 5 et 100 Go ;</li>
 *     <li>alerte « critique » : 4 % de l'espace libre, entre 2 et 40 Go, et toujours sous la première ;</li>
 *     <li>plafond du cache de remux : 15 % de l'espace libre, entre 2 et 100 Go, sans jamais faire passer l'espace
 *         libre sous l'alerte « espace faible » (marge de 5 Go).</li>
 * </ul>
 * Exemples : 500 Go libres → 50 / 20 / 75 Go ; 2 To → 100 / 40 / 100 Go ; 40 Go → 5 / 2 / 6 Go.
 * Gigaoctets décimaux (comme le DSM).
 */
public final class DiskAdvice {

    public record Thresholds(int warnGb, int criticalGb, int remuxCapGb) {
    }

    private DiskAdvice() {
    }

    public static Thresholds propose(long freeBytes) {
        double free = Math.max(0, freeBytes) / 1e9;
        int warn = clamp(Math.round(free * 0.10), 5, 100);
        int critical = clamp(Math.round(free * 0.04), 2, 40);
        if (critical >= warn) {
            critical = Math.max(1, warn / 2);
        }
        int remux = clamp(Math.round(free * 0.15), 2, 100);
        int room = (int) Math.floor(free - warn - 5);
        if (remux > room) {
            remux = Math.max(1, room);
        }
        return new Thresholds(warn, critical, remux);
    }

    /** Vérification d'une saisie : critique < alerte, valeurs raisonnables. Message lisible, ou null si correct. */
    public static String problem(Thresholds t) {
        if (t.warnGb() < 1 || t.warnGb() > 10_000 || t.criticalGb() < 1 || t.remuxCapGb() < 1 || t.remuxCapGb() > 10_000) {
            return "Valeurs hors limites (1 à 10 000 Go).";
        }
        if (t.criticalGb() >= t.warnGb()) {
            return "Le seuil critique doit être plus petit que le seuil d'alerte.";
        }
        return null;
    }

    private static int clamp(long v, int min, int max) {
        return (int) Math.max(min, Math.min(max, v));
    }
}
