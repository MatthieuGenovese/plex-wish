package fr.plexwish.anime.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import fr.plexwish.anime.BuildConfig
import fr.plexwish.anime.data.api.AnimeSummary
import fr.plexwish.anime.data.api.ContinueWatching
import fr.plexwish.anime.feature.common.Viewing
import fr.plexwish.anime.feature.home.HomeState
import fr.plexwish.anime.feature.home.HomeViewModel
import fr.plexwish.anime.ui.components.AnimeCard
import fr.plexwish.anime.ui.components.AnimeCardSkeleton
import fr.plexwish.anime.ui.components.Backdrop
import fr.plexwish.anime.ui.components.ErrorMessage
import fr.plexwish.anime.ui.components.GhostButton
import fr.plexwish.anime.ui.components.Poster
import fr.plexwish.anime.ui.components.PrimaryButton
import fr.plexwish.anime.ui.components.ProgressBar
import fr.plexwish.anime.ui.components.Rail
import fr.plexwish.anime.ui.components.ResumeCard
import fr.plexwish.anime.ui.components.Skeleton
import fr.plexwish.anime.ui.components.StateBox
import fr.plexwish.anime.ui.components.actionLabel
import fr.plexwish.anime.ui.components.largeText
import fr.plexwish.anime.ui.components.metaLabel
import fr.plexwish.anime.ui.theme.AppIcons
import fr.plexwish.anime.ui.theme.AppShapes
import fr.plexwish.anime.ui.theme.AppTheme
import fr.plexwish.anime.ui.theme.Dimens
import fr.plexwish.anime.ui.theme.OnImagePalette
import fr.plexwish.anime.ui.theme.PaletteTheme

