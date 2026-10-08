package fr.plexwish.anime.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import fr.plexwish.anime.data.api.EpisodeSummary
import fr.plexwish.anime.data.api.ProgressDto
import fr.plexwish.anime.data.api.ResumeDto
import fr.plexwish.anime.feature.common.Viewing
import fr.plexwish.anime.feature.detail.AnimeDetailViewModel
import fr.plexwish.anime.feature.detail.DetailState
import fr.plexwish.anime.ui.components.Backdrop
import fr.plexwish.anime.ui.components.Choice
import fr.plexwish.anime.ui.components.ErrorMessage
import fr.plexwish.anime.ui.components.GhostButton
import fr.plexwish.anime.ui.components.LinkButton
import fr.plexwish.anime.ui.components.Loading
import fr.plexwish.anime.ui.components.PersonAvatar
import fr.plexwish.anime.ui.components.Poster
import fr.plexwish.anime.ui.components.PrimaryButton
import fr.plexwish.anime.ui.components.ProgressBar
import fr.plexwish.anime.ui.components.Rail
import fr.plexwish.anime.ui.components.SectionTitle
import fr.plexwish.anime.ui.components.Segmented
import fr.plexwish.anime.ui.components.Skeleton
import fr.plexwish.anime.ui.components.StateBox
import fr.plexwish.anime.ui.components.TMDB_NOTICE
import fr.plexwish.anime.ui.components.focusRing
import fr.plexwish.anime.ui.components.largeText
import fr.plexwish.anime.ui.theme.AppIcons
import fr.plexwish.anime.ui.theme.AppShapes
import fr.plexwish.anime.ui.theme.AppTheme
import fr.plexwish.anime.ui.theme.Dimens
import fr.plexwish.anime.ui.theme.OnImagePalette
import fr.plexwish.anime.ui.theme.PaletteTheme
import fr.plexwish.anime.ui.theme.PillShape

/** Actions de la fiche. */
data class DetailActions(
    val onPlay: (episodeId: Long) -> Unit = {},
    val onPerson: (String) -> Unit = {},
    val onGenre: (String) -> Unit = {},
    val onSeason: (Long) -> Unit = {},
    val onChunk: (Int) -> Unit = {},
    val retry: () -> Unit = {},
    val retryEpisodes: () -> Unit = {},
)

/**
 * Fiche d'un animé (comme le web) : bandeau (affiche, titres, genres, bouton principal S5 « Reprendre » /
 * « Épisode suivant » / « Commencer » / « Revoir »), synopsis replié, sources, saisons et tranches, épisodes avec
 * leur état (vu, en cours), distribution en portraits ronds. Lecture directe depuis le bouton ou un épisode.
 */
@Composable
fun AnimeDetailScreen(
    vm: AnimeDetailViewModel, onPerson: (String) -> Unit, onPlay: (Long) -> Unit, onGenre: (String) -> Unit, padding: PaddingValues,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    // Progression et bouton principal relus à chaque retour sur la fiche (après le lecteur), pas au premier affichage.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        var first = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!first) vm.refreshProgress()
            first = false
        }
    }
    DetailContent(s, padding, DetailActions(onPlay, onPerson, onGenre, vm::selectSeason, vm::selectChunk, vm::load, vm::retryEpisodes))
}

