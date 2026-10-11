package fr.plexwish.animeserver.library.parse;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fin du dernier sous-titre d'un fichier ASS / SSA (contrôle de durée, 1.3.0) : lit les lignes {@code Dialogue:} de la
 * section {@code [Events]}, avec la colonne {@code End} annoncée par sa ligne {@code Format:} (3e colonne sinon).
 * Les commentaires ({@code Comment:}) ne comptent pas. Lecture seule, ligne par ligne.
 */
public final class AssTiming {

    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{1,2}):(\\d{1,2})(?:[.,](\\d{1,3}))?");
    /** Au-delà, le fichier n'est pas lu (un .ass fait quelques centaines de Ko). */
    public static final long MAX_BYTES = 20_000_000L;

    private AssTiming() {
    }

    public static OptionalDouble lastCueSeconds(BufferedReader reader) throws IOException {
        boolean events = false;
        int endColumn = 2;
        int columns = 10;
        double last = -1;
        String line;
        boolean first = true;
        while ((line = reader.readLine()) != null) {
            if (first && !line.isEmpty() && line.charAt(0) == '﻿') {
                line = line.substring(1);
            }
            first = false;
            String t = line.trim();
            if (t.startsWith("[")) {
                events = t.toLowerCase(Locale.ROOT).startsWith("[events]");
                continue;
            }
            if (!events) {
                continue;
            }
            if (t.regionMatches(true, 0, "Format:", 0, 7)) {
                String[] cols = t.substring(7).split(",");
                for (int i = 0; i < cols.length; i++) {
                    if (cols[i].trim().equalsIgnoreCase("End")) {
                        endColumn = i;
                    }
                }
                columns = cols.length;
            } else if (t.regionMatches(true, 0, "Dialogue:", 0, 9)) {
                String[] cols = t.substring(9).split(",", columns);
                if (cols.length > endColumn) {
                    double end = seconds(cols[endColumn].trim());
                    if (end > last) {
                        last = end;
                    }
                }
            }
        }
        return last < 0 ? OptionalDouble.empty() : OptionalDouble.of(last);
    }

    /** « 0:23:41.52 » → 1421.52 ; -1 si illisible. */
    static double seconds(String s) {
        Matcher m = TIME.matcher(s);
        if (!m.matches()) {
            return -1;
        }
        double frac = 0;
        if (m.group(4) != null) {
            frac = Integer.parseInt(m.group(4)) / Math.pow(10, m.group(4).length());
        }
        return Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3)) + frac;
    }
}
