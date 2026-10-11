package fr.plexwish.animeserver.library.subs;

import fr.plexwish.animeserver.library.parse.DefaultFilenameParser;
import fr.plexwish.animeserver.library.parse.LibraryFiles;
import fr.plexwish.animeserver.library.parse.ParseResult;
import fr.plexwish.animeserver.library.parse.SubtitleNames;
import fr.plexwish.animeserver.library.scan.Titles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Association des sous-titres externes sur la vraie bibliothèque (library-sample.txt : 402 .ass dans 12 animés), sans
 * base : les épisodes sont ceux que le parser tire des vidéos de l'échantillon.
 */
class ExternalSubtitleLinkTest {

    static List<String> paths;
    static ExternalSubtitleService service;
    static ExternalSubtitleService.Lookup lookup;
    /** Épisode (titre normalisé|saison|épisode) de chaque identifiant. */
    static Map<Long, String> names = new HashMap<>();

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = ExternalSubtitleLinkTest.class.getResourceAsStream("/library-sample.txt")) {
            paths = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        }
        DefaultFilenameParser parser = new DefaultFilenameParser();
        service = new ExternalSubtitleService();
        service.parser = parser;
        Map<String, Long> episodes = new HashMap<>();
        Map<String, Long> sameName = new HashMap<>();
        Map<String, Integer> last = new HashMap<>();
        for (String p : paths) {
            if (LibraryFiles.typeOfPath(p) != LibraryFiles.Type.VIDEO) {
                continue;
            }
            if (parser.parse(p) instanceof ParseResult.Episode e) {
                String key = Titles.normalize(e.animeTitle()) + "|" + e.season() + "|" + e.episode();
                Long id = episodes.computeIfAbsent(key, k -> (long) episodes.size() + 1);
                names.put(id, key);
                last.merge(Titles.normalize(e.animeTitle()) + "|" + e.season(), e.episode(), Math::max);
                int slash = p.lastIndexOf('/');
                sameName.put(p.substring(0, slash) + '\0' + SubtitleNames.videoStem(p.substring(slash + 1)), id);
            }
        }
        lookup = new ExternalSubtitleService.Lookup() {
            @Override
            public Long sameName(String dir, String stem) {
                return sameName.get(dir + '\0' + stem);
            }

            @Override
            public Long episode(String normalizedTitle, int season, int episode) {
                return episodes.get(normalizedTitle + "|" + season + "|" + episode);
            }

            @Override
            public Integer lastEpisode(String normalizedTitle, int season) {
                return last.get(normalizedTitle + "|" + season);
            }
        };
    }

    private static String target(String path) {
        ExternalSubtitleService.Link l = service.link(path, lookup);
        return l.episodeId() == null ? null : names.get(l.episodeId()) + " " + l.match();
    }

    private static String episode(String title, int season, int episode, String match) {
        return Titles.normalize(title) + "|" + season + "|" + episode + " " + match;
    }

    @Test
    void everyAssOfTheRealLibraryFindsItsEpisode() {
        List<String> subs = paths.stream().filter(p -> SubtitleNames.isSubtitle(p.substring(p.lastIndexOf('/') + 1))).toList();
        assertEquals(402, subs.size());
        Map<String, Integer> unmatched = new TreeMap<>();
        Map<String, Integer> byMatch = new TreeMap<>();
        for (String s : subs) {
            ExternalSubtitleService.Link l = service.link(s, lookup);
            if (l.episodeId() == null) {
                unmatched.merge(s, 1, Integer::sum);
            } else {
                byMatch.merge(l.match(), 1, Integer::sum);
            }
        }
        System.out.println("Sous-titres externes : " + byMatch + ", sans épisode : " + unmatched.keySet());
        assertTrue(unmatched.isEmpty(), "sans épisode : " + unmatched.keySet());
    }

    @Test
    void realCases() {
        assertEquals(episode("Sword Art Online", 4, 1, "SAME_NAME"),
                target("Sword Art Online/SAO S4/Sword Art Online - 4x01 - Dans les terres septentrionales.ass"));
        // Dossier séparé, « Épisode 27 » : saison 1 (rien dans le nom ni le dossier), épisode 27.
        assertEquals(episode("Captain Tsubasa", 1, 27, "NAME"),
                target("Captain Tsubasa/sous-titres + police/[sekkusu&ok] Captain Tsubasa - Épisode 27.ass"));
        assertEquals(episode("Captain Tsubasa", 1, 1, "NAME"),
                target("Captain Tsubasa/sous-titres + police/[sekkusu&ok] Captain Tsubasa - Épisode 1.forced.ass"));
        // Saison du dossier « Tokyo Ghoul S1 », « Episode 1 » à un chiffre.
        assertEquals(episode("TOKYO GHOUL", 1, 1, "NAME"),
                target("TOKYO GHOUL/Tokyo Ghoul S1/sous-titres + police/[sekkusu&ok] Tokyo Ghoul - Episode 1.ass"));
        // « saison 2 » dans le nom ; numérotation continue : « saison 3 - Episode 13 » = 1er épisode de la saison 4.
        assertEquals(episode("TOKYO GHOUL", 2, 1, "NAME"),
                target("TOKYO GHOUL/Tokyo Ghoul S2/sous-titres + police/[sekkusu&ok] Tokyo Ghoul √A - saison 2 - Episode 1.ass"));
        assertEquals(episode("TOKYO GHOUL", 4, 1, "NAME"),
                target("TOKYO GHOUL/Tokyo Ghoul S3/sous-titres + police/[sekkusu&ok] Tokyo Ghoul-re - saison 3 - Episode 13.ass"));
        assertEquals(episode("Toaru Kagaku no Railgun", 3, 1, "NAME"), target("Toaru Kagaku no Railgun/Saison 3/Subs/E01ts.ass"));
        assertEquals(episode("Blue Box", 1, 1, "NAME"), target(
                "Blue Box/Saison 1/[Aoi-Project] Blue Box S1 - (Sub+Font)/Épisode 01 (Sub+Font)/[Aoi-Project] Blue Box - 01 BD VF.ass"));
        assertEquals(episode("DAYS", 0, 1, "SAME_NAME"), target("DAYS/Days - S00E01 - Épisode 1.fre.ass"));
    }

    @Test
    void namesTellForcedAndLanguage() {
        assertTrue(SubtitleNames.forced("[sekkusu&ok] Captain Tsubasa - Épisode 1.forced.ass"));
        assertFalse(SubtitleNames.forced("[sekkusu&ok] Captain Tsubasa - Épisode 1.ass"));
        assertEquals(Optional.of("fre"), SubtitleNames.language("Days - S00E01 - Épisode 1.fre.ass"));
        assertEquals(Optional.of("fre"), SubtitleNames.language("[Aoi-Project] Blue Box - 01 BD VF.ass"));
        assertEquals(Optional.empty(), SubtitleNames.language("E01ts.ass"));
        assertEquals("Days - S00E01 - Épisode 1", SubtitleNames.stem("Days - S00E01 - Épisode 1.fre.forced.ass"));
        assertEquals("[LightRip] Nisekoi S00E01 OAV 1 (1080p x265 OPUS)",
                SubtitleNames.stem("[LightRip] Nisekoi S00E01 OAV 1 (1080p x265 OPUS).fr.ass"));
    }
}
