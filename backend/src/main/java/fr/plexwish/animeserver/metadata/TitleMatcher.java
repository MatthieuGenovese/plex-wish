package fr.plexwish.animeserver.metadata;

import fr.plexwish.animeserver.metadata.MetadataProvider.Candidate;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Appariement d'un dossier d'animé avec les fiches du fournisseur (ARCHITECTURE §15.2). Fonctions pures, testées
 * sans réseau.
 * <ol>
 *     <li>Le titre du dossier est nettoyé : mentions techniques (S01, VOSTFR, 1080p, groupe…) retirées, année
 *     « (2019) » extraite, titre entre parenthèses gardé comme variante (« Rumbling Hearts (Kimi ga Nozomu Eien) »).</li>
 *     <li>Chaque candidat reçoit un score de similarité de titre (0 à 1) : meilleur de ses titres (romaji, anglais,
 *     synonymes) contre chaque variante, après normalisation (accents, ponctuation, variantes de romanisation :
 *     « Shōjo » = « Shoujo », « wo » = « o »).</li>
 *     <li>Départage : année connue (bonus si égale, malus si différente), nombre d'épisodes local proche, format TV.</li>
 *     <li>Décision : sous le seuil, ou deux fiches indiscernables (remakes homonymes sans année), l'animé reste
 *     non apparié plutôt que d'être associé au hasard.</li>
 * </ol>
 */
public final class TitleMatcher {

    /** En dessous : non apparié. */
    public static final double MIN_SCORE = 0.80;
    /** Au-dessus, avec une avance suffisante sur le suivant : apparié sans réserve. Entre les deux : douteux. */
    public static final double CONFIDENT_SCORE = 0.92;
    public static final double CONFIDENT_MARGIN = 0.05;
    /** Écart en dessous duquel deux fiches sont indiscernables (ex. remake homonyme sans année connue). */
    public static final double TIE = 0.01;

    private static final Pattern YEAR = Pattern.compile("\\((\\d{4})(?:\\s*-\\s*\\d{4})?\\)");
    private static final Pattern PARENS = Pattern.compile("\\(([^()]*)\\)|\\[([^\\[\\]]*)]");
    /** À partir de la première mention technique, le reste du nom du dossier est ignoré. */
    private static final Pattern TECHNICAL = Pattern.compile(
            "(?i)(?:^|\\s)(?:S\\d{1,2}(?:E\\d+(?:-\\d+)?)?|VOSTFR|VOSTA|VF|VO|MULTI|\\d{3,4}p|WEB(?:-?DL|RIP)?|BD(?:RIP)?|"
                    + "BLU-?RAY|x26[45]|HEVC|AAC|FLAC|INT[ÉE]GRALE|COMPLETE)(?=\\s|$|[-_.()\\[\\]])");
    /** Contenu de parenthèses purement technique : (CR), (ADN), (BD 1080p)… */
    private static final Pattern TECHNICAL_ONLY = Pattern.compile("(?i)^(?:CR|ADN|CUSTOM|BD|WEB|NF|AMZN|DSNP|\\d{3,4}p|\\s|VOSTFR|VF)+$");

    private TitleMatcher() {
    }

    /** Titre du dossier nettoyé : variante principale, variantes secondaires, année éventuelle. */
    public record Query(String main, List<String> variants, Integer year) {
    }

    public static Query clean(String folderTitle) {
        String s = folderTitle.trim();
        Integer year = null;
        Matcher y = YEAR.matcher(s);
        if (y.find()) {
            year = Integer.parseInt(y.group(1));
            s = (s.substring(0, y.start()) + " " + s.substring(y.end())).trim();
        }
        List<String> variants = new ArrayList<>();
        Matcher p = PARENS.matcher(s);
        StringBuilder rest = new StringBuilder();
        int last = 0;
        while (p.find()) {
            String inside = (p.group(1) != null ? p.group(1) : p.group(2)).trim();
            if (!inside.isEmpty() && !TECHNICAL_ONLY.matcher(inside).matches() && !TECHNICAL.matcher(inside).find()) {
                variants.add(inside);
            }
            rest.append(s, last, p.start()).append(' ');
            last = p.end();
        }
        rest.append(s.substring(last));
        s = rest.toString();
        Matcher t = TECHNICAL.matcher(s);
        if (t.find() && t.start() > 0) {
            s = s.substring(0, t.start());
        }
        s = s.replaceAll("\\s+-\\s*$", "").replaceAll("\\s+", " ").trim();
        String main = s.isEmpty() ? folderTitle.trim() : s;
        List<String> all = new ArrayList<>();
        all.add(main);
        all.addAll(variants);
        return new Query(main, all, year);
    }

