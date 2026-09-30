package fr.plexwish.animeserver.library.parse;

import fr.plexwish.animeserver.library.parse.ParseResult.Episode;
import fr.plexwish.animeserver.library.parse.ParseResult.Extra;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le parser sur toute la vraie bibliothèque (library-sample.txt, 32 940 chemins) : seuils d'ARCHITECTURE §7.11.
 */
class LibrarySampleTest {

    private static List<String> videos() throws IOException {
        try (InputStream in = LibrarySampleTest.class.getResourceAsStream("/library-sample.txt")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.lines().filter(l -> !l.isBlank())
                    .filter(p -> LibraryFiles.typeOfPath(p) == LibraryFiles.Type.VIDEO)
                    .toList();
        }
    }

    @Test
    void atLeast97PercentOfEpisodesRecognisedAndFewExtras() throws IOException {
        DefaultFilenameParser parser = new DefaultFilenameParser();
        List<String> videos = videos();
        assertEquals(28_254, videos.size(), "vidéos de l'échantillon (hors dossiers techniques et fichiers sans nom)");

        Map<Class<?>, Long> counts = videos.stream().map(parser::parse)
                .collect(Collectors.groupingBy(Object::getClass, Collectors.counting()));
        long episodes = counts.getOrDefault(Episode.class, 0L);
        long extras = counts.getOrDefault(Extra.class, 0L);
        double recognised = 100.0 * episodes / (videos.size() - extras);
        double extrasShare = 100.0 * extras / videos.size();
        System.out.printf("library-sample : %d vidéos, %d épisodes (%.2f %% hors extras), %d extras (%.2f %%), %d autres%n",
                videos.size(), episodes, recognised, extras, extrasShare, videos.size() - episodes - extras);

        assertTrue(recognised >= 97.0, "épisodes reconnus : " + recognised + " %");
        assertTrue(extrasShare < 3.0, "extras : " + extrasShare + " %");
    }

    @Test
    void devilmanCrybabyHasTwoFilesPerEpisode() throws IOException {
        DefaultFilenameParser parser = new DefaultFilenameParser();
        Map<Integer, Long> perEpisode = videos().stream()
                .filter(p -> p.startsWith("Devilman Crybaby/"))
                .map(parser::parse)
                .map(r -> (Episode) r)
                .collect(Collectors.groupingBy(Episode::episode, Collectors.counting()));
        assertEquals(10, perEpisode.size());
        assertTrue(perEpisode.values().stream().allMatch(n -> n == 2), perEpisode.toString());
    }
}
