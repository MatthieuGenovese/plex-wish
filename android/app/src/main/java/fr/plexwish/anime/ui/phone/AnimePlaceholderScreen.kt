package fr.plexwish.anime.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Point d'étape (connexion + bibliothèque) : la fiche complète arrive au bloc suivant. */
@Composable
fun AnimePlaceholderScreen(animeId: Long, padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Fiche de l'animé n° $animeId", style = MaterialTheme.typography.titleLarge)
        Text("La fiche (saisons, épisodes, distribution) et le lecteur arrivent aux blocs suivants.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
