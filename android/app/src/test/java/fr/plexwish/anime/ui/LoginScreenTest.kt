package fr.plexwish.anime.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assert
import fr.plexwish.anime.FakeCipher
import fr.plexwish.anime.MemoryStore
import fr.plexwish.anime.data.auth.AuthRepository
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.feature.login.LoginState
import fr.plexwish.anime.feature.login.LoginViewModel
import fr.plexwish.anime.ui.phone.LoginActions
import fr.plexwish.anime.ui.phone.LoginContent
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Connexion (P3.7) : adresse fixée à la compilation (champ absent) ou saisie, mot de passe affichable, ordre de focus au clavier / à la télécommande. */
class LoginScreenTest : ScreenTest() {

    @Test
    fun login() {
        var visible = 0
        shoot("p37-connexion") {
            LoginContent(LoginState(server = "https://anime.mondomaine.fr", login = "matthieu", password = "secret",
                error = "Identifiant ou mot de passe incorrect."), LoginActions(toggleVisible = { visible++ }))
        }
        assertAccessible()
        compose.onNodeWithContentDescription("Afficher le mot de passe").performClick()
        assertEquals(1, visible)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun focusFollowsTheReadingOrder() {
        compose.setContent { LoginContent(LoginState(), LoginActions()) }
        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        compose.onNode(isFocused()).assert(hasSetTextAction()).assert(hasText("Adresse du serveur"))
        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        compose.onNode(isFocused()).assert(hasText("Identifiant ou e-mail"))
        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        compose.onNode(isFocused()).assert(hasText("Mot de passe"))
    }

    @Test
    fun serverAddressFixedAtBuildTimeIsUsedAndNeverEditable() {
        val session = SessionStore(MemoryStore(), FakeCipher())
        val auth = AuthRepository(session, OkHttpClient(), allowHttp = true, deviceName = "test")
        val fixed = LoginViewModel(auth, session, "https://nas.exemple.fr")
        assertEquals("https://nas.exemple.fr", fixed.state.value.server)
        assertTrue(fixed.state.value.fixedServer)
        fixed.onServer("https://autre.exemple.fr")
        assertEquals("https://nas.exemple.fr", fixed.state.value.server)
        // Version d'essai sans adresse : champ à remplir.
        val vm = LoginViewModel(auth, session, "")
        assertEquals("", vm.state.value.server)
        assertFalse(vm.state.value.fixedServer)
        vm.toggleVisible()
        assertTrue(vm.state.value.visible)
    }

    @Test
    fun noServerFieldWhenTheAddressIsFixed() {
        shoot("p37-connexion-adresse-fixe") {
            LoginContent(LoginState(server = "https://nas.exemple.fr", fixedServer = true, login = "matthieu"), LoginActions())
        }
        assertAccessible()
        assertEquals(0, compose.onAllNodes(hasText("Adresse du serveur")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("https://nas.exemple.fr", substring = true)).fetchSemanticsNodes().size)
    }
}
