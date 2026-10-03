package fr.plexwish.anime.ui.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.plexwish.anime.data.api.AnimeSummary
import fr.plexwish.anime.data.api.ContinueWatching
import fr.plexwish.anime.feature.home.HomeViewModel
import fr.plexwish.anime.ui.components.ErrorMessage
import fr.plexwish.anime.ui.components.Loading
import fr.plexwish.anime.ui.components.Poster

/** Accueil : « Continuer à regarder » puis les derniers ajouts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel, onContinue: (ContinueWatching) -> Unit, onAnime: (Long) -> Unit, padding: PaddingValues) {
    val s by vm.state.collectAsStateWithLifecycle()
    PullToRefreshBox(isRefreshing = s.loading && (s.recent.isNotEmpty() || s.continueWatching.isNotEmpty()),
        onRefresh = vm::load, modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (s.loading && s.recent.isEmpty() && s.continueWatching.isEmpty()) item { Loading() }
            s.error?.let { item { ErrorMessage(it, vm::load) } }
            if (s.continueWatching.isNotEmpty()) {
                item { SectionTitle("Continuer à regarder") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(s.continueWatching, key = { it.episodeId }) { c -> ContinueCard(c) { onContinue(c) } }
                    }
                }
            }
            if (s.recent.isNotEmpty()) {
                item { SectionTitle("Récemment ajoutés") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(s.recent, key = { it.id }) { a -> AnimeCard(a, Modifier.width(120.dp)) { onAnime(a.id) } }
                    }
                }
            }
            if (!s.loading && s.error == null && s.recent.isEmpty()) {
                item { Text("La bibliothèque est vide pour l'instant.", Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() })
}

@Composable
private fun ContinueCard(c: ContinueWatching, onClick: () -> Unit) {
    val label = "${c.animeTitle}, ${c.seasonLabel}, épisode ${c.episodeNumber}"
    Column(
        Modifier.width(140.dp).clickable(role = Role.Button, onClickLabel = "Reprendre", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Poster(c.animeTitle, c.posterUrl)
        if (c.durationSeconds > 0) {
            LinearProgressIndicator(
                progress = { (c.positionSeconds.toFloat() / c.durationSeconds).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
        Text(c.animeTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${c.seasonLabel} · ép. ${c.episodeNumber}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Vignette d'un animé : affiche, titre, année et nombre d'épisodes. */
@Composable
fun AnimeCard(a: AnimeSummary, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Poster(a.title, a.posterUrl)
        Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(a.year?.toString(), "${a.episodeCount} épisode${if (a.episodeCount > 1) "s" else ""}").joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
