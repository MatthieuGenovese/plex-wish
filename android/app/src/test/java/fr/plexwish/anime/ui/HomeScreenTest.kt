package fr.plexwish.anime.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.plexwish.anime.data.api.ContinueWatching
import fr.plexwish.anime.feature.home.GenreRow
import fr.plexwish.anime.feature.home.HomeState
import fr.plexwish.anime.ui.phone.HomeContent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/** Accueil (P3.3) : bandeau « À reprendre » / « À suivre » / « Dernier ajout », rangées, états vides. */
class HomeScreenTest : ScreenTest() {

    private val full = HomeState(
        loading = false, total = 1300,
        continueWatching = listOf(Fixtures.continueWatching(0), Fixtures.continueWatching(1, next = true), Fixtures.continueWatching(2)),
        recent = Fixtures.animes(12),
        genres = Fixtures.genres.map { GenreRow(it, Fixtures.animes(8, 3)) },
        discover = Fixtures.animes(12, 5),
    )

    @Test
    @Config(qualifiers = "w384dp-h1500dp-xxhdpi")
    fun homeWithHistory() {
        var played: ContinueWatching? = null
        shoot("p33-accueil") { HomeContent(full, { played = it }, {}, {}, {}, {}, PaddingValues()) }
        assertAccessible()
        compose.onNodeWithText("Reprendre").performClick()
        assertEquals(5000L, played?.episodeId)
        // Deuxième entrée (épisode suivant) : un seul élément pour TalkBack, avec l'action.
        compose.onNodeWithContentDescription("Épisode suivant : Frieren, Saison 1 · Épisode 4").performClick()
        assertEquals(5001L, played?.episodeId)
    }

    @Test
    fun nextEpisodeHero() {
        val s = full.copy(continueWatching = listOf(Fixtures.continueWatching(1, next = true)))
        shoot("p33-accueil-a-suivre", ScreenTest.ALL.take(2)) { HomeContent(s, {}, {}, {}, {}, {}, PaddingValues()) }
        compose.onNodeWithText("À SUIVRE").assertExists()
        compose.onNodeWithText("Épisode suivant").assertExists()
    }

    @Test
    fun newAccountSeesTheLatestAddition() {
        val s = full.copy(continueWatching = emptyList(), recent = listOf(Fixtures.anime(0, fresh = true)) + Fixtures.animes(5, 1))
        shoot("p33-accueil-nouveau-compte", ScreenTest.ALL.take(2)) { HomeContent(s, {}, {}, {}, {}, {}, PaddingValues()) }
        compose.onNodeWithText("DERNIER AJOUT").assertExists()
        assertAccessible()
    }

    @Test
    fun emptyErrorAndLoadingStates() {
        shoot("p33-accueil-vide", ScreenTest.ALL.take(2)) { HomeContent(HomeState(loading = false, total = 0), {}, {}, {}, {}, {}, PaddingValues()) }
        compose.onNodeWithText("La bibliothèque est vide").assertExists()
        assertAccessible()
    }

    @Test
    fun errorState() {
        shoot("p33-accueil-erreur", ScreenTest.DARK_ONLY) {
            HomeContent(HomeState(loading = false, error = "Le serveur ne répond pas : vérifiez l'adresse, et qu'il est bien démarré."),
                {}, {}, {}, {}, {}, PaddingValues())
        }
        compose.onNodeWithText("Réessayer").assertExists()
    }

    @Test
    fun loadingSkeleton() {
        shoot("p33-accueil-chargement", ScreenTest.DARK_ONLY) { HomeContent(HomeState(), {}, {}, {}, {}, {}, PaddingValues()) }
    }
}
