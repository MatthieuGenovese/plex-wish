package fr.plexwish.anime.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.plexwish.anime.feature.library.LibrarySort
import fr.plexwish.anime.feature.library.LibraryState
import fr.plexwish.anime.feature.library.LibraryViewModel
import fr.plexwish.anime.feature.library.Period
import fr.plexwish.anime.feature.library.WatchFilter
import fr.plexwish.anime.ui.components.AnimeCard
import fr.plexwish.anime.ui.components.AnimeCardSkeleton
import fr.plexwish.anime.ui.components.AppIconButton
import fr.plexwish.anime.ui.components.ErrorMessage
import fr.plexwish.anime.ui.components.GhostButton
import fr.plexwish.anime.ui.components.LinkButton
import fr.plexwish.anime.ui.components.Loading
import fr.plexwish.anime.ui.components.ScreenTitle
import fr.plexwish.anime.ui.components.StateBox
import fr.plexwish.anime.ui.components.focusRing
import fr.plexwish.anime.ui.components.largeText
import fr.plexwish.anime.ui.theme.AppIcons
import fr.plexwish.anime.ui.theme.AppTheme
import fr.plexwish.anime.ui.theme.Dimens
import fr.plexwish.anime.ui.theme.PillShape

/** Actions de la bibliothèque (un seul objet : l'écran reste simple à prévisualiser et à tester). */
data class LibraryActions(
    val onQuery: (String) -> Unit = {},
    val onSort: (LibrarySort) -> Unit = {},
    val onWatch: (WatchFilter) -> Unit = {},
    val onGenre: (String?) -> Unit = {},
    val onPeriod: (Period?) -> Unit = {},
    val clearFilters: () -> Unit = {},
    val clearAll: () -> Unit = {},
    val retry: () -> Unit = {},
    val loadMore: () -> Unit = {},
    val onAnime: (Long) -> Unit = {},
)

/** Bibliothèque (et onglet « Rechercher » : même écran, champ de recherche focalisé, clavier ouvert). */
@Composable
fun LibraryScreen(vm: LibraryViewModel, onAnime: (Long) -> Unit, padding: PaddingValues, searchMode: Boolean = false) {
    val s by vm.state.collectAsStateWithLifecycle()
    LibraryContent(
        s, searchMode, padding,
        LibraryActions(vm::onQuery, vm::onSort, vm::onWatch, vm::onGenre, vm::onPeriod, vm::clearFilters, vm::clearAll, vm::retry,
            vm::loadMore, onAnime),
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LibraryContent(s: LibraryState, searchMode: Boolean, padding: PaddingValues, a: LibraryActions, grid: LazyGridState = rememberLazyGridState()) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (searchMode) LaunchedEffect(Unit) { runCatching { focus.requestFocus(); keyboard?.show() } }
    // Pages suivantes chargées en approchant de la fin de la grille.
    val nearEnd by remember { derivedStateOf {
        val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        last >= grid.layoutInfo.totalItemsCount - 12
    } }
    LaunchedEffect(grid) { snapshotFlow { nearEnd }.collect { if (it) a.loadMore() } }
    val p = AppTheme.palette
    val large = largeText()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (large) 150.dp else 104.dp),
        state = grid,
        contentPadding = PaddingValues(start = Dimens.gutter, end = Dimens.gutter, bottom = Dimens.s5),
        horizontalArrangement = Arrangement.spacedBy(Dimens.s3),
        verticalArrangement = Arrangement.spacedBy(Dimens.s4),
        modifier = Modifier.fillMaxSize().padding(padding),
    ) {
        val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
        item(span = full, key = "head") {
            Column(Modifier.padding(top = Dimens.s3), verticalArrangement = Arrangement.spacedBy(Dimens.s3)) {
                androidx.compose.foundation.layout.FlowRow(verticalArrangement = Arrangement.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.s3), itemVerticalAlignment = Alignment.Bottom) {
                    ScreenTitle(if (searchMode) "Rechercher" else "Bibliothèque")
                    Text(
                        when {
                            s.loading && s.items.isEmpty() -> "Chargement…"
                            s.error != null && s.items.isEmpty() -> ""
                            else -> "${"%,d".format(s.total).replace(',', ' ')} animé${if (s.total > 1) "s" else ""}"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = p.text2,
                        modifier = Modifier.padding(bottom = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                OutlinedTextField(
                    value = s.query, onValueChange = a.onQuery, singleLine = true,
                    placeholder = { Text("Rechercher un animé") },
                    leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                    trailingIcon = { if (s.query.isNotEmpty()) AppIconButton(AppIcons.Close, "Effacer la recherche", { a.onQuery("") }) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    shape = PillShape,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = p.surface2, focusedContainerColor = p.surface2,
                        unfocusedBorderColor = p.surface2),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Rechercher un animé" },
                )
            }
        }
        item(span = full, key = "filters") { Filters(s, a) }
        when {
            s.error != null && s.items.isEmpty() -> item(span = full, key = "error") {
                ErrorMessage(s.error, a.retry, title = "Impossible de charger la bibliothèque")
            }
            s.loading && s.items.isEmpty() -> items(12, key = { "sk$it" }) { AnimeCardSkeleton() }
            !s.loading && s.items.isEmpty() -> item(span = full, key = "empty") { Empty(s, a) }
            else -> {
                items(s.items, key = { it.id }) { anime -> AnimeCard(anime) { a.onAnime(anime.id) } }
                if (s.loadingMore) item(span = full, key = "more") { Loading() }
                s.error?.let { item(span = full, key = "error-more") { ErrorMessage(it, a.retry) } }
            }
        }
    }
}

/** Filtres sur une ligne qui défile (pas de mur de boutons au-dessus des résultats), comme le web sur téléphone. */
@Composable
private fun Filters(s: LibraryState, a: LibraryActions) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s2), verticalAlignment = Alignment.CenterVertically) {
        WatchFilter.entries.forEach { w ->
            item(w.name) {
                Chip(w.label, selected = s.watch == w, onClick = { a.onWatch(w) })
            }
        }
        item("genre") {
            MenuChip(
                label = s.genreLabel ?: "Tous les genres", selected = s.genre != null, menuLabel = "Genre",
                options = listOf<Pair<String?, String>>(null to "Tous les genres") +
                    s.genres.map { it.genre to "${it.label} (${it.animeCount})" } +
                    (if (s.genre != null && s.genres.none { it.genre == s.genre }) listOf(s.genre to s.genre) else emptyList()),
                current = s.genre, onSelect = a.onGenre,
            )
        }
        item("period") {
            MenuChip(
                label = s.period?.label ?: "Toutes les années", selected = s.period != null, menuLabel = "Période",
                options = listOf<Pair<Period?, String>>(null to "Toutes les années") + Period.entries.map { it to it.label },
                current = s.period, onSelect = a.onPeriod,
            )
        }
        item("sort") {
            MenuChip(
                label = s.sort.label, selected = false, menuLabel = "Trier par",
                options = LibrarySort.entries.map { it to it.label }, current = s.sort, onSelect = { it?.let(a.onSort) },
            )
        }
        if (s.filtered) item("clear") { LinkButton("Effacer les filtres", a.clearFilters) }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit, trailing: Boolean = false, description: String? = null) {
    val p = AppTheme.palette
    FilterChip(
        selected = selected, onClick = onClick, shape = PillShape,
        label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
        leadingIcon = if (selected && !trailing) ({ Icon(AppIcons.Check, contentDescription = null) }) else null,
        trailingIcon = if (trailing) ({ Icon(AppIcons.KeyboardArrowDown, contentDescription = null) }) else null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = p.surface1, labelColor = p.text, iconColor = p.text2,
            selectedContainerColor = p.accentSoft, selectedLabelColor = p.text, selectedLeadingIconColor = p.accent,
            selectedTrailingIconColor = p.accent,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = p.outlineStrong,
            selectedBorderColor = p.accent, selectedBorderWidth = 1.5.dp),
        modifier = Modifier.focusRing(PillShape).heightIn(min = Dimens.target)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    )
}

