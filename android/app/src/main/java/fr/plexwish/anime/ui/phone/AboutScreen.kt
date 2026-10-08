package fr.plexwish.anime.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.plexwish.anime.BuildConfig
import fr.plexwish.anime.ui.components.Panel
import fr.plexwish.anime.ui.components.ScreenTitle
import fr.plexwish.anime.ui.components.TMDB_NOTICE
import fr.plexwish.anime.ui.theme.AppTheme
import fr.plexwish.anime.ui.theme.Dimens

/** À propos : version, sources des données (AniList, TMDB) et mention TMDB, polices et icônes (licences). */
@Composable
fun AboutScreen(padding: PaddingValues) {
    val p = AppTheme.palette
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.s4),
        ) {
            ScreenTitle("À propos")
            Text("${BuildConfig.APP_NAME} ${BuildConfig.VERSION_NAME} : serveur privé de streaming d'animés pour un petit groupe d'amis.",
                style = MaterialTheme.typography.bodyLarge, color = p.text2)
            Panel(title = "AniList") {
                Text("Fiches, années, genres, affiches et distribution (personnages et comédiens de doublage) viennent d'AniList (anilist.co).",
                    style = MaterialTheme.typography.bodyMedium)
            }
            Panel(title = "TMDB") {
                Text("Synopsis et titres en français, et affiches quand AniList n'en a pas : The Movie Database (themoviedb.org).",
                    style = MaterialTheme.typography.bodyMedium)
                Text(TMDB_NOTICE, style = MaterialTheme.typography.bodySmall, color = p.text2)
            }
            Panel(title = "Police et icônes") {
                Text("Police Figtree (SIL Open Font License 1.1) ; icônes Material Symbols (Google, licence Apache 2.0).",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
