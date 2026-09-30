package fr.plexwish.animeserver.library.parse;

import fr.plexwish.animeserver.library.parse.ParseResult.Episode;
import fr.plexwish.animeserver.library.parse.ParseResult.Extra;
import fr.plexwish.animeserver.library.parse.ParseResult.Problem;
import fr.plexwish.animeserver.library.parse.ParseResult.Strategy;
import fr.plexwish.animeserver.library.parse.ParseResult.Unresolved;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pièges d'ARCHITECTURE §7.11 et règles unitaires du parser. Des chaînes seulement, jamais de fichiers. */
class DefaultFilenameParserTest {

    private final DefaultFilenameParser parser = new DefaultFilenameParser();

    private Episode episode(String path) {
        return assertInstanceOf(Episode.class, parser.parse(path), path);
    }

    private void assertEpisode(String path, int season, int number) {
        Episode e = episode(path);
        assertEquals(season, e.season(), "saison de " + path);
        assertEquals(number, e.episode(), "épisode de " + path);
    }

    // --- Pièges de la vraie bibliothèque (§7.11) -------------------------------------------------------

    @Test
    void slime300IsEpisodeOneNotThreeHundred() {
        Episode e = episode("I've Been Killing Slimes for 300 Years and Maxed Out My Level/Saison 1/"
                + "[matheousse] Slime 300 S1 - 01 MULTi [BD 1080p AAC Opus] [DBB79FF9].mkv");
        assertEquals(1, e.season());
        assertEquals(1, e.episode());
        assertEquals(Strategy.NUMBER_ONLY, e.strategy());
        assertEquals("I've Been Killing Slimes for 300 Years and Maxed Out My Level", e.animeTitle());
    }

    @Test
    void genshikenUppercaseX() {
        Episode e = episode("Genshiken/Saison 1/Genshiken 01X01.mkv");
        assertEquals(Strategy.NXEE, e.strategy());
        assertEquals(1, e.season());
        assertEquals(1, e.episode());
    }

    @Test
    void ahMyGoddessTakesTheSeasonFromTheFolder() {
        Episode s1 = episode("AH! My Goddess/Saison 1/[Elecman] Ah! My Goddess E12 [BDrip][1080p x265 10bits Multi].mkv");
        Episode s2 = episode("AH! My Goddess/Saison 2/[Elecman] Ah My Goddess E12 [BDRIP][1080p x265 10bits Vostfr].mkv");
        assertEquals(Strategy.E_NUMBER, s1.strategy());
        assertEquals(1, s1.season());
        assertEquals(2, s2.season());
        assertEquals(12, s1.episode());
        assertEquals(12, s2.episode());
    }

    @Test
    void s00InAnOavFolderIsASpecialEpisodeNotAnExtra() {
        Episode e = episode("Shingeki No Kyojin/OAV/L'Attaque des Titans - S00E18 - Lost Girls  Wall Sina, Goodbye (Partie 2).mkv");
        assertEquals(0, e.season());
        assertEquals(18, e.episode());
        assertNull(e.folderSeasonConflict());
    }

    @Test
    void fishManIslandNumberPrecededByUnderscore() {
        assertEpisode("One Piece/Saison 7/[Kaerizaki-Fansub]_One_Piece_Fish_Man_Island_01_[VERSION_LIGHT][VOSTFR][FHD_1920x1080].mp4", 7, 1);
    }

    @Test
    void narutoShippudenKaiNumberPrecededBySpace() {
        assertEpisode("Naruto Shippuden/Naruto Shippuden Kai Intégrale VOSTFR/Naruto Shippuden Kai 113 - Hebi.mkv", 1, 113);
    }

    @Test
    void onePiece1124FourDigitsAndTechnicalTokensIgnored() {
        assertEpisode("One Piece/Saison 11/[Kaerizaki-Fansub]_One_Piece_1124_[VOSTFR][FHD_1080p][HEVC_x265][10Bit].mkv", 11, 1124);
    }

