package fr.plexwish.animeserver.library.parse;

import fr.plexwish.animeserver.library.parse.ParseResult.Episode;
import fr.plexwish.animeserver.library.parse.ParseResult.Extra;
import fr.plexwish.animeserver.library.parse.ParseResult.Problem;
import fr.plexwish.animeserver.library.parse.ParseResult.SeasonSource;
import fr.plexwish.animeserver.library.parse.ParseResult.Strategy;
import fr.plexwish.animeserver.library.parse.ParseResult.Unresolved;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser de la bibliothèque, règles d'ARCHITECTURE §7.2 à §7.5, dans cet ordre :
 * <ol>
 *     <li>{@code SxxExx} ;</li>
 *     <li>{@code NxEE} ;</li>
 *     <li>{@code E\d+} seul ;</li>
 *     <li>test d'extras (uniquement pour un fichier sans numéro d'après 1 à 3) ;</li>
 *     <li>numéro seul.</li>
 * </ol>
 * Le nom du fichier fait foi ; le dossier de saison sert de secours (3 et 5) et de vérification (1 et 2).
 */
@ApplicationScoped
public class DefaultFilenameParser implements FilenameParser {

    // --- 1 à 3 : motifs explicites ------------------------------------------------------------------
    private static final Pattern SXXEXX = Pattern.compile(
            "(?i)(?<![a-z0-9])S(\\d{1,2})[ ._-]?E(\\d{1,4})(\\.\\d{1,2}(?![0-9]))?(?![0-9])");
    private static final Pattern NXEE = Pattern.compile(
            "(?i)(?<![0-9])(\\d{1,2})x(\\d{2,3})(\\.\\d{1,2}(?![0-9]))?(?![0-9])");
    private static final Pattern E_NUMBER = Pattern.compile(
            "(?i)(?<![a-z0-9])E(\\d{1,4})(\\.\\d{1,2}(?![0-9]))?(?![0-9])");
    /** Juste après un épisode : "-E04", " - 04" → double épisode (si le second numéro suit de près). */
    private static final Pattern FOLLOWING_EPISODE = Pattern.compile("(?i)^\\s*-\\s*E?(\\d{1,4})(?![0-9]|p)");
    /** "S1" isolé dans le nom : donne la saison aux stratégies 3 et 5. */
    private static final Pattern SEASON_IN_NAME = Pattern.compile("(?i)(?<![a-z0-9])S(\\d{1,2})(?![a-z0-9])");

