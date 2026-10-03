package fr.plexwish.animeserver.tmdb;

import fr.plexwish.animeserver.metadata.TitleMatcher;
import fr.plexwish.animeserver.tmdb.TmdbClient.Result;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Appariement d'un animé avec TMDB (ARCHITECTURE §16.5) à partir de ce que l'on sait déjà : titres AniList (romaji,
 * anglais, japonais), année, titre du dossier. Mêmes seuils que pour AniList (§15.2). TMDB regroupe les saisons en
 * une série : les mentions de saison (« Season 2 », « 2nd Season », « Part 2 », « II ») sont retirées, et une fiche
 * AniList de suite ne pénalise pas une série TMDB commencée plus tôt.
 */
public final class TmdbMatcher {

    private static final Pattern SEASON = Pattern.compile(
            "(?i)[\\s:\\-]*(?:(?:season|saison|cour|part)\\s*\\d+|\\d+(?:st|nd|rd|th)\\s+season|final\\s+season|"
                    + "\\b(?:II|III|IV|V)\\b|\\s\\d)\\s*$");

    private TmdbMatcher() {
    }

    /** Ce que l'on cherche : titres connus, année, type (tv / movie), et s'il s'agit d'une suite. */
    public record Query(List<String> titles, Integer year, String type, boolean sequel) {
    }

    public static Query query(List<String> knownTitles, Integer year, String type) {
        Set<String> titles = new LinkedHashSet<>();
        boolean sequel = false;
        for (String t : knownTitles) {
            if (t == null || t.isBlank()) {
                continue;
            }
            String base = withoutSeason(t);
            if (!base.equals(t.trim())) {
                sequel = true;
            }
            titles.add(base);
            titles.add(t.trim());
        }
        return new Query(new ArrayList<>(titles), year, type, sequel);
    }

    static String withoutSeason(String title) {
        String s = title.trim();
        for (int i = 0; i < 3; i++) {
            String next = SEASON.matcher(s).replaceAll("").trim();
            if (next.equals(s) || next.isEmpty()) {
                break;
            }
            s = next;
        }
        return s;
    }

    public record Scored(Result result, double titleScore, double rankScore) {
    }

    public record Decision(TitleMatcher.Status status, TitleMatcher.Reason reason, Scored best, List<Scored> ranked) {
    }

    static double titleScore(Query q, Result r) {
        double best = 0;
        for (String t : q.titles()) {
            String nt = TitleMatcher.normalize(t);
            for (String c : new String[]{r.name(), r.originalName()}) {
                if (c != null) {
                    best = Math.max(best, TitleMatcher.similarity(nt, TitleMatcher.normalize(c)));
                }
            }
        }
        return best;
    }

    /** Titre + départages : animation (genre 16), année (une série commence au plus tard l'année de la saison). */
    static double rankScore(Query q, Result r) {
        double s = titleScore(q, r);
        s += r.animation() ? 0.03 : -0.05;
        if (q.year() != null && r.year() != null) {
            int diff = r.year() - q.year();
            if (diff == 0) {
                s += 0.05;
            } else if (diff > 1) {
                s -= 0.10; // série commencée après notre saison : autre œuvre
            } else if (diff < -1 && !q.sequel()) {
                s -= 0.05; // série plus ancienne alors que notre fiche est une première saison : remake ?
            }
        }
        return s;
    }

    public static Decision decide(Query q, List<Result> results) {
        List<Scored> ranked = new ArrayList<>();
        for (Result r : results) {
            ranked.add(new Scored(r, titleScore(q, r), rankScore(q, r)));
        }
        ranked.sort(Comparator.comparingDouble(Scored::rankScore).reversed());
        if (ranked.isEmpty()) {
            return new Decision(TitleMatcher.Status.UNMATCHED, TitleMatcher.Reason.NO_RESULT, null, ranked);
        }
        Scored best = ranked.get(0);
        if (best.titleScore() < TitleMatcher.MIN_SCORE) {
            return new Decision(TitleMatcher.Status.UNMATCHED, TitleMatcher.Reason.LOW_SCORE, null, ranked);
        }
        Scored second = ranked.size() > 1 ? ranked.get(1) : null;
        if (second != null && second.titleScore() >= TitleMatcher.MIN_SCORE
                && best.rankScore() - second.rankScore() < TitleMatcher.TIE) {
            return new Decision(TitleMatcher.Status.UNMATCHED, TitleMatcher.Reason.AMBIGUOUS, null, ranked);
        }
        boolean exact = best.titleScore() >= 0.999;
        boolean clear = second == null || best.rankScore() - second.rankScore() >= TitleMatcher.CONFIDENT_MARGIN
                || (exact && second.titleScore() < 0.999);
        if (best.titleScore() >= TitleMatcher.CONFIDENT_SCORE && clear && best.result().animation()) {
            return new Decision(TitleMatcher.Status.MATCHED, null, best, ranked);
        }
        return new Decision(TitleMatcher.Status.DOUBTFUL, best.titleScore() >= TitleMatcher.CONFIDENT_SCORE
                ? TitleMatcher.Reason.CLOSE_CANDIDATE : TitleMatcher.Reason.BELOW_CONFIDENT, best, ranked);
    }
}
