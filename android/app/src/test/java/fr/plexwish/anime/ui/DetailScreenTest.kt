package fr.plexwish.anime.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.plexwish.anime.data.api.AnimeDetail
import fr.plexwish.anime.data.api.CastCharacter
import fr.plexwish.anime.data.api.CastEntry
import fr.plexwish.anime.data.api.CastPersonRef
import fr.plexwish.anime.data.api.EpisodeSummary
import fr.plexwish.anime.data.api.GenreDto
import fr.plexwish.anime.data.api.PersonDetail
import fr.plexwish.anime.data.api.PersonRole
import fr.plexwish.anime.data.api.ProgressDto
import fr.plexwish.anime.data.api.ResumeDto
import fr.plexwish.anime.data.api.SeasonDto
import fr.plexwish.anime.feature.detail.DetailState
import fr.plexwish.anime.feature.person.PersonAnime
import fr.plexwish.anime.feature.person.PersonState
import fr.plexwish.anime.ui.phone.DetailActions
import fr.plexwish.anime.ui.phone.DetailContent
import fr.plexwish.anime.ui.phone.PersonContent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/** Fiche (P3.5) : bandeau, bouton principal S5, genres S3, saisons, épisodes, distribution ; page d'un comédien. */
class DetailScreenTest : ScreenTest() {

    private fun anime(resume: ResumeDto?, seasons: Int = 2) = AnimeDetail(
        id = 7, title = "Sousou no Frieren", alternativeTitle = "葬送のフリーレン", frenchTitle = "Frieren", year = 2023,
        synopsis = "Après dix ans d'aventure, le héros Himmel et ses compagnons ont vaincu le Roi des démons. L'elfe Frieren, " +
            "mage du groupe, voit ses amis vieillir et disparaître ; elle part à la rencontre des humains qu'elle n'a pas pris le temps de connaître, " +
            "de village en village, en apprenant ce que dix ans représentent pour eux.",
        synopsisLanguage = "fr", synopsisSource = "TMDB", metadataSource = "AniList", metadataUrl = "https://anilist.co/anime/154587",
        tmdbUrl = "https://www.themoviedb.org/tv/209867",
        seasons = (1..seasons).map { SeasonDto(10L + it, it, "Saison $it", 28) } + SeasonDto(99, 0, "Spéciaux", 2),
        resume = resume, genres = listOf(GenreDto("Adventure", "Aventure"), GenreDto("Fantasy", "Fantastique"), GenreDto("Drama", "Drame")),
    )

    private val episodes = (1..12).map { EpisodeSummary(1000L + it, it, if (it % 3 == 0) null else "Le voyage n°$it vers le nord", 1440) }
    private val progress = mapOf(
        1001L to ProgressDto(1001, 1440, 1440, true), 1002L to ProgressDto(1002, 1440, 1440, true),
        1003L to ProgressDto(1003, 600, 1440, false),
    )
    private val cast = listOf(
        CastEntry(CastCharacter("Frieren"), "MAIN", person = CastPersonRef("95185", "Atsumi Tanezaki", "種﨑敦美")),
        CastEntry(CastCharacter("Fern"), "MAIN", person = CastPersonRef("12", "Kana Ichinose")),
        CastEntry(CastCharacter("Narrateur"), "SUPPORTING", person = null),
        CastEntry(CastCharacter("Stark"), "SUPPORTING", person = CastPersonRef("13", "Chiaki Kobayashi")),
    )

    private fun state(resume: ResumeDto?) = DetailState(loading = false, anime = anime(resume), seasonId = 11, episodes = episodes,
        progress = progress, cast = cast, castSource = "AniList")

    @Test
    @Config(qualifiers = "w384dp-h1800dp-xxhdpi")
    fun detailResume() {
        var played = 0L
        var genre: String? = null
        val r = ResumeDto("RESUME", 1003, 11, 1, 3, null, 600, 1440)
        shoot("p35-fiche") { DetailContent(state(r), PaddingValues(), DetailActions(onPlay = { played = it }, onGenre = { genre = it })) }
        assertAccessible()
        compose.onNodeWithText("Reprendre").performClick()
        assertEquals(1003L, played)
        compose.onNodeWithText("Fantastique").performClick()
        assertEquals("Fantasy", genre)
        compose.onNodeWithContentDescription("Épisode 4, Le voyage n°4 vers le nord, 24 min").performClick()
        assertEquals(1004L, played)
    }

    @Test
    fun startNextAndRewatch() {
        shoot("p35-fiche-commencer", ScreenTest.ALL.take(2)) {
            DetailContent(state(ResumeDto("START", 1001, 11, 1, 1)).copy(progress = emptyMap()), PaddingValues(), DetailActions())
        }
        compose.onNodeWithText("POUR COMMENCER").assertExists()
        compose.onNodeWithText("Commencer").assertExists()
    }

    @Test
    fun nextEpisode() {
        shoot("p35-fiche-suivant", ScreenTest.DARK_ONLY) {
            DetailContent(state(ResumeDto("NEXT", 1004, 11, 1, 4, "Le voyage n°4 vers le nord")), PaddingValues(), DetailActions())
        }
        compose.onNodeWithText("PROCHAIN ÉPISODE").assertExists()
        compose.onNodeWithText("Épisode suivant").assertExists()
    }

    @Test
    fun rewatchAndManySeasons() {
        val s = state(ResumeDto("REWATCH", 1001, 11, 1, 1)).copy(anime = anime(ResumeDto("REWATCH", 1001, 11, 1, 1), seasons = 6))
        shoot("p35-fiche-revoir", ScreenTest.DARK_ONLY) { DetailContent(s, PaddingValues(), DetailActions()) }
        compose.onNodeWithText("VOUS AVEZ TOUT VU").assertExists()
        compose.onNodeWithContentDescription("Saison : Saison 1").assertExists()
    }

    @Test
    fun notFoundAndError() {
        shoot("p35-fiche-introuvable", ScreenTest.DARK_ONLY) { DetailContent(DetailState(loading = false, notFound = true), PaddingValues(), DetailActions()) }
        compose.onNodeWithText("Animé introuvable").assertExists()
    }

    @Test
    fun person() {
        val roles = listOf(
            PersonRole(7, "Sousou no Frieren", 2023, null, CastCharacter("Frieren"), "MAIN"),
            PersonRole(9, "Spy × Family", 2022, null, CastCharacter("Anya Forger"), "MAIN"),
        )
        val s = PersonState(loading = false, person = PersonDetail("95185", "Atsumi Tanezaki", "種﨑敦美", null, "https://anilist.co/staff/95185", roles),
            animes = roles.map { PersonAnime(it.animeId, it.animeTitle, it.year, null, listOf(it)) } +
                PersonAnime(11, "Même si l'été ne revient pas, nous garderons les fenêtres ouvertes", 2002, null,
                    listOf(PersonRole(11, "x", 2002, null, CastCharacter("Aoi"), "SUPPORTING"), PersonRole(11, "x", 2002, null, CastCharacter("La narratrice"), "SUPPORTING"))))
        shoot("p35-comedien") { PersonContent(s, {}, {}, PaddingValues()) }
        assertAccessible()
    }
}
