package fr.plexwish.anime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.plexwish.anime.ui.components.ErrorMessage
import fr.plexwish.anime.ui.components.GhostButton
import fr.plexwish.anime.ui.components.Poster
import fr.plexwish.anime.ui.components.PrimaryButton
import fr.plexwish.anime.ui.components.ProgressBar
import fr.plexwish.anime.ui.theme.AppIcons
import org.junit.Test

/** Fondations (P3.1) : typographie Figtree, boutons, couvertures composées, barre de progression, état d'erreur. */
class ComponentsScreenTest : ScreenTest() {

    @Test
    fun foundations() {
        shoot("p31-fondations") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Frieren", style = MaterialTheme.typography.headlineLarge)
                Text("Corps de texte : l'elfe Frieren et Himmel, Satō, Ōkami, « guillemets »…", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Reprendre", {}, icon = AppIcons.PlayArrowFill)
                    GhostButton("Détails", {})
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Poster("Même si l'été ne revient pas, nous garderons les fenêtres ouvertes", null, Modifier.width(110.dp))
                    Poster("Haruka Satō", null, Modifier.width(80.dp), round = true)
                    Poster("Akuma ga Senki", null, Modifier.width(40.dp))
                }
                ProgressBar(0.62f)
                ErrorMessage("Le serveur ne répond pas.", onRetry = {})
            }
        }
        assertAccessible()
    }
}
