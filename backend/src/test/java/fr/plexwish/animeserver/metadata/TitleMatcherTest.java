package fr.plexwish.animeserver.metadata;

import fr.plexwish.animeserver.metadata.MetadataProvider.Candidate;
import fr.plexwish.animeserver.metadata.TitleMatcher.Decision;
import fr.plexwish.animeserver.metadata.TitleMatcher.Query;
import fr.plexwish.animeserver.metadata.TitleMatcher.Reason;
import fr.plexwish.animeserver.metadata.TitleMatcher.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Appariement par titre, sans réseau : nettoyage du dossier, similarité, seuils, départages. */
class TitleMatcherTest {

    static Candidate c(String id, String romaji, String english, Integer year, String format, Integer episodes, String... synonyms) {
        return new Candidate(id, romaji, english, null, List.of(synonyms), year, format, episodes, null, null, null, null);
    }

    static Decision decide(String folder, Integer episodes, Candidate... candidates) {
        return TitleMatcher.decide(TitleMatcher.clean(folder), List.of(candidates), episodes);
    }

    // --- Nettoyage du nom de dossier --------------------------------------------------------------

    @Test
    void technicalMentionsYearAndParenthesesAreHandled() {
        Query q = TitleMatcher.clean("Full Dive - The Ultimate Next-Gen Full Dive RPG Is Even Shittier than Real Life S01 VOSTFR 1080p WEB x264 AAC -Tsundere-Raws (CR)");
        assertEquals("Full Dive - The Ultimate Next-Gen Full Dive RPG Is Even Shittier than Real Life", q.main());
        assertEquals(List.of(q.main()), q.variants());
        assertNull(q.year());

        Query illya = TitleMatcher.clean("Fate⁄kaleid liner Prisma Illya (2013-2016)");
        assertEquals("Fate⁄kaleid liner Prisma Illya", illya.main());
        assertEquals(2013, illya.year());

        Query alt = TitleMatcher.clean("Rumbling Hearts (Kimi ga Nozomu Eien)");
        assertEquals(List.of("Rumbling Hearts", "Kimi ga Nozomu Eien"), alt.variants());

        assertEquals("Kono Bijutsubu ni wa Mondai ga Aru!", TitleMatcher.clean("Kono Bijutsubu ni wa Mondai ga Aru! VOSTFR (BD 1080p)").main());
        assertEquals("Level 1 Demon Lord and One Room Hero",
                TitleMatcher.clean("Level 1 Demon Lord and One Room Hero S01E01-06 VOSTFR 1080p WEB x264 AAC -Tsundere-Raws (ADN)").main());
        assertEquals("Hunter x Hunter", TitleMatcher.clean("Hunter x Hunter (2011)").main());
        assertEquals(2011, TitleMatcher.clean("Hunter x Hunter (2011)").year());
    }

    @Test
    void normalizationIgnoresAccentsPunctuationAndRomanizationVariants() {
        assertEquals(TitleMatcher.normalize("Chuunibyou demo Koi ga Shitai!"), TitleMatcher.normalize("Chûnibyô Demo Koi ga Shitai!"));
        assertEquals(TitleMatcher.normalize("Dekiru Neko wa Kyou mo Yuuutsu"), TitleMatcher.normalize("Dekiru Neko ha Kyō mo Yūutsu"));
        assertEquals(TitleMatcher.normalize("Kono Yo no Hate de Koi wo Utau Shoujo YU-NO"),
                TitleMatcher.normalize("Kono Yo no Hate de Koi o Utau Shōjo YU-NO"));
        assertEquals(TitleMatcher.normalize("Fate/Apocrypha"), TitleMatcher.normalize("Fate⁄Apocrypha"));
        assertEquals(TitleMatcher.normalize("Cube x Cursed x Curious"), TitleMatcher.normalize("Cube × Cursed × Curious"));
    }

    // --- Décisions ----------------------------------------------------------------------------------

    @Test
    void exactMatch() {
        Decision d = decide("Sousou no Frieren", 28,
                c("154587", "Sousou no Frieren", "Frieren: Beyond Journey's End", 2023, "TV", 28),
                c("170068", "Sousou no Frieren: ●● no Mahou", null, 2023, "ONA", 10),
                c("182255", "Sousou no Frieren 2nd Season", "Frieren: Beyond Journey's End Season 2", 2026, "TV", 10));
        assertEquals(Status.MATCHED, d.status());
        assertEquals("154587", d.best().candidate().providerId());
    }

    @Test
    void accentsAndRomanizationStillMatchExactly() {
        Decision d = decide("Chûnibyô Demo Koi ga Shitai!", 12,
                c("14741", "Chuunibyou demo Koi ga Shitai!", "Love, Chunibyo & Other Delusions!", 2012, "TV", 12),
                c("18671", "Chuunibyou demo Koi ga Shitai! Ren", "Love, Chunibyo & Other Delusions! -Heart Throb-", 2014, "TV", 12));
        assertEquals(Status.MATCHED, d.status());
        assertEquals("14741", d.best().candidate().providerId());
        assertEquals(1.0, d.best().titleScore(), 1e-9);
    }

