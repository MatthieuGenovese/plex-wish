package fr.plexwish.anime.ui.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.plexwish.anime.feature.detail.ROLE_LABELS
import fr.plexwish.anime.feature.person.PersonAnime
import fr.plexwish.anime.feature.person.PersonViewModel
import fr.plexwish.anime.ui.components.ErrorMessage
import fr.plexwish.anime.ui.components.Loading
import fr.plexwish.anime.ui.components.Poster

/** Page d'un comédien : photo, noms, lien AniList, puis les animés de la bibliothèque où il joue (grille). */
@Composable
fun PersonScreen(vm: PersonViewModel, onAnime: (Long) -> Unit, padding: PaddingValues) {
    val s by vm.state.collectAsStateWithLifecycle()
    val p = s.person
    val uri = LocalUriHandler.current
    when {
        p == null && s.loading -> Loading(Modifier.padding(padding))
        p == null -> ErrorMessage(
            if (s.notFound) "Ce comédien ne joue dans aucun animé disponible de la bibliothèque." else s.error ?: "Erreur inconnue.",
            if (s.notFound) null else vm::load, Modifier.padding(padding),
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Poster(p.name, p.imageUrl, Modifier.width(110.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                        p.nativeName?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium.copy(localeList = LocaleList("ja")))
                        }
                        Muted("Comédien de doublage · ${s.animes.size} animé${if (s.animes.size > 1) "s" else ""} de la bibliothèque")
                        p.sourceUrl?.let { url ->
                            TextButton(onClick = { uri.openUri(url) }, contentPadding = PaddingValues(0.dp)) { Text("Source : AniList") }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Dans la bibliothèque", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            items(s.animes, key = { it.animeId }) { a -> PersonAnimeCard(a) { onAnime(a.animeId) } }
        }
    }
}

@Composable
private fun PersonAnimeCard(a: PersonAnime, onClick: () -> Unit) {
    Column(
        Modifier.clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Poster(a.title, a.posterUrl)
        Text(listOfNotNull(a.title, a.year?.toString()).joinToString(" · "), style = MaterialTheme.typography.titleSmall,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        // Noms des personnages joués, sans image.
        a.roles.forEach { r ->
            Text("${r.character.name} · ${ROLE_LABELS[r.role] ?: r.role}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (r.role == "MAIN") FontWeight.Medium else null)
        }
    }
}
