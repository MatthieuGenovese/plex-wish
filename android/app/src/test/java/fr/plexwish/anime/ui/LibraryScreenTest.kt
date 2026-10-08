package fr.plexwish.anime.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import fr.plexwish.anime.feature.library.LibrarySort
import fr.plexwish.anime.feature.library.LibraryState
import fr.plexwish.anime.feature.library.Period
import fr.plexwish.anime.feature.library.WatchFilter
import fr.plexwish.anime.ui.phone.LibraryActions
import fr.plexwish.anime.ui.phone.LibraryContent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Bibliothèque et recherche (P3.4) : filtres S2/S3, tri S6, états vides dédiés. */
class LibraryScreenTest : ScreenTest() {

    private val list = LibraryState(loading = false, items = Fixtures.animes(12).mapIndexed { i, a -> if (i == 1) Fixtures.anime(1, fresh = true) else a },
        total = 1300, genres = Fixtures.genres)

    @Test
    fun libraryWithFilters() {
        var watch: WatchFilter? = null
        var genre: String? = "x"
        val s = list.copy(watch = WatchFilter.UNSEEN, genre = "Comedy", period = Period.Y2010, sort = LibrarySort.YEAR, total = 127)
        shoot("p34-bibliotheque") {
            LibraryContent(s, false, PaddingValues(), LibraryActions(onWatch = { watch = it }, onGenre = { genre = it }))
        }
        assertAccessible()
        compose.onNodeWithText("En cours").performClick()
        assertEquals(WatchFilter.IN_PROGRESS, watch)
        // Les filtres défilent : la puce « Genre » est amenée à l'écran (comme au doigt ou à la télécommande).
        compose.onAllNodes(hasScrollToNodeAction())[1].performScrollToNode(hasContentDescription("Genre : Comédie"))
        compose.onNodeWithContentDescription("Genre : Comédie").performClick()
        compose.onNodeWithText("Tous les genres").performClick()
        assertEquals(null, genre)
    }

    @Test
    fun searchTab() {
        shoot("p34-recherche", ScreenTest.ALL.take(3)) {
            LibraryContent(list.copy(query = "no", total = 2, items = Fixtures.animes(2)), true, PaddingValues(), LibraryActions())
        }
        assertAccessible()
    }

    @Test
    fun dedicatedEmptyStates() {
        shoot("p34-vide-en-cours", ScreenTest.ALL.take(2)) {
            LibraryContent(LibraryState(loading = false, watch = WatchFilter.IN_PROGRESS), false, PaddingValues(), LibraryActions())
        }
        compose.onNodeWithText("Aucun animé en cours").assertExists()
        assertAccessible()
    }

    @Test
    fun noResultForASearch() {
        var cleared = false
        shoot("p34-vide-recherche", ScreenTest.DARK_ONLY) {
            LibraryContent(LibraryState(loading = false, query = "zzzzqx"), true, PaddingValues(), LibraryActions(clearAll = { cleared = true }))
        }
        compose.onNodeWithText("Aucun animé pour « zzzzqx »").assertExists()
        compose.onNodeWithText("Tout effacer").performClick()
        assertEquals(true, cleared)
    }

    @Test
    fun emptyLibraryAndLoading() {
        shoot("p34-vide-bibliotheque", ScreenTest.DARK_ONLY) { LibraryContent(LibraryState(loading = false), false, PaddingValues(), LibraryActions()) }
        compose.onNodeWithText("La bibliothèque est vide").assertExists()
    }

    @Test
    fun loading() {
        shoot("p34-chargement", ScreenTest.DARK_ONLY) { LibraryContent(LibraryState(), false, PaddingValues(), LibraryActions()) }
    }
}
