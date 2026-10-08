package fr.plexwish.anime.ui.phone

import fr.plexwish.anime.ui.theme.AppIcons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.plexwish.anime.feature.library.LibrarySort
import fr.plexwish.anime.feature.library.LibraryViewModel
import fr.plexwish.anime.ui.components.ErrorMessage
import fr.plexwish.anime.ui.components.Loading

/** Bibliothèque : recherche, tri, grille d'affiches avec pages chargées au fil du défilement. */
@Composable
fun LibraryScreen(vm: LibraryViewModel, onAnime: (Long) -> Unit, padding: PaddingValues) {
    val s by vm.state.collectAsStateWithLifecycle()
    // État du défilement conservé à la navigation (pile de navigation) et à la rotation (rememberSaveable interne).
    val grid = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 12
        }
    }
    LaunchedEffect(grid) { snapshotFlow { nearEnd }.collect { if (it) vm.loadMore() } }

    Column(Modifier.fillMaxSize().padding(padding)) {
        OutlinedTextField(
            value = s.query, onValueChange = vm::onQuery, singleLine = true,
            label = { Text("Rechercher un titre") },
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
            trailingIcon = {
                if (s.query.isNotEmpty()) IconButton(onClick = { vm.onQuery("") }) {
                    Icon(AppIcons.Close, contentDescription = "Effacer la recherche")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibrarySort.entries.forEach { sort ->
                FilterChip(selected = s.sort == sort, onClick = { vm.onSort(sort) }, label = { Text(sort.label) })
            }
        }
        Text(
            if (s.loading) "Chargement…" else "${s.total} animé${if (s.total > 1) "s" else ""}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            state = grid,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (s.loading && s.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
            items(s.items, key = { it.id }) { a -> AnimeCard(a) { onAnime(a.id) } }
            if (s.loadingMore) item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
            s.error?.let { item(span = { GridItemSpan(maxLineSpan) }) { ErrorMessage(it, vm::retry) } }
            if (!s.loading && s.error == null && s.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(if (s.query.isBlank()) "La bibliothèque est vide." else "Aucun titre ne correspond à « ${s.query} ».")
                }
            }
        }
    }
}
