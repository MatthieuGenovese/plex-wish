package fr.plexwish.animeserver.library.scan;

import java.text.Normalizer;
import java.util.Locale;

/** Clé d'identification d'un animé : "Chûnibyô Demo Koi!" → "chunibyo demo koi". */
public final class Titles {

    private Titles() {
    }

    public static String normalize(String title) {
        String s = Normalizer.normalize(title, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        s = s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return s.isEmpty() ? title.trim().toLowerCase(Locale.ROOT) : s;
    }
}