    // --- Nettoyage avant 4 et 5 ------------------------------------------------------------------------
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*]|\\([^)]*\\)|\\{[^}]*}");
    /** Éléments techniques qui contiennent des chiffres (ou pourraient être pris pour un marqueur). */
    private static final Pattern TECHNICAL = Pattern.compile("(?i)(?<![a-z0-9])("
            + "\\d{3,4}p|\\d{3,4}x\\d{3,4}|[xh][ ._]?26[45]|hevc|avc|\\d{1,2} ?bits?"
            + "|aac\\d?(\\.\\d)?|e?ac3|flac|opus|dts|mp3|ddp?\\d\\.\\d|\\d\\.\\d"
            + "|web-?dl|web-?rip|bd ?rip|blu-?ray|dvd ?rip"
            + ")(?![a-z0-9])");

    // --- 4 : extras --------------------------------------------------------------------------------------
    /** Marqueurs insensibles à la casse, et OP / ED seulement en MAJUSCULES ("Takt Op. Destiny" est un titre). */
    private static final Pattern EXTRA_MARKER = Pattern.compile(
            "(?i:(?<![a-z])(nc ?(?:op|ed)\\d*(?:v\\d)?|opening|ending|creditless|menu|trailer|teaser|preview|amv|ost)(?![a-z]))"
                    + "|(?<![A-Za-z])(OP|ED)(?: ?\\d+)?(?:v\\d)?(?![A-Za-z])");
    private static final Pattern EXTRA_DIR = Pattern.compile(
            "(?i)^(ost|music|musique|op ?- ?ed.*|nc|extras?|op|ed|pv|trailers?|menus?)$");

    // --- 5 : numéro seul ---------------------------------------------------------------------------------
    private static final Pattern DOUBLE_NUMBER = Pattern.compile("(?<![0-9])(\\d{2,3})-(\\d{2,3})(?![0-9])");
    private static final Pattern DECIMAL_NUMBER = Pattern.compile("(?<![0-9.])\\d{1,3}\\.\\d{1,2}(?![0-9])");
    /** 2 à 4 chiffres précédés d'un espace ou d'un "_", suivis d'une fin, d'un séparateur ou d'un suffixe v2. */
    private static final Pattern CANDIDATE = Pattern.compile("(?<=[ _])(\\d{2,4})(?:v\\d)?(?=$|[ _.\\[(-])");

    // --- Dossiers ----------------------------------------------------------------------------------------
    private static final Pattern SEASON_DIR = Pattern.compile("(?i)^(?:season|saison|s)\\s*0*(\\d{1,2})\\b");
    private static final Pattern SPECIAL_DIR = Pattern.compile("(?i)(?<![a-z])(oav|ova|specials?|spéciaux|speciaux|bonus)(?![a-z])");

    @Override
    public ParseResult parse(String relativePath) {
        List<String> parts = Arrays.asList(relativePath.split("/"));
        if (parts.size() < 2) {
            return new Unresolved(null, Problem.NO_ANIME_FOLDER, "vidéo à la racine de la bibliothèque");
        }
        String title = parts.get(0).trim();
        List<String> dirs = parts.subList(1, parts.size() - 1);
        String name = stem(parts.get(parts.size() - 1));

        // 1. SxxExx
        Matcher m = SXXEXX.matcher(name);
        if (m.find()) {
            return explicit(title, dirs, name, m, new Season(Integer.parseInt(m.group(1)), SeasonSource.NAME_SXXEXX), 2, 3, Strategy.SXXEXX);
        }
        // 2. NxEE
        m = NXEE.matcher(name);
        if (m.find()) {
            return explicit(title, dirs, name, m, new Season(Integer.parseInt(m.group(1)), SeasonSource.NAME_NXEE), 2, 3, Strategy.NXEE);
        }
        // 3. E\d+ seul : saison du nom (S1), sinon du dossier, sinon 1.
        //    Cherché hors crochets et parenthèses : une empreinte CRC comme [E4E2B273] contient "E4".
        String unbracketed = BRACKETS.matcher(name).replaceAll(" ");
        m = E_NUMBER.matcher(unbracketed);
        if (m.find()) {
            return explicit(title, dirs, unbracketed, m, fallbackSeason(name, dirs), 1, 2, Strategy.E_NUMBER);
        }

        String cleaned = TECHNICAL.matcher(BRACKETS.matcher(name).replaceAll(" ")).replaceAll(" ");

        // 4. Extras : seulement pour un fichier sans numéro d'après 1 à 3
        Matcher marker = EXTRA_MARKER.matcher(cleaned);
        if (marker.find()) {
            return new Extra(title, marker.group().trim());
        }
        for (String dir : dirs) {
            if (EXTRA_DIR.matcher(dir.trim()).matches()) {
                return new Extra(title, "dossier " + dir.trim());
            }
        }

        // 5. Numéro seul
        Matcher dbl = DOUBLE_NUMBER.matcher(cleaned);
        if (dbl.find()) {
            return new Unresolved(title, Problem.MULTI_EPISODE, dbl.group());
        }
        Matcher dec = DECIMAL_NUMBER.matcher(cleaned);
        if (dec.find()) {
            return new Unresolved(title, Problem.DECIMAL_EPISODE, dec.group());
        }
        Matcher c = CANDIDATE.matcher(" " + cleaned);
        String last = null;
        while (c.find()) {
            last = c.group(1);
        }
        if (last != null) {
            Season season = fallbackSeason(name, dirs);
            return new Episode(title, season.number(), Integer.parseInt(last), Strategy.NUMBER_ONLY, season.source(), null);
        }
        return new Unresolved(title, Problem.NO_EPISODE_NUMBER, "aucun numéro d'épisode reconnu");
    }

    /** Stratégies 1 à 3 : décimal et double épisode vont au rapport ; désaccord de saison signalé (1 et 2). */
    /** Numéro de saison et son origine. */
    private record Season(int number, SeasonSource source) {
    }

    private ParseResult explicit(String title, List<String> dirs, String name, Matcher m, Season season,
                                 int episodeGroup, int decimalGroup, Strategy strategy) {
        if (m.group(decimalGroup) != null) {
            return new Unresolved(title, Problem.DECIMAL_EPISODE, m.group());
        }
        int episode = Integer.parseInt(m.group(episodeGroup));
        Matcher next = FOLLOWING_EPISODE.matcher(name.substring(m.end()));
        if (next.find()) {
            int second = Integer.parseInt(next.group(1));
            if (second > episode && second <= episode + 3) {
                return new Unresolved(title, Problem.MULTI_EPISODE, m.group() + next.group().trim());
            }
        }
        Integer conflict = null;
        if (strategy != Strategy.E_NUMBER) {
            Optional<Integer> folder = seasonFolder(dirs);
            if (folder.isPresent() && folder.get() != season.number()) {
                conflict = folder.get();
            }
        }
        return new Episode(title, season.number(), episode, strategy, season.source(), conflict);
    }

    /** Saison pour les stratégies 3 et 5 : "S1" isolé dans le nom, sinon dossier (saison ou spéciaux), sinon 1. */
    private Season fallbackSeason(String name, List<String> dirs) {
        Matcher s = SEASON_IN_NAME.matcher(name);
        if (s.find()) {
            return new Season(Integer.parseInt(s.group(1)), SeasonSource.NAME_S);
        }
        for (int i = dirs.size() - 1; i >= 0; i--) {
            String dir = dirs.get(i).trim();
            Matcher d = SEASON_DIR.matcher(dir);
            if (d.find()) {
                return new Season(Integer.parseInt(d.group(1)), SeasonSource.FOLDER);
            }
            if (SPECIAL_DIR.matcher(dir).find()) {
                return new Season(0, SeasonSource.SPECIAL_FOLDER);
            }
        }
        return new Season(1, SeasonSource.DEFAULT);
    }

    /** Dossier de saison le plus proche du fichier (Season 2, Saison 02, S2), pour la vérification. */
    private static Optional<Integer> seasonFolder(List<String> dirs) {
        for (int i = dirs.size() - 1; i >= 0; i--) {
            Matcher d = SEASON_DIR.matcher(dirs.get(i).trim());
            if (d.find()) {
                return Optional.of(Integer.parseInt(d.group(1)));
            }
        }
        return Optional.empty();
    }

    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
