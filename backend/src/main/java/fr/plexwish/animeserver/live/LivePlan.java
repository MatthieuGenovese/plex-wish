package fr.plexwish.animeserver.live;

import java.util.ArrayList;
import java.util.List;

/**
 * Découpage d'un fichier en segments (docs/LIVE-REMUX.md §2.1, §3.4) : chaque segment commence sur une image clé
 * d'origine et dure au moins {@link #TARGET_SECONDS} (sauf le dernier). Calculé à l'avance à partir des images clés :
 * la durée totale et les bornes sont connues dès le départ, le seek est libre.
 *
 * @param bounds     début de chaque segment (secondes, croissant ; le premier = première image clé)
 * @param keyframes  toutes les images clés (secondes, croissant), pour viser juste au lancement de ffmpeg
 * @param duration   fin du dernier segment (durée du fichier)
 */
public record LivePlan(double[] bounds, double[] keyframes, double duration) {

    /** Durée minimale d'un segment (secondes). */
    public static final double TARGET_SECONDS = 6.0;
    /** Tolérance pour ranger un fragment dans un segment (horodatages arrondis à la milliseconde par les conteneurs). */
    static final double TOLERANCE = 0.1;

    /** Plan à partir des images clés (ms) et de la durée (ms) ; vide si aucune image clé. */
    public static LivePlan of(int[] keyframesMs, int durationMs) {
        if (keyframesMs.length == 0) {
            throw new IllegalArgumentException("aucune image clé");
        }
        double[] kf = new double[keyframesMs.length];
        for (int i = 0; i < kf.length; i++) {
            kf[i] = keyframesMs[i] / 1000.0;
        }
        List<Double> b = new ArrayList<>();
        b.add(kf[0]);
        for (int i = 1; i < kf.length; i++) {
            if (kf[i] - b.get(b.size() - 1) >= TARGET_SECONDS) {
                b.add(kf[i]);
            }
        }
        double end = Math.max(durationMs / 1000.0, kf[kf.length - 1] + 0.04);
        // Dernier segment trop court (< 1 s) : fusionné avec le précédent.
        if (b.size() > 1 && end - b.get(b.size() - 1) < 1.0) {
            b.remove(b.size() - 1);
        }
        return new LivePlan(b.stream().mapToDouble(Double::doubleValue).toArray(), kf, end);
    }

    public int count() {
        return bounds.length;
    }

    public double start(int segment) {
        return bounds[segment];
    }

    public double length(int segment) {
        double end = segment + 1 < bounds.length ? bounds[segment + 1] : duration;
        return end - bounds[segment];
    }

    /** Segment qui contient l'instant {@code t} (secondes, même repère que les bornes). */
    public int segmentAt(double t) {
        int lo = 0;
        int hi = bounds.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (bounds[mid] <= t + TOLERANCE) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /**
     * Instant passé à {@code -ss} pour que ffmpeg parte exactement de l'image clé du segment : un peu après elle
     * (min 0,5 s, moitié de l'écart avec la suivante), car viser l'image clé elle-même fait reculer d'une image clé
     * dans un AVI (essai du 2026-10-18). Null pour le premier segment (pas de saut).
     */
    public Double seekFor(int segment) {
        if (segment == 0) {
            return null;
        }
        double start = bounds[segment];
        double next = duration;
        for (double k : keyframes) {
            if (k > start + 1e-6) {
                next = k;
                break;
            }
        }
        return start + Math.min(0.5, (next - start) / 2);
    }

    /** Durée cible annoncée dans les playlists ({@code EXT-X-TARGETDURATION}, secondes entières, arrondi supérieur). */
    public int targetDuration() {
        double max = 0;
        for (int i = 0; i < bounds.length; i++) {
            max = Math.max(max, length(i));
        }
        return (int) Math.ceil(max);
    }
}
