package fr.plexwish.animeserver.library.parse;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Noms des sous-titres externes (1.3.0, ARCHITECTURE §7.8) : ce que le nom d'un {@code .ass} dit de la vidéo qu'il
 * accompagne. Pur calcul sur des chaînes, comme le parser des vidéos.
 * <ul>
 *     <li>{@link #stem} : nom sans extension ni suffixes de langue / forcés ({@code .fre}, {@code .forced}…), à
 *     comparer au nom de la vidéo du même dossier ;</li>
 *     <li>{@link #parserPath} : chemin à donner au parser des vidéos, réécrit pour les formes vues sur la vraie
 *     bibliothèque (« Épisode 7 », « E01ts », « saison 2 », saison dans un dossier « Tokyo Ghoul S1 ») ;</li>
 *     <li>{@link #forced}, {@link #language} : piste « forcés » (panneaux et chansons), langue si le nom la donne.</li>
 * </ul>
 */
public final class SubtitleNames {

    /** Extensions associées (ASS / SSA : rendus avec leurs styles ; les autres formats restent comptés seulement). */
    public static final Set<String> EXTENSIONS = Set.of("ass", "ssa");
    public static final Set<String> FONT_EXTENSIONS = Set.of("ttf", "otf", "ttc");

    private static final Pattern SUFFIX = Pattern.compile(
            "(?i)\\.(fr|fre|fra|french|vf|vostfr|en|eng|english|ja|jpn|forced|forces|forcés|full|complet|default|signs)$");
    private static final Pattern FORCED = Pattern.compile("(?i)(?<![\\p{L}0-9])(forced|forcés?)(?![\\p{L}0-9])");
    private static final Pattern FRENCH = Pattern.compile("(?i)(?<![\\p{L}0-9])(fr|fre|fra|french|vf|vostfr)(?![\\p{L}0-9])");
    private static final Pattern ENGLISH = Pattern.compile("(?i)(?<![\\p{L}0-9])(en|eng|english)(?![\\p{L}0-9])");
    private static final Pattern EPISODE_WORD = Pattern.compile("(?iu)(?<![\\p{L}])[ée]pisode[ ._-]*(\\d{1,4})(?![0-9])");
    private static final Pattern SEASON_WORD = Pattern.compile("(?iu)(?<![\\p{L}])(?:saison|season)[ ._-]*0*(\\d{1,2})(?![0-9])");
    /** Saison déjà dans le nom : SxxExx, NxEE ou « S2 » isolé (règles du parser des vidéos). */
    private static final Pattern HAS_SEASON = Pattern.compile(
            "(?i)(?<![a-z0-9])S\\d{1,2}(?:[ ._-]?E\\d{1,4})?(?![a-z0-9])|(?<![a-z0-9])S\\d{1,2}E\\d|(?<![0-9])\\d{1,2}x\\d{2,3}(?![0-9])");
    /** Dossier qui porte une saison n'importe où dans son nom : « Tokyo Ghoul S1 », « Blue Box S1 - (Sub+Font) ». */
    private static final Pattern SEASON_ANYWHERE = Pattern.compile("(?iu)(?<![\\p{L}0-9])(?:s|saison|season)[ ._-]*0*(\\d{1,2})(?![\\p{L}0-9])");
    private static final Pattern E_WITH_SUFFIX = Pattern.compile("(?i)(?<![\\p{L}0-9])E(\\d{1,4})[a-z]{1,4}$");

    private SubtitleNames() {
    }

    public static boolean isSubtitle(String fileName) {
        return EXTENSIONS.contains(LibraryFiles.extension(fileName));
    }

    public static boolean isFont(String fileName) {
        return FONT_EXTENSIONS.contains(LibraryFiles.extension(fileName));
    }

    /** Nom sans extension ni suffixes « .fre », « .forced »… : « Ep 01.fre.forced.ass » → « Ep 01 ». */
    public static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String s = dot > 0 ? fileName.substring(0, dot) : fileName;
        for (Matcher m = SUFFIX.matcher(s); m.find(); m = SUFFIX.matcher(s)) {
            s = s.substring(0, m.start());
        }
        return s;
    }

    /** Nom de vidéo sans extension, pour la comparaison « même nom » (casse indifférente). */
    public static String videoStem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot > 0 ? fileName.substring(0, dot) : fileName).toLowerCase(Locale.ROOT);
    }

    public static String sameNameKey(String fileName) {
        return stem(fileName).toLowerCase(Locale.ROOT);
    }

    public static boolean forced(String fileName) {
        return FORCED.matcher(stemWithSuffixes(fileName)).find();
    }

    /** Langue (code ISO 639-2) si le nom la donne, sinon vide. */
    public static Optional<String> language(String fileName) {
        String s = stemWithSuffixes(fileName);
        if (FRENCH.matcher(s).find()) {
            return Optional.of("fre");
        }
        if (ENGLISH.matcher(s).find()) {
            return Optional.of("eng");
        }
        return Optional.empty();
    }

    /**
     * Chemin à donner au parser des vidéos, réécrit pour les formes vues à côté des vraies vidéos : suffixes de langue
     * retirés, « Épisode 7 » lu comme {@code E7} (un seul chiffre échappe à la stratégie « numéro seul »), {@code E01ts}
     * comme {@code E01}, « saison 2 » comme {@code S2} ; sans saison dans le nom, celle d'un dossier qui la porte
     * (« Tokyo Ghoul S1 », « Saison 3 ») est ajoutée au nom : saison du nom, sinon du dossier, sinon 1.
     */
    public static String parserPath(String relativePath) {
        String path = Normalizer.normalize(relativePath, Normalizer.Form.NFC);
        int slash = path.lastIndexOf('/');
        String dir = path.substring(0, slash + 1);
        String name = stem(path.substring(slash + 1));
        name = EPISODE_WORD.matcher(name).replaceAll("E$1");
        name = E_WITH_SUFFIX.matcher(name).replaceAll("E$1");
        name = SEASON_WORD.matcher(name).replaceAll("S$1");
        if (!HAS_SEASON.matcher(name).find()) {
            String[] dirs = dir.split("/");
            for (int i = dirs.length - 1; i >= 1; i--) {
                Matcher m = SEASON_ANYWHERE.matcher(dirs[i]);
                if (m.find()) {
                    name = name + " S" + Integer.parseInt(m.group(1));
                    break;
                }
            }
        }
        return dir + name + ".ass";
    }

    private static String stemWithSuffixes(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