@Composable
fun DetailContent(s: DetailState, padding: PaddingValues, a: DetailActions) {
    val anime = s.anime
    when {
        anime == null && s.loading -> DetailSkeleton(padding)
        anime == null -> Box(Modifier.padding(padding)) {
            if (s.notFound) {
                StateBox(AppIcons.Explore, "Animé introuvable", message = "Cet animé n'existe pas ou n'a plus d'épisode disponible.")
            } else {
                ErrorMessage(s.error ?: "Erreur inconnue.", a.retry, title = "Impossible de charger la fiche")
            }
        }
        else -> LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = Dimens.s6),
            verticalArrangement = Arrangement.spacedBy(Dimens.s3),
        ) {
            item("hero") { Hero(s, a) }
            item("synopsis") { Synopsis(s) }
            item("episodes-title") {
                SectionTitle("Épisodes", Modifier.padding(start = Dimens.gutter, end = Dimens.gutter, top = Dimens.s4))
            }
            if (anime.seasons.size > 1) item("seasons") { Seasons(s, a) }
            if (s.chunks.isNotEmpty()) item("chunks") { Chunks(s, a) }
            s.season?.let { season ->
                item("season-count") {
                    Text("${season.label} · ${Viewing.count(season.episodeCount, "épisode")}", style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.palette.text2, modifier = Modifier.padding(horizontal = Dimens.gutter))
                }
            }
            when {
                s.episodesLoading -> item("episodes-loading") { Loading() }
                s.episodesError != null -> item("episodes-error") { ErrorMessage(s.episodesError, a.retryEpisodes) }
                else -> items(s.visibleEpisodes, key = { "e" + it.id }) { e ->
                    EpisodeRow(e, s.progress[e.id], next = anime.resume?.let { r -> r.kind != "REWATCH" && r.episodeId == e.id } == true, onPlay = { a.onPlay(e.id) })
                }
            }
            if (s.cast.isNotEmpty()) item("cast") {
                Column(Modifier.padding(top = Dimens.s4)) {
                    Rail("Distribution · voix japonaises") {
                        items(s.cast, key = { it.person?.id + "/" + it.character.name }) { e -> PersonAvatar(e, a.onPerson) }
                    }
                    s.castSource?.let {
                        Text("Distribution : $it", style = MaterialTheme.typography.bodySmall, color = AppTheme.palette.text3,
                            modifier = Modifier.padding(horizontal = Dimens.gutter))
                    }
                }
            }
            if (anime.tmdbUrl != null || anime.synopsisSource == "TMDB") item("tmdb") {
                Text(TMDB_NOTICE, style = MaterialTheme.typography.bodySmall, color = AppTheme.palette.text3,
                    modifier = Modifier.padding(horizontal = Dimens.gutter, vertical = Dimens.s4))
            }
        }
    }
}

/** Libellés du bouton principal (S5), comme le web. */
private fun ResumeDto.title() = when (kind) {
    "RESUME" -> "Vous en êtes à"
    "NEXT" -> "Prochain épisode"
    "REWATCH" -> "Vous avez tout vu"
    else -> "Pour commencer"
}

private fun ResumeDto.action() = when (kind) {
    "RESUME" -> "Reprendre"
    "NEXT" -> "Épisode suivant"
    "REWATCH" -> "Revoir depuis le début"
    else -> "Commencer"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(s: DetailState, a: DetailActions) {
    val anime = s.anime ?: return
    val large = largeText()
    val episodes = anime.seasons.sumOf { it.episodeCount }
    val normal = anime.seasons.count { it.seasonNumber > 0 }
    val meta = listOfNotNull(anime.year?.toString(), if (normal > 1) "$normal saisons" else null, Viewing.count(episodes, "épisode"))
        .joinToString(" · ")
    PaletteTheme(OnImagePalette) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).clip(AppShapes.large)) {
            Backdrop(anime.title, anime.posterLargeUrl ?: anime.posterUrl)
            Column(Modifier.padding(Dimens.s4), verticalArrangement = Arrangement.spacedBy(Dimens.s3)) {
                val titles: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, style = MaterialTheme.typography.headlineSmall, color = Color.White,
                            modifier = Modifier.semantics { heading() })
                        s.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f)) }
                        Text(meta, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                    }
                }
                if (large) {
                    Poster(anime.title, anime.posterLargeUrl ?: anime.posterUrl, Modifier.width(128.dp))
                    titles()
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s4), verticalAlignment = Alignment.Bottom) {
                        Poster(anime.title, anime.posterLargeUrl ?: anime.posterUrl, Modifier.width(112.dp))
                        Box(Modifier.weight(1f)) { titles() }
                    }
                }
                if (anime.genres.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s2), verticalArrangement = Arrangement.spacedBy(Dimens.s2)) {
                        anime.genres.forEach { g ->
                            Text(g.label, style = MaterialTheme.typography.labelMedium, color = Color.White,
                                modifier = Modifier.focusRing(PillShape).clip(PillShape)
                                    .border(1.dp, Color.White.copy(alpha = 0.5f), PillShape)
                                    .clickable(role = Role.Button, onClickLabel = "Voir les animés de ce genre") { a.onGenre(g.genre) }
                                    .heightIn(min = Dimens.target).padding(horizontal = 14.dp, vertical = 14.dp))
                        }
                    }
                }
                anime.resume?.let { r -> ResumeBox(r, a) }
            }
        }
    }
}

