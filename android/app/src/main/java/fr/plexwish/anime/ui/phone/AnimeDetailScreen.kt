package fr.plexwish.anime.ui.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import fr.plexwish.anime.data.api.CastEntry
import fr.plexwish.anime.data.api.EpisodeSummary
import fr.plexwish.anime.data.api.ProgressDto
import fr.plexwish.anime.feature.detail.AnimeDetailViewModel
import fr.plexwish.anime.feature.detail.DetailState
import fr.plexwish.anime.feature.detail.ROLE_LABELS
import fr.plexwish.anime.ui.components.ErrorMessage
import fr.plexwish.anime.ui.components.Loading
import fr.plexwish.anime.ui.components.Poster
import fr.plexwish.anime.ui.components.TMDB_NOTICE
import fr.plexwish.anime.ui.theme.AppColors

/**
 * Fiche d'un animé : affiche, titres, synopsis et sources ; saisons ; épisodes (par tranches de 100) avec la
 * progression ; distribution en grille qui passe à la ligne (photo et nom du comédien, nom du personnage, rôle ;
 * jamais d'image de personnage). {@code onEpisode} : lecture (bloc suivant), null tant qu'il n'y a pas de lecteur.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnimeDetailScreen(
    vm: AnimeDetailViewModel,
    onPerson: (String) -> Unit,
    padding: PaddingValues,
    onEpisode: ((EpisodeSummary) -> Unit)? = null,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    // Progression relue à chaque retour sur la fiche (après le lecteur), pas au premier affichage.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        var first = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!first) {
                kotlinx.coroutines.delay(800) // la dernière position part en arrière-plan à la sortie du lecteur
                vm.refreshProgress()
            }
            first = false
        }
    }

    val anime = s.anime
    when {
        anime == null && s.loading -> Loading(Modifier.padding(padding))
        anime == null -> ErrorMessage(
            if (s.notFound) "Cet animé n'existe pas ou n'a plus d'épisode disponible." else s.error ?: "Erreur inconnue.",
            if (s.notFound) null else vm::load, Modifier.padding(padding),
        )
        else -> BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // Distribution : autant de colonnes que la largeur le permet (≥ 104 dp chacune), lignes ajoutées au besoin.
            val columns = ((maxWidth.value - 32) / 112).toInt().coerceAtLeast(2)
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "hero") { Hero(s) }
                if (anime.seasons.size > 1) {
                    item(key = "seasons") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            anime.seasons.forEach { season ->
                                FilterChip(
                                    selected = season.id == s.seasonId, onClick = { vm.selectSeason(season.id) },
                                    label = { Text("${season.label} · ${season.episodeCount}") },
                                )
                            }
                        }
                    }
                }
                s.season?.let { season ->
                    item(key = "season-title") {
                        Text(
                            "${season.label} · ${season.episodeCount} épisode${if (season.episodeCount > 1) "s" else ""}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                        )
                    }
                }
                if (s.chunks.isNotEmpty()) {
                    item(key = "chunks") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.chunks.forEach { c ->
                                FilterChip(selected = c.index == s.chunk, onClick = { vm.selectChunk(c.index) }, label = { Text(c.label) })
                            }
                        }
                    }
                }
                when {
                    s.episodesLoading -> item(key = "episodes-loading") { Loading() }
                    s.episodesError != null -> item(key = "episodes-error") { ErrorMessage(s.episodesError!!, vm::retryEpisodes) }
                    else -> items(s.visibleEpisodes, key = { "e" + it.id }) { e ->
                        EpisodeRow(e, s.progress[e.id], onEpisode)
                    }
                }
                if (s.cast.isNotEmpty()) {
                    item(key = "cast-title") {
                        Text("Distribution · voix japonaises", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 20.dp).semantics { heading() })
                    }
                    itemsIndexed(s.cast.chunked(columns), key = { i, _ -> "c$i" }) { _, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            row.forEach { e -> CastCard(e, onPerson, Modifier.weight(1f)) }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    s.castSource?.let { src ->
                        item(key = "cast-source") { Muted("Distribution : $src") }
                    }
                }
                if (anime.tmdbUrl != null || anime.synopsisSource == "TMDB") {
                    item(key = "tmdb") { Muted(TMDB_NOTICE, Modifier.padding(top = 16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Hero(s: DetailState) {
    val a = s.anime ?: return
    val uri = LocalUriHandler.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Poster(a.title, a.posterLargeUrl ?: a.posterUrl, Modifier.width(120.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(a.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                s.subtitle?.let { Muted(it) }
                a.year?.let { Muted(it.toString()) }
            }
        }
        a.synopsis?.let { text ->
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis)
            if (text.length > 280) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Réduire" else "Lire la suite") }
            }
        }
        val sources = listOfNotNull(a.metadataSource?.let { it to a.metadataUrl }, a.tmdbUrl?.let { "TMDB" to it })
        if (sources.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val hint = if (a.synopsis != null && a.synopsisLanguage == "en") "Synopsis en anglais (pas de traduction française) · " else ""
                Muted("${hint}Sources :")
                sources.forEach { (name, url) ->
                    if (url != null) {
                        TextButton(onClick = { uri.openUri(url) }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(name) }
                    } else Muted(name)
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(e: EpisodeSummary, progress: ProgressDto?, onEpisode: ((EpisodeSummary) -> Unit)?) {
    val title = e.title ?: "Épisode ${e.episodeNumber}"
    val minutes = e.durationSeconds?.let { (it + 30) / 60 }
    val state = when {
        progress == null -> null
        progress.completed -> "vu"
        progress.positionSeconds > 0 -> "en cours, ${progress.positionSeconds * 100 / progress.durationSeconds.coerceAtLeast(1)} %"
        else -> null
    }
    val label = listOfNotNull("Épisode ${e.episodeNumber}", e.title, minutes?.let { "$it min" }, state).joinToString(", ")
    Surface(
        color = AppColors.Surface, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onEpisode != null) Modifier.clickable(role = Role.Button) { onEpisode(e) } else Modifier)
            .clearAndSetSemantics {
                contentDescription = label
                if (onEpisode != null) {
                    role = Role.Button
                    onClick("Lire") { onEpisode(e); true }
                }
            },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${e.episodeNumber}", color = AppColors.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    minutes?.let { Muted("$it min") }
                }
                if (progress?.completed == true) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AppColors.Success)
                }
            }
            if (progress != null && !progress.completed && progress.positionSeconds > 0) {
                LinearProgressIndicator(
                    progress = { progress.positionSeconds.toFloat() / progress.durationSeconds.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

/** Carte de la distribution : photo et nom du comédien, nom du personnage, rôle. Lien vers la page du comédien. */
@Composable
private fun CastCard(e: CastEntry, onPerson: (String) -> Unit, modifier: Modifier) {
    val roleLabel = ROLE_LABELS[e.role] ?: e.role
    val p = e.person
    val label = if (p != null) "${p.name}, voix de ${e.character.name} ($roleLabel)" else "Voix non renseignée, ${e.character.name} ($roleLabel)"
    Column(
        modifier
            .then(if (p != null) Modifier.clickable(role = Role.Button) { onPerson(p.id) } else Modifier)
            .clearAndSetSemantics {
                contentDescription = label
                if (p != null) {
                    role = Role.Button
                    onClick("Ouvrir la page du comédien") { onPerson(p.id); true }
                }
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Sans comédien : visuel de remplacement (initiales du personnage), jamais d'image de personnage.
        Poster(p?.name ?: e.character.name, p?.imageUrl)
        Text(p?.name ?: "Voix non renseignée", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            color = if (p != null) AppColors.Text else AppColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(e.character.name, style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        Text(roleLabel, style = MaterialTheme.typography.labelSmall,
            color = if (e.role == "MAIN") AppColors.Success else AppColors.TextMuted)
    }
}

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}
