package fr.plexwish.anime.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.plexwish.anime.data.api.AnimeSummary
import fr.plexwish.anime.data.api.ContinueWatching
import fr.plexwish.anime.feature.common.Viewing
import fr.plexwish.anime.ui.theme.AppShapes
import fr.plexwish.anime.ui.theme.AppTheme
import fr.plexwish.anime.ui.theme.Dimens
import fr.plexwish.anime.ui.theme.OnImagePalette
import fr.plexwish.anime.ui.theme.PaletteTheme

/** Texte agrandi (≥ 130 %) : mises en page en colonne, cartes plus larges. */
@Composable
fun largeText(): Boolean = LocalDensity.current.fontScale >= 1.3f

/** Pastille (« Nouveau », « Android seulement »…). */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, container: Color = AppTheme.palette.accent, content: Color = AppTheme.palette.onAccent) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.background(container, AppShapes.small).padding(horizontal = 6.dp, vertical = 2.dp))
}

/**
 * Carte d'animé : affiche, titre (2 lignes), année et nombre d'épisodes ; badge « Nouveau » (14 jours) si demandé.
 * Un seul élément pour TalkBack (« titre, année, n épisodes ») ; anneau de focus et léger agrandissement à la
 * télécommande.
 */
@Composable
fun AnimeCard(a: AnimeSummary, modifier: Modifier = Modifier, showNew: Boolean = true, onClick: () -> Unit) {
    val p = AppTheme.palette
    val meta = listOfNotNull(a.year?.toString(), Viewing.count(a.episodeCount, "épisode")).joinToString(" · ")
    val fresh = showNew && Viewing.isNew(a.lastAddedAt)
    Column(
        modifier.focusRing(AppShapes.medium, grow = true).clip(AppShapes.medium).clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(a.title, if (fresh) "nouveau" else null, meta).joinToString(", ")
                role = Role.Button
                onClick { onClick(); true }
            }
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Poster(a.title, a.posterUrl, Modifier.fillMaxWidth())
            if (fresh) Badge("Nouveau", Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(meta, style = MaterialTheme.typography.bodySmall, color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Libellé de l'action d'une entrée « Continuer à regarder ». */
fun ContinueWatching.actionLabel() = if (isNext) "Épisode suivant" else "Reprendre"

/** « S1 · É3 · reste 12 min » ou « S1 · É4 » (épisode suivant). */
fun ContinueWatching.metaLabel(long: Boolean = false): String {
    val ep = if (long) Viewing.longEpisode(seasonNumber, episodeNumber) else Viewing.shortEpisode(seasonNumber, episodeNumber)
    val left = if (!isNext) Viewing.remainingMinutes(positionSeconds, durationSeconds) else null
    return listOfNotNull(ep, left?.let { "reste $it min" }).joinToString(" · ")
}

/**
 * Carte « Reprendre » / « Épisode suivant » (paysage, affiche floutée en fond, toujours sombre) : petite affiche,
 * action, titre, épisode et temps restant, barre de progression. Lancement direct de la lecture.
 */
@Composable
fun ResumeCard(c: ContinueWatching, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val large = largeText()
    val label = "${c.actionLabel()} : ${c.animeTitle}, ${c.metaLabel(long = true)}"
    PaletteTheme(OnImagePalette) {
        val p = AppTheme.palette
        Box(
            modifier.width(if (large) 320.dp else 288.dp).heightIn(min = 132.dp)
                .focusRing(AppShapes.medium, grow = true).clip(AppShapes.medium)
                .clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics { contentDescription = label; role = Role.Button; onClick { onClick(); true } },
        ) {
            Backdrop(c.animeTitle, c.posterUrl, blur = 18.dp)
            Row(Modifier.padding(Dimens.s3), horizontalArrangement = Arrangement.spacedBy(Dimens.s3), verticalAlignment = Alignment.Bottom) {
                Poster(c.animeTitle, c.posterUrl, Modifier.width(64.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(c.actionLabel().uppercase(), style = MaterialTheme.typography.labelSmall, color = p.accent)
                    Text(c.animeTitle, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(c.metaLabel(), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f), maxLines = 2)
                    if (!c.isNext && c.durationSeconds > 0) {
                        ProgressBar(Viewing.fraction(c.positionSeconds, c.durationSeconds), Modifier.padding(top = 4.dp),
                            trackColor = Color.White.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}

/** Squelette d'une carte d'animé (chargement). */
@Composable
fun AnimeCardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Skeleton(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
        Skeleton(Modifier.fillMaxWidth(0.8f).height(14.dp), RoundedCornerShape(4.dp))
    }
}
