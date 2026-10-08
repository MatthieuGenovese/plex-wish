package fr.plexwish.anime.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fr.plexwish.anime.ui.theme.AppColors

/**
 * Affiche (ou portrait) au format 2:3. Sans image, ou si elle ne charge pas : visuel de remplacement (couleur
 * tirée du titre + initiales), comme sur le web. {@code url} : déjà résolue vers notre serveur (ImageUrls).
 * {@code description} : texte lu par TalkBack (null si le titre est déjà affiché à côté).
 */
@Composable
fun Poster(title: String, url: String?, modifier: Modifier = Modifier, description: String? = null) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .background(AppColors.SurfaceRaised),
    ) {
        if (url != null && !failed) {
            AsyncImage(
                model = url,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Placeholder(title, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Placeholder(title: String, modifier: Modifier) {
    BoxWithConstraints(modifier.background(placeholderColor(title)).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        val size = (maxWidth.value * 0.22f).coerceIn(10f, 32f)
        Text(initials(title), color = AppColors.Text, fontWeight = FontWeight.Bold, fontSize = size.sp)
    }
}

/** Initiales des deux premiers mots (comme le web). */
fun initials(title: String): String = title.split(Regex("[\\s_\\-:]+"))
    .filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
    .take(2).joinToString("") { it[0].uppercase() }

private fun placeholderColor(title: String): Color {
    var h = 0
    for (c in title) h = (h * 31 + c.code) % 360
    return Color.hsl(h.toFloat(), 0.35f, 0.26f)
}
