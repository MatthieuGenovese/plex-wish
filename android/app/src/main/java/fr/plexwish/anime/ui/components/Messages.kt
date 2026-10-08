package fr.plexwish.anime.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.plexwish.anime.ui.theme.AppIcons
import fr.plexwish.anime.ui.theme.AppShapes
import fr.plexwish.anime.ui.theme.AppTheme
import fr.plexwish.anime.ui.theme.Dimens

/**
 * État vide ou d'erreur (comme {@code app-state} du web) : icône, titre, message, actions. Annoncé par TalkBack.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StateBox(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    error: Boolean = false,
    actions: (@Composable () -> Unit)? = null,
) {
    val p = AppTheme.palette
    Column(
        modifier.fillMaxWidth().padding(Dimens.s4)
            .border(1.dp, p.outline, AppShapes.large).background(p.surface1, AppShapes.large)
            .padding(horizontal = Dimens.s5, vertical = Dimens.s6)
            .semantics(mergeDescendants = false) { liveRegion = if (error) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.s3),
    ) {
        Icon(icon, contentDescription = null, tint = if (error) p.err else p.text3, modifier = Modifier.size(36.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.text2, textAlign = TextAlign.Center) }
        if (actions != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s2, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Dimens.s2)) { actions() }
        }
    }
}

/** Erreur réseau ou serveur + « Réessayer ». */
@Composable
fun ErrorMessage(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier, title: String = "Impossible de charger") {
    StateBox(AppIcons.CloudOff, title, modifier, message = message, error = true, actions = onRetry?.let { retry ->
        { PrimaryButton("Réessayer", retry, icon = AppIcons.Refresh) }
    })
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp).semantics { contentDescription = "Chargement" }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Bloc gris qui pulse doucement, à la place d'un contenu en cours de chargement (ignoré par TalkBack). */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = AppShapes.medium) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "skeletonAlpha")
    Box(modifier.alpha(a).background(AppTheme.palette.surface2, shape))
}
