package fr.plexwish.anime.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.plexwish.anime.ui.theme.AppTheme
import fr.plexwish.anime.ui.theme.Dimens
import fr.plexwish.anime.ui.theme.PillShape

private val ButtonPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)

@Composable
private fun RowScope.Content(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, textAlign = TextAlign.Center)
}

/** Bouton principal (pilule pleine, couleur d'accent), 48 dp de haut au moins. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, shape = PillShape, contentPadding = ButtonPadding,
        modifier = modifier.focusRing(PillShape).heightIn(min = Dimens.target),
    ) { Content(text, icon) }
}

/** Bouton secondaire (contour). */
@Composable
fun GhostButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true,
    color: Color? = null,
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = PillShape, contentPadding = ButtonPadding,
        colors = if (color != null) ButtonDefaults.outlinedButtonColors(contentColor = color) else ButtonDefaults.outlinedButtonColors(),
        border = ButtonDefaults.outlinedButtonBorder(enabled).copy(brush = androidx.compose.ui.graphics.SolidColor(
            color?.copy(alpha = 0.6f) ?: AppTheme.palette.outlineStrong)),
        modifier = modifier.focusRing(PillShape).heightIn(min = Dimens.target),
    ) { Content(text, icon) }
}

/** Bouton texte (lien d'action). */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    TextButton(onClick = onClick, shape = PillShape, modifier = modifier.focusRing(PillShape).heightIn(min = Dimens.target)) {
        Content(text, icon)
    }
}

/** Bouton icône 48 dp, avec son libellé TalkBack. */
@Composable
fun AppIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color? = null) {
    IconButton(onClick = onClick, modifier = modifier.focusRing(PillShape).size(Dimens.target)) {
        Icon(icon, contentDescription = label, tint = tint ?: androidx.compose.material3.LocalContentColor.current)
    }
}
