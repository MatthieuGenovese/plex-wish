package fr.plexwish.anime.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Fond d'image floutée (bandeau d'accueil, fiche, carte « Reprendre ») : l'affiche agrandie et floutée (Android 12+ ;
 * avant, sans flou), assombrie par un dégradé pour que le texte blanc reste lisible. Sans affiche : dégradé tiré du
 * titre. Toujours sombre, quel que soit le thème (comme le web).
 */
@Composable
fun BoxScope.Backdrop(title: String, url: String?, blur: Dp = 32.dp, strength: Float = 1f) {
    val h = hue(title).toFloat()
    Box(Modifier.matchParentSize().background(
        Brush.linearGradient(listOf(Color.hsl(h, 0.40f, 0.26f), Color.hsl((h + 60f) % 360f, 0.40f, 0.12f)))))
    if (url != null) {
        AsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().graphicsLayer { scaleX = 1.15f; scaleY = 1.15f }
                .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(blur) else Modifier),
        )
    }
    Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
        listOf(Color(0xFF080A0E).copy(alpha = 0.86f * strength), Color(0xFF080A0E).copy(alpha = 0.50f * strength)))))
    Box(Modifier.matchParentSize().background(Brush.verticalGradient(
        0.45f to Color.Transparent, 1f to Color(0xFF080A0E).copy(alpha = 0.6f * strength))))
}