/** Où l'on en est (S5) : épisode, progression, bouton principal. */
@Composable
private fun ResumeBox(r: ResumeDto, a: DetailActions) {
    val left = if (r.kind == "RESUME") Viewing.remainingMinutes(r.positionSeconds, r.durationSeconds) else null
    Column(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f), AppShapes.medium)
            .border(1.dp, Color.White.copy(alpha = 0.15f), AppShapes.medium).padding(Dimens.s3),
        verticalArrangement = Arrangement.spacedBy(Dimens.s2),
    ) {
        Text(r.title().uppercase(), style = MaterialTheme.typography.labelSmall, color = AppTheme.palette.accent)
        Text(Viewing.longEpisode(r.seasonNumber, r.episodeNumber) + (r.episodeTitle?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.titleSmall, color = Color.White)
        if (r.kind == "RESUME" && r.durationSeconds > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s3)) {
                ProgressBar(Viewing.fraction(r.positionSeconds, r.durationSeconds), Modifier.weight(1f), trackColor = Color.White.copy(alpha = 0.3f))
                left?.let { Text("reste $it min", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f)) }
            }
        }
        PrimaryButton(r.action(), { a.onPlay(r.episodeId) }, Modifier.fillMaxWidth(),
            icon = if (r.kind == "REWATCH") AppIcons.Refresh else AppIcons.PlayArrowFill)
    }
}

@Composable
private fun Synopsis(s: DetailState) {
    val anime = s.anime ?: return
    val p = AppTheme.palette
    val uri = LocalUriHandler.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = Dimens.gutter), verticalArrangement = Arrangement.spacedBy(Dimens.s1)) {
        val text = anime.synopsis
        if (text != null) {
            Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
            if (text.length > 240) LinkButton(if (expanded) "Réduire" else "Lire la suite", { expanded = !expanded })
            if (anime.synopsisLanguage == "en") {
                Text("Synopsis en anglais (pas de traduction française).", style = MaterialTheme.typography.bodySmall, color = p.text3)
            }
        } else {
            Text("Pas de synopsis pour cet animé.", style = MaterialTheme.typography.bodyMedium, color = p.text2)
        }
        val sources = listOfNotNull(anime.metadataSource?.let { it to anime.metadataUrl }, anime.tmdbUrl?.let { "TMDB" to it })
        if (sources.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sources :", style = MaterialTheme.typography.bodySmall, color = p.text2)
                sources.forEach { (name, url) ->
                    if (url != null) LinkButton(name, { uri.openUri(url) })
                    else Text(" $name", style = MaterialTheme.typography.bodySmall, color = p.text2)
                }
            }
        }
    }
}

/** Saisons : boutons côte à côte jusqu'à 3, au-delà une liste (comme le web, avec la largeur d'un téléphone). */
@Composable
private fun Seasons(s: DetailState, a: DetailActions) {
    val seasons = s.anime?.seasons ?: return
    Box(Modifier.padding(horizontal = Dimens.gutter)) {
        if (seasons.size <= 3 && !largeText()) {
            Segmented(seasons.map { Choice(it.id, it.label) }, s.seasonId ?: -1, a.onSeason)
        } else {
            SelectButton("Saison", s.season?.label ?: "", seasons.map { it.id to it.label }, s.seasonId, a.onSeason)
        }
    }
}

