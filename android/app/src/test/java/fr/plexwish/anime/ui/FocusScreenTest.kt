package fr.plexwish.anime.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.requestFocus
import fr.plexwish.anime.feature.home.HomeState
import fr.plexwish.anime.ui.phone.HomeContent
import org.junit.Test

/**
 * Télécommande / clavier (prépare Android TV) : sur l'accueil, l'ordre de focus suit l'ordre de lecture (bandeau,
 * puis rangées) et l'anneau de focus est visible (capture).
 */
class FocusScreenTest : ScreenTest() {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun homeFocusOrderAndRing() {
        val s = HomeState(loading = false, total = 1300, continueWatching = listOf(Fixtures.continueWatching(0), Fixtures.continueWatching(1, next = true)),
            recent = Fixtures.animes(8))
        shoot("p37-focus-accueil-depart", ScreenTest.DARK_ONLY, keyboard = true) { HomeContent(s, {}, {}, {}, {}, {}, PaddingValues()) }
        val tab = { compose.onRoot().performKeyInput { pressKey(Key.Tab) } }
        // Point de départ : le bouton principal du bandeau (la télécommande y arrive en premier).
        compose.onNode(hasText("Reprendre")).requestFocus()
        compose.onNode(isFocused()).assert(hasText("Reprendre"))
        tab()
        compose.onNode(isFocused()).assert(hasText("Voir la fiche"))
        tab()
        compose.onNode(isFocused()).assert(hasContentDescription("Épisode suivant : Frieren, Saison 1 · Épisode 4"))
        tab() // « Tout voir » de « Récemment ajoutés »
        tab()
        compose.onNode(isFocused()).assert(hasContentDescription(Fixtures.titles[0], substring = true))
        compose.waitForIdle()
        save("p37-focus-accueil-carte-sombre")
    }
}