    /**
     * Forme de comparaison : sans accents ni ponctuation, minuscules, variantes de romanisation ramenées à une
     * seule (voyelles longues « ou / uu / ō », particules « wo » → « o » et « ha » → « wa »).
     */
    public static String normalize(String title) {
        String s = Normalizer.normalize(title.replace("×", " x ").replace('⁄', ' '), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        s = (" " + s + " ").replace(" wo ", " o ").replace(" ha ", " wa ").trim();
        s = s.replaceAll("(\\p{L})\\1+", "$1").replace("ou", "o").replaceAll("(\\p{L})\\1+", "$1");
        return s;
    }

    /** Similarité de deux titres déjà normalisés : la meilleure entre la chaîne entière et les mots triés. */
    public static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1;
        }
        return Math.max(ratio(a, b), ratio(sortedWords(a), sortedWords(b)));
    }

    /** Score de titre seul : meilleur couple (variante du dossier, titre du candidat). */
    public static double titleScore(Query query, Candidate c) {
        double best = 0;
        for (String v : query.variants()) {
            String nv = normalize(v);
            for (String t : c.titles()) {
                best = Math.max(best, similarity(nv, normalize(t)));
            }
        }
        return best;
    }

    /** Score de classement : titre + départages (année, nombre d'épisodes, format). */
    public static double rankScore(Query query, Candidate c, Integer localEpisodes) {
        double s = titleScore(query, c);
        if (query.year() != null && c.year() != null) {
            int diff = Math.abs(query.year() - c.year());
            s += diff == 0 ? 0.05 : diff == 1 ? 0 : -0.10;
        }
        if (localEpisodes != null && localEpisodes > 0 && c.episodes() != null && c.episodes() > 0
                && Math.abs(c.episodes() - localEpisodes) <= Math.max(1, Math.round(localEpisodes * 0.1))) {
            s += 0.02;
        }
        if ("TV".equals(c.format())) {
            s += 0.01;
        }
        return s;
    }

    public enum Status { MATCHED, DOUBTFUL, UNMATCHED }

    /** Raison d'un non-appariement ou d'un doute, pour l'admin. */
    public enum Reason { NO_RESULT, LOW_SCORE, AMBIGUOUS, CLOSE_CANDIDATE, BELOW_CONFIDENT }

    public record Scored(Candidate candidate, double titleScore, double rankScore) {
    }

    public record Decision(Status status, Reason reason, Scored best, List<Scored> ranked) {
    }

    public static Decision decide(Query query, List<Candidate> candidates, Integer localEpisodes) {
        List<Scored> ranked = new ArrayList<>();
        for (Candidate c : candidates) {
            ranked.add(new Scored(c, titleScore(query, c), rankScore(query, c, localEpisodes)));
        }
        ranked.sort(Comparator.comparingDouble(Scored::rankScore).reversed());
        if (ranked.isEmpty()) {
            return new Decision(Status.UNMATCHED, Reason.NO_RESULT, null, ranked);
        }
        Scored best = ranked.get(0);
        if (best.titleScore() < MIN_SCORE) {
            return new Decision(Status.UNMATCHED, Reason.LOW_SCORE, null, ranked);
        }
        Scored second = ranked.size() > 1 ? ranked.get(1) : null;
        if (second != null && second.titleScore() >= MIN_SCORE && best.rankScore() - second.rankScore() < TIE) {
            // Deux fiches au même titre que rien ne départage (remake homonyme sans année) : on ne choisit pas au hasard.
            return new Decision(Status.UNMATCHED, Reason.AMBIGUOUS, null, ranked);
        }
        boolean exact = best.titleScore() >= 0.999;
        boolean clear = second == null || best.rankScore() - second.rankScore() >= CONFIDENT_MARGIN
                || (exact && second.titleScore() < 0.999);
        if (best.titleScore() >= CONFIDENT_SCORE && clear) {
            return new Decision(Status.MATCHED, null, best, ranked);
        }
        return new Decision(Status.DOUBTFUL, best.titleScore() >= CONFIDENT_SCORE ? Reason.CLOSE_CANDIDATE : Reason.BELOW_CONFIDENT,
                best, ranked);
    }

    // --- Similarité ------------------------------------------------------------------------------

    private static String sortedWords(String s) {
        String[] words = s.split(" ");
        java.util.Arrays.sort(words);
        return String.join(" ", words);
    }

    /** 1 - distance de Levenshtein / longueur de la plus longue chaîne. */
    static double ratio(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - (double) prev[b.length()] / Math.max(a.length(), b.length());
    }
}
