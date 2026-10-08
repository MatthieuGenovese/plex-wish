package fr.plexwish.anime.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.plexwish.anime.feature.account.PasswordForm
import fr.plexwish.anime.ui.phone.AboutScreen
import fr.plexwish.anime.ui.phone.AccountContent
import fr.plexwish.anime.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/** Compte (P3.2) : thème, mot de passe, À propos. */
class AccountScreenTest : ScreenTest() {

    @Test
    @Config(qualifiers = "w384dp-h1400dp-xxhdpi")
    fun account() {
        var chosen: ThemeMode? = null
        shoot("p32-compte") {
            AccountContent("matthieu", "https://anime.mondomaine.fr", ThemeMode.DARK, { chosen = it },
                PasswordForm(current = "x", error = "Le mot de passe actuel est incorrect."), {}, {}, {}, {}, {}, {}, {}, PaddingValues())
        }
        assertAccessible()
        compose.onNodeWithText("Sombre").assertIsSelected()
        compose.onNodeWithText("Clair").performClick()
        assertEquals(ThemeMode.LIGHT, chosen)
    }

    @Test
    fun about() {
        shoot("p32-a-propos") { AboutScreen(PaddingValues()) }
        assertAccessible()
    }
}
