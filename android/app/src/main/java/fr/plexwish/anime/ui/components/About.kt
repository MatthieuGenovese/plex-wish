package fr.plexwish.anime.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import fr.plexwish.anime.BuildConfig

/** Mention exigée par les conditions de TMDB (texte exact, ARCHITECTURE §16), affichée dans l'app. */
const val TMDB_NOTICE =
    "This application uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB."

/** « À propos » : version, sources des données et mention TMDB. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        title = { Text("À propos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Anime Server ${BuildConfig.VERSION_NAME}")
                Text("Fiches, affiches et distribution : AniList (anilist.co). Synopsis en français et affiches : TMDB (themoviedb.org).")
                Text(TMDB_NOTICE)
            }
        },
    )
}