/** Puce qui ouvre une liste (genre, période, tri) ; l'option choisie est cochée. */
@Composable
private fun <T> MenuChip(label: String, selected: Boolean, menuLabel: String, options: List<Pair<T?, String>>, current: T?, onSelect: (T?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip(label, selected, onClick = { open = true }, trailing = true, description = "$menuLabel : $label")
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = { if (value == current) Icon(AppIcons.Check, contentDescription = "choisi") },
                    onClick = { open = false; onSelect(value) },
                    modifier = Modifier.focusRing(),
                )
            }
        }
    }
}

/** Liste vide : le message dit pourquoi, comme le web. */
@Composable
private fun Empty(s: LibraryState, a: LibraryActions) {
    val onlyWatch = s.query.isBlank() && s.genre == null && s.period == null
    when {
        onlyWatch && s.watch == null -> StateBox(AppIcons.VideoLibrary, "La bibliothèque est vide",
            message = "Aucun animé n'a encore été importé. Revenez un peu plus tard.")
        onlyWatch && s.watch == WatchFilter.IN_PROGRESS -> StateBox(AppIcons.History, "Aucun animé en cours",
            message = "Les animés commencés apparaîtront ici.", actions = { GhostButton("Effacer le filtre", a.clearFilters) })
        onlyWatch && s.watch == WatchFilter.SEEN -> StateBox(AppIcons.CheckCircleFill, "Aucun animé vu en entier pour l'instant",
            message = "Un animé apparaît ici quand tous ses épisodes ont été regardés.", actions = { GhostButton("Effacer le filtre", a.clearFilters) })
        else -> StateBox(
            AppIcons.Search, if (s.query.isNotBlank()) "Aucun animé pour « ${s.query.trim()} »" else "Aucun animé ne correspond",
            message = if (s.filtered) "Essayez avec moins de filtres, ou une partie du titre seulement."
            else "Vérifiez l'orthographe, ou cherchez une partie du titre.",
            actions = { GhostButton("Tout effacer", a.clearAll) },
        )
    }
}