@Composable
private fun Chunks(s: DetailState, a: DetailActions) {
    Box(Modifier.padding(horizontal = Dimens.gutter)) {
        SelectButton("Épisodes", "Épisodes ${s.chunks.getOrNull(s.chunk)?.label ?: ""}",
            s.chunks.map { it.index to "Épisodes ${it.label}" }, s.chunk, a.onChunk)
    }
}

/** Bouton qui ouvre une liste de choix (saison au-delà de 3, tranche d'épisodes). */
@Composable
private fun <T> SelectButton(name: String, label: String, options: List<Pair<T, String>>, current: T?, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        GhostButton(label, { open = true }, Modifier.semantics { contentDescription = "$name : $label" }, icon = AppIcons.KeyboardArrowDown)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(value) },
                    leadingIcon = { if (value == current) Icon(AppIcons.Check, contentDescription = "choisi") },
                    modifier = Modifier.focusRing())
            }
        }
    }
}

/** Épisode : numéro, titre, durée, état (vu, en cours avec sa barre, prochain). Toucher = lecture. */
@Composable
private fun EpisodeRow(e: EpisodeSummary, progress: ProgressDto?, next: Boolean, onPlay: () -> Unit) {
    val p = AppTheme.palette
    val title = e.title ?: "Épisode ${e.episodeNumber}"
    val minutes = Viewing.minutesLabel(e.durationSeconds)
    val watching = progress != null && !progress.completed && progress.positionSeconds > 0
    val state = when {
        progress?.completed == true -> "vu"
        watching -> "en cours, reste ${Viewing.remainingMinutes(progress!!.positionSeconds, progress.durationSeconds) ?: 0} min"
        next -> "prochain épisode"
        else -> null
    }
    val label = listOfNotNull("Épisode ${e.episodeNumber}", e.title, minutes, state).joinToString(", ")
    Column(
        Modifier.padding(horizontal = Dimens.gutter).fillMaxWidth().focusRing(AppShapes.medium).clip(AppShapes.medium)
            .background(if (next) p.accentSoft else p.surface1)
            .border(1.dp, if (next) p.accent.copy(alpha = 0.5f) else p.outline, AppShapes.medium)
            .clickable(role = Role.Button, onClick = onPlay)
            .clearAndSetSemantics { contentDescription = label; role = Role.Button; onClick("Lire") { onPlay(); true } }
            .heightIn(min = 56.dp).padding(horizontal = Dimens.s3, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s3), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(if (progress?.completed == true) p.surface2 else p.accentSoft, AppShapes.small),
                contentAlignment = Alignment.Center) {
                Text("${e.episodeNumber}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    color = if (progress?.completed == true) p.text2 else p.text, textAlign = TextAlign.Center, maxLines = 1)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (progress?.completed == true) p.text2 else p.text)
                val sub = listOfNotNull(minutes, if (next && !watching) "prochain" else null).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = p.text2)
            }
            when {
                progress?.completed == true -> Icon(AppIcons.CheckCircleFill, contentDescription = null, tint = p.ok)
                else -> Icon(AppIcons.PlayArrowFill, contentDescription = null, tint = if (next) p.accent else p.text3)
            }
        }
        if (watching) ProgressBar(Viewing.fraction(progress!!.positionSeconds, progress.durationSeconds))
    }
}

@Composable
private fun DetailSkeleton(padding: PaddingValues) {
    Column(Modifier.padding(padding).padding(Dimens.gutter), verticalArrangement = Arrangement.spacedBy(Dimens.s4)) {
        Skeleton(Modifier.fillMaxWidth().heightIn(min = 300.dp), AppShapes.large)
        repeat(3) { Skeleton(Modifier.fillMaxWidth().heightIn(min = 18.dp)) }
        repeat(4) { Skeleton(Modifier.fillMaxWidth().heightIn(min = 56.dp)) }
    }
}
