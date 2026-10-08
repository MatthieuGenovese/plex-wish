package fr.plexwish.anime.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.AsyncImage
import fr.plexwish.anime.ui.theme.AppTheme
import fr.plexwish.anime.ui.theme.Figtree

/**
 * Affiche 2:3. Sans image, ou si elle ne charge pas : couverture composée (dégradé tiré du titre, titre écrit dessus),
 * jamais un carré vide, comme le web. {@code round} : portrait rond (comédiens), initiales sans photo.
 * {@code description} : texte lu par TalkBack (null si le titre est déjà affiché à côté).
 */
@Composable
fun Poster(title: String, url: String?, modifier: Modifier = Modifier, description: String? = null, round: Boolean = false) {
    var failed by remember(url) { mutableStateOf(false) }
    val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
    Box(modifier.aspectRatio(if (round) 1f else 2f / 3f).clip(shape).background(AppTheme.palette.surface2)) {
        if (url != null && !failed) {
            AsyncImage(
                model = url, contentDescription = description, contentScale = ContentScale.Crop,
                onError = { failed = true }, modifier = Modifier.fillMaxSize(),
            )
        } else if (round) {
            Initials(title, Modifier.fillMaxSize())
        } else {
            Cover(title, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Cover(title: String, modifier: Modifier) {
    val p = AppTheme.palette
    val h = hue(title).toFloat()
    val top = Color.hsl(h, p.posterS, (p.posterL + 0.08f).coerceAtMost(1f))
    val bottom = Color.hsl((h + 40f) % 360f, p.posterS, (p.posterL - 0.10f).coerceAtLeast(0f))
    BoxWithConstraints(modifier.background(Brush.linearGradient(listOf(top, bottom))).clearAndSetSemantics { }) {
        val small = maxWidth < 72.dp
        if (!small) {
            Box(Modifier.padding(start = maxWidth * 0.1f, top = maxHeight * 0.1f).width(maxWidth * 0.22f).height(3.dp)
                .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(2.dp)))
            // Décor (le titre est écrit à côté, en texte qui suit la taille du système) : taille fixe, en dp.
            val size = with(LocalDensity.current) { (maxWidth.value * 0.12f).coerceIn(11f, 22f).dp.toSp() }
            Text(
                title, maxLines = 5, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = size, lineHeight = size * 1.12f,
                    color = Color.White, shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1f), 2f)),
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(maxWidth * 0.1f),
            )
        }
    }
}

@Composable
private fun Initials(title: String, modifier: Modifier) {
    val h = hue(title).toFloat()
    val brush = Brush.linearGradient(listOf(Color.hsl(h, 0.40f, 0.46f), Color.hsl((h + 50f) % 360f, 0.40f, 0.28f)))
    BoxWithConstraints(modifier.background(brush).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        val size = with(LocalDensity.current) { (maxWidth.value * 0.30f).coerceIn(14f, 34f).dp.toSp() }
        Text(initials(title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = size, fontFamily = Figtree)
    }
}

/** Initiales des deux premiers mots (comme le web). */
fun initials(title: String): String = title.split(Regex("[\\s_\\-:]+"))
    .filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
    .take(2).joinToString("") { it[0].uppercase() }

/** Teinte stable tirée du titre (0–359), même calcul que le web. */
fun hue(title: String): Int {
    var h = 0
    for (c in title) h = (h * 31 + c.code) % 360
    return h
}
