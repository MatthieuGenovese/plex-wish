package fr.plexwish.anime.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import fr.plexwish.anime.ui.theme.AppShapes
import fr.plexwish.anime.ui.theme.LocalPalette

/**
 * Anneau de focus commun (clavier, télécommande ; Android TV plus tard) : contour de 3 dp couleur « focus », dessiné
 * juste AUTOUR de l'élément (il ne recouvre jamais son contenu) et, pour les cartes, léger agrandissement. À placer
 * AVANT le {@code clip} / {@code clickable} de l'élément. Au toucher, les éléments ne prennent pas le focus :
 * l'anneau n'apparaît qu'à la navigation par touches.
 */
fun Modifier.focusRing(shape: Shape = AppShapes.medium, grow: Boolean = false): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && grow) 1.04f else 1f, label = "focusScale")
    val color = LocalPalette.current.focus
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .drawWithContent {
            drawContent()
            if (focused) {
                val gap = 2.dp.toPx()
                val width = 3.dp.toPx()
                val o = gap + width / 2
                val outline = shape.createOutline(Size(size.width + 2 * o, size.height + 2 * o), layoutDirection, this)
                translate(-o, -o) { drawOutline(outline, color, style = Stroke(width)) }
            }
        }
        .onFocusChanged { focused = it.isFocused }
}