    @Test
    void separateSeasonsOnAniListPickTheFirstSeasonForAMultiSeasonFolder() {
        Decision d = decide("Shingeki no Kyojin", 87,
                c("20958", "Shingeki no Kyojin Season 2", "Attack on Titan Season 2", 2017, "TV", 12),
                c("16498", "Shingeki no Kyojin", "Attack on Titan", 2013, "TV", 25),
                c("99147", "Shingeki no Kyojin Season 3", "Attack on Titan Season 3", 2018, "TV", 12));
        assertEquals(Status.MATCHED, d.status());
        assertEquals("16498", d.best().candidate().providerId());
    }

    @Test
    void sameTitleRemakeWithoutAnyHintStaysUnmatched() {
        Candidate y1999 = c("136", "HUNTER×HUNTER", "Hunter x Hunter", 1999, "TV", 62);
        Candidate y2011 = c("11061", "HUNTER×HUNTER (2011)", "Hunter x Hunter (2011)", 2011, "TV", 148, "HUNTER×HUNTER");
        Decision d = decide("Hunter x Hunter", null, y1999, y2011);
        assertEquals(Status.UNMATCHED, d.status());
        assertEquals(Reason.AMBIGUOUS, d.reason());
        assertNull(d.best());
        assertEquals(2, d.ranked().size(), "les candidats restent proposés à l'admin");
    }

    @Test
    void remakeIsResolvedByTheYearInTheFolderName() {
        Candidate y1999 = c("136", "HUNTER×HUNTER", "Hunter x Hunter", 1999, "TV", 62);
        Candidate y2011 = c("11061", "HUNTER×HUNTER (2011)", "Hunter x Hunter (2011)", 2011, "TV", 148, "HUNTER×HUNTER");
        Decision d = decide("Hunter x Hunter (2011)", null, y1999, y2011);
        assertEquals(Status.MATCHED, d.status());
        assertEquals("11061", d.best().candidate().providerId());
    }

    @Test
    void remakeWithOnlyTheEpisodeCountIsAppliedButDoubtful() {
        Candidate y1999 = c("136", "HUNTER×HUNTER", "Hunter x Hunter", 1999, "TV", 62);
        Candidate y2011 = c("11061", "HUNTER×HUNTER (2011)", "Hunter x Hunter (2011)", 2011, "TV", 148, "HUNTER×HUNTER");
        Decision d = decide("Hunter x Hunter", 148, y1999, y2011);
        assertEquals(Status.DOUBTFUL, d.status());
        assertEquals("11061", d.best().candidate().providerId());
    }

    @Test
    void closeButDifferentTitlesStayUnderTheThreshold() {
        Decision d = decide("Kino no Tabi", 13,
                c("1", "Kino no Tabi: The Beautiful World - The Animated Series", "Kino's Journey -the Beautiful World- the Animated Series", 2017, "TV", 12),
                c("2", "Kino no Tabi: Nanika wo Suru Tame ni - Life Goes On", null, 2005, "MOVIE", 1));
        assertEquals(Status.UNMATCHED, d.status());
        assertEquals(Reason.LOW_SCORE, d.reason());
    }

    @Test
    void frenchFolderTitleWithoutResultsIsUnmatched() {
        Decision d = decide("Le Seigneur des Yôkai", 26);
        assertEquals(Status.UNMATCHED, d.status());
        assertEquals(Reason.NO_RESULT, d.reason());
    }

    @Test
    void englishTitleMatchesAndAlternativeTitleInParenthesesHelps() {
        Decision d = decide("Rumbling Hearts (Kimi ga Nozomu Eien)", 14,
                c("378", "Kimi ga Nozomu Eien", "Rumbling Hearts", 2003, "TV", 14));
        assertEquals(Status.MATCHED, d.status());
        Decision byAlt = decide("Three Leaves, Three Colors (Sansha Sanyou)", 12,
                c("21857", "Sansha Sanyou", "Three Leaves, Three Colors", 2016, "TV", 12));
        assertEquals(Status.MATCHED, byAlt.status());
    }

    @Test
    void slightlyDifferentRomanizationIsMatched() {
        Decision d = decide("Naruto Shippuden", 500,
                c("1735", "Naruto: Shippuuden", "Naruto Shippuden", 2007, "TV", 500));
        assertEquals(Status.MATCHED, d.status());
        Decision approx = decide("Kami-tachi ni Hirowareta Otoko (By the Grace of the Gods)", 12,
                c("110146", "Kami-tachi ni Hirowareta Otoko", "By the Grace of the Gods", 2020, "TV", 12));
        assertEquals(Status.MATCHED, approx.status());
    }

    @Test
    void mediumSimilarityIsDoubtfulNotMatched() {
        // Titre proche mais pas identique (sous-titre manquant dans le dossier).
        Decision d = decide("Ore ga Ojou-sama Gakkou ni Shomin Sample Toshite Gets Sareta Ken", 12,
                c("21385", "Ore ga Ojou-sama Gakkou ni \"Shomin Sample\" Toshite Gets♥Sareta Ken", "Shomin Sample", 2015, "TV", 12));
        assertTrue(d.status() == Status.MATCHED || d.status() == Status.DOUBTFUL, d.toString());
        Decision partial = decide("Mahou Shoujo Site", 12,
                c("1", "Mahou Shoujo Ikusei Keikaku", "Magical Girl Raising Project", 2016, "TV", 12));
        assertEquals(Status.UNMATCHED, partial.status());
    }
}