    @Test
    void onePieceKaiNotX264NorResolution() {
        assertEpisode("One Piece/Saison 9/One Piece Kaï - 098 - Totto Land - 1080p.VOSTFR.x264 [Sacha].mp4", 9, 98);
    }

    @Test
    void taktOpDestinyIsAnEpisode() {
        assertEpisode("Takt.OP Destiny/[Erai-raws] Takt Op. Destiny - 11 [1080p][Multiple Subtitle][C66F48E0].mkv", 1, 11);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Isekai Quartet/S2/NC/[Natsumi no Sekai] Isekai Quartet S2 - NCOP VOSTFR [BD 1080p AAC].mkv",
            "Blend S/Extras/Blend S NCED4 [BD 1080p FLAC] [37795792].mkv",
            "Nyan Koi!/EXTRA/Nyan Koi! Menu - 05 (BD 1920x1080 x.264 Flac).mkv",
            "SSSS.Dynazenon/Bonus/NCOPv1 Imperfect [801134F9].mkv",
            "Subete ga F ni Naru/Subete ga F ni Naru - The Perfect Insider - OP01 [BD 1080p x264 FLAC].mkv",
            "Mekaku City Actors/Extra/Preview/Ep.08.mkv",
    })
    void extrasWithoutEpisodeNumber(String path) {
        assertInstanceOf(Extra.class, parser.parse(path), path);
    }

    @Test
    void littleWitchAcademiaSingleDigitIsUnresolved() {
        Unresolved u = assertInstanceOf(Unresolved.class, parser.parse("Little Witch/Little Witch Academia 1.mp4"));
        assertEquals(Problem.NO_EPISODE_NUMBER, u.problem());
    }

    @Test
    void decimalNumbersGoToTheReport() {
        assertEquals(Problem.DECIMAL_EPISODE, assertInstanceOf(Unresolved.class,
                parser.parse("Macross Delta/[Lumen] Macross Delta 0.89.mkv")).problem());
        assertEquals(Problem.DECIMAL_EPISODE, assertInstanceOf(Unresolved.class,
                parser.parse("Show/Show S01E05.5 VOSTFR.mkv")).problem());
    }

    @Test
    void doubleEpisodesGoToTheReport() {
        assertEquals(Problem.MULTI_EPISODE, assertInstanceOf(Unresolved.class,
                parser.parse("Fortune Arterial/[Tanjou]Fortune Arterial - Akai Yakusoku HD 05-06 vostfr.mp4")).problem());
        assertEquals(Problem.MULTI_EPISODE, assertInstanceOf(Unresolved.class,
                parser.parse("Show/Show - S01E03-E04 - Titre.mkv")).problem());
        assertEquals(Problem.MULTI_EPISODE, assertInstanceOf(Unresolved.class,
                parser.parse("Show/Show - S01E03-04.mkv")).problem());
    }

    // --- Règles générales ------------------------------------------------------------------------

    @Test
    void fileNameWinsOverSeasonFolderAndConflictIsReported() {
        Episode e = episode("Show/Season 01/Show - S02E03.mkv");
        assertEquals(2, e.season());
        assertEquals(1, e.folderSeasonConflict());
        assertNull(episode("Show/Season 02/Show - S02E03.mkv").folderSeasonConflict());
    }

    @Test
    void firstPatternWins() {
        assertEpisode("11 Eyes/11 Eyes - S01E13 (OAV S1E01) [x265 1080p].mkv", 1, 13);
    }

    @Test
    void numberedEpisodesStayEpisodesWhatever_theirWords() {
        assertEpisode("Sekai Seifuku/Sekai Seifuku S01E13 OVA VOSTFR 1080p BluRay x264 10bits FLAC -DavRips.mkv", 1, 13);
        assertEpisode("Fate⁄EXTRA Last Encore/Fate⁄EXTRA Last Encore S01E01 MULTi 1080p 10bits BluRay x265 AAC -Punisher694.mkv", 1, 1);
        assertEpisode("Blend S/Extras/Blend S - S01E05.mkv", 1, 5);
    }

    @Test
    void numberOnlyInSpecialFolderIsSeasonZero() {
        assertEpisode("Show/OAV/Show - 02 [1080p].mkv", 0, 2);
    }

    @Test
    void noSeasonAnywhereMeansSeasonOne() {
        assertEpisode("[Erai-raws] Aharen-san/[Erai-raws] Aharen-san wa Hakarenai - 04 [720p][6B2662DA].mkv", 1, 4);
    }

    @Test
    void resolutionAfterSxxExxIsNotADecimal() {
        assertEpisode("Show/Show S01E05.1080p.WEB.mkv", 1, 5);
    }

    @Test
    void crcHashIsNotAnEpisodeNumber() {
        // "E4" et "E2" sont dans l'empreinte CRC : ce sont des génériques, pas les épisodes 4 et 2.
        assertInstanceOf(Extra.class, parser.parse("Ascendance of a Bookworm/Extras/"
                + "[matheousse] Ascendance of a Bookworm - NCED 02 [BD 1080p FLAC] [E4E2B273].mkv"));
        assertInstanceOf(Extra.class, parser.parse("Saenai Heroine no Sodatekata/Saison 2 (2017)/Extras/"
                + "Saenai Heroine no Sodatekata Flat NCOP 3 [BD 1080p FLAC] [E2F92400].mkv"));
        assertEpisode("Show/Show E05 [E4E2B273].mkv", 1, 5);
    }

    @Test
    void seasonSourceIsReported() {
        assertEquals(ParseResult.SeasonSource.NAME_SXXEXX, episode("Show/Season 01/Show - S02E03.mkv").seasonSource());
        assertEquals(ParseResult.SeasonSource.NAME_NXEE, episode("Genshiken/Saison 1/Genshiken 01X01.mkv").seasonSource());
        assertEquals(ParseResult.SeasonSource.NAME_S, episode("Slime/[m] Slime 300 S1 - 01 [BD].mkv").seasonSource());
        assertEquals(ParseResult.SeasonSource.FOLDER, episode("AH! My Goddess/Saison 2/Ah My Goddess E12.mkv").seasonSource());
        assertEquals(ParseResult.SeasonSource.SPECIAL_FOLDER, episode("Show/OAV/Show - 02 [1080p].mkv").seasonSource());
        assertEquals(ParseResult.SeasonSource.DEFAULT, episode("Naruto Shippuden/Kai VOSTFR/Naruto Shippuden Kai 113 - Hebi.mkv").seasonSource());
    }

    @Test
    void videoAtTheRootHasNoAnime() {
        assertEquals(Problem.NO_ANIME_FOLDER, assertInstanceOf(Unresolved.class, parser.parse("loose.mkv")).problem());
    }

    @Test
    void fileClassification() {
        assertEquals(LibraryFiles.Type.VIDEO, LibraryFiles.typeOfPath("A/b.MKV"));
        assertEquals(LibraryFiles.Type.SUBTITLE, LibraryFiles.typeOfPath("A/sous-titres + police/b.ass"));
        assertEquals(LibraryFiles.Type.IGNORED, LibraryFiles.typeOfPath("A/@eaDir/b.mkv"));
        assertEquals(LibraryFiles.Type.IGNORED, LibraryFiles.typeOfPath("A/._b.mkv"));
        assertEquals(LibraryFiles.Type.IGNORED, LibraryFiles.typeOfPath("Tsuki/Extras/PV/.mkv"));
        assertEquals(LibraryFiles.Type.VIDEO, LibraryFiles.typeOfPath("Accel world/.Accel World Opening 1 HD..mp4"));
    }
}