/** Accueil : bandeau, puis rangées. Rechargé en tirant vers le bas ; « Continuer » relu au retour sur l'écran. */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onPlay: (ContinueWatching) -> Unit,
    onAnime: (Long) -> Unit,
    onGenre: (String) -> Unit,
    onLibrary: () -> Unit,
    padding: PaddingValues,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        var first = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!first) vm.refreshContinueWatching()
            first = false
        }
    }
    val s by vm.state.collectAsStateWithLifecycle()
    HomeContent(s, onPlay, onAnime, onGenre, onLibrary, vm::load, padding)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    s: HomeState,
    onPlay: (ContinueWatching) -> Unit,
    onAnime: (Long) -> Unit,
    onGenre: (String) -> Unit,
    onLibrary: () -> Unit,
    onReload: () -> Unit,
    padding: PaddingValues,
) {
    val hasData = s.recent.isNotEmpty() || s.continueWatching.isNotEmpty()
    PullToRefreshBox(isRefreshing = s.loading && hasData, onRefresh = onReload, modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Dimens.s5), verticalArrangement = Arrangement.spacedBy(Dimens.s5)) {
            item("brand") { Brand() }
            when {
                s.loading && !hasData -> item("skeleton") { HomeSkeleton() }
                s.error != null && !hasData -> item("error") { ErrorMessage(s.error, onReload, title = "Impossible de charger l'accueil") }
                s.empty -> item("empty") {
                    StateBox(AppIcons.VideoLibrary, "La bibliothèque est vide",
                        message = "L'administrateur n'a pas encore importé d'animés. Revenez un peu plus tard.",
                        actions = { GhostButton("Actualiser", onReload, icon = AppIcons.Refresh) })
                }
                else -> {
                    item("hero") {
                        val c = s.continueWatching.firstOrNull()
                        val latest = s.recent.firstOrNull()
                        when {
                            c != null -> ResumeHero(c, onPlay = { onPlay(c) }, onDetail = { onAnime(c.animeId) })
                            latest != null -> LatestHero(latest, onDetail = { onAnime(latest.id) })
                        }
                    }
                    val more = s.continueWatching.drop(1)
                    if (more.isNotEmpty()) item("continue") {
                        Rail("Continuer à regarder") { items(more, key = { it.animeId }) { c -> ResumeCard(c) { onPlay(c) } } }
                    }
                    if (s.recent.isNotEmpty()) item("recent") {
                        Rail("Récemment ajoutés", onMore = onLibrary) {
                            items(s.recent, key = { it.id }) { a -> AnimeCard(a, Modifier.width(cardWidth()), showNew = false) { onAnime(a.id) } }
                        }
                    }
                    s.genres.forEach { row ->
                        item("genre-${row.genre.genre}") {
                            Rail(row.genre.label, onMore = { onGenre(row.genre.genre) }) {
                                items(row.items, key = { it.id }) { a -> AnimeCard(a, Modifier.width(cardWidth())) { onAnime(a.id) } }
                            }
                        }
                    }
                    if (s.discover.isNotEmpty()) item("discover") {
                        Rail("À découvrir", onMore = onLibrary) {
                            items(s.discover, key = { it.id }) { a -> AnimeCard(a, Modifier.width(cardWidth())) { onAnime(a.id) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun cardWidth() = if (largeText()) 148.dp else Dimens.posterWidth

/** Logo et nom de l'application, en haut de l'accueil. */
@Composable
private fun Brand() {
    val p = AppTheme.palette
    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = Dimens.s3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(32.dp).background(p.accent, AppShapes.small), contentAlignment = Alignment.Center) {
            Icon(AppIcons.PlayArrowFill, contentDescription = null, tint = p.onAccent, modifier = Modifier.size(22.dp))
        }
        Text(BuildConfig.APP_NAME, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    }
}

/** Bandeau : l'épisode à reprendre (ou le suivant), lecture directe ; la fiche en second. */
@Composable
private fun ResumeHero(c: ContinueWatching, onPlay: () -> Unit, onDetail: () -> Unit) {
    val left = if (!c.isNext) Viewing.remainingMinutes(c.positionSeconds, c.durationSeconds) else null
    HeroFrame(
        title = c.animeTitle, posterUrl = c.posterUrl,
        kicker = if (c.isNext) "À suivre" else "À reprendre",
        meta = Viewing.longEpisode(c.seasonNumber, c.episodeNumber) + (c.episodeTitle?.let { " · $it" } ?: ""),
    ) {
        if (!c.isNext && c.durationSeconds > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s3),
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Reste ${left ?: 0} minutes" }) {
                ProgressBar(Viewing.fraction(c.positionSeconds, c.durationSeconds), Modifier.width(140.dp), trackColor = Color.White.copy(alpha = 0.3f))
                left?.let { Text("reste $it min", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f)) }
            }
        }
        PrimaryButton(c.actionLabel(), onPlay, Modifier.fillMaxWidth(), icon = AppIcons.PlayArrowFill)
        GhostButton("Voir la fiche", onDetail, Modifier.fillMaxWidth(), color = Color.White)
    }
}

/** Sans historique : le dernier ajout, vers sa fiche. */
@Composable
private fun LatestHero(a: AnimeSummary, onDetail: () -> Unit) {
    HeroFrame(
        title = a.title, posterUrl = a.posterUrl, kicker = "Dernier ajout",
        meta = listOfNotNull(a.year?.toString(), Viewing.count(a.episodeCount, "épisode")).joinToString(" · "),
    ) {
        PrimaryButton("Voir la fiche", onDetail, Modifier.fillMaxWidth(), icon = AppIcons.ChevronRight)
    }
}

/** Cadre du bandeau : affiche floutée en fond, affiche nette, texte ; texte agrandi : affiche au-dessus. */
@Composable
private fun HeroFrame(title: String, posterUrl: String?, kicker: String, meta: String, actions: @Composable ColumnScope.() -> Unit) {
    val large = largeText()
    PaletteTheme(OnImagePalette) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).clip(AppShapes.large).heightIn(min = 200.dp)) {
            Backdrop(title, posterUrl)
            Column(Modifier.padding(Dimens.s4), verticalArrangement = Arrangement.spacedBy(Dimens.s3)) {
                val text: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(kicker.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.82f))
                        Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 4,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                        Text(meta, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.88f), maxLines = 3,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                if (large) {
                    Poster(title, posterUrl, Modifier.width(120.dp))
                    text()
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s4), verticalAlignment = Alignment.Bottom) {
                        Poster(title, posterUrl, Modifier.width(104.dp))
                        Box(Modifier.weight(1f)) { text() }
                    }
                }
                actions()
            }
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s5)) {
        Skeleton(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).height(240.dp), AppShapes.large)
        Skeleton(Modifier.padding(horizontal = Dimens.gutter).width(180.dp).height(22.dp))
        Row(Modifier.padding(horizontal = Dimens.gutter), horizontalArrangement = Arrangement.spacedBy(Dimens.s3)) {
            repeat(4) { AnimeCardSkeleton(Modifier.width(Dimens.posterWidth)) }
        }
    }
}
