package fr.plexwish.anime.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.plexwish.anime.ui.theme.AppShapes
import fr.plexwish.anime.ui.theme.AppTheme
import fr.plexwish.anime.ui.theme.Dimens
import fr.plexwish.anime.ui.theme.PillShape

/** Titre d'écran (titre 1 du web), lu comme titre par TalkBack. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineLarge, modifier = modifier.semantics { heading() })
}

/** Titre de section (titre 2), lu comme titre par TalkBack. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { heading() })
}

/** Panneau (carte à bord fin, comme {@code .panel} du web). */
@Composable
fun Panel(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = AppTheme.palette
    Column(
        modifier.fillMaxWidth().border(1.dp, p.outline, AppShapes.large).background(p.surface1, AppShapes.large).padding(Dimens.s4),
        verticalArrangement = Arrangement.spacedBy(Dimens.s3),
    ) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

/** Une option d'un choix segmenté. */
data class Choice<T>(val value: T, val label: String, val icon: ImageVector? = null)

/**
 * Choix segmenté (thème, saisons) : options côte à côte à parts égales, l'option choisie pleine ; texte agrandi
 * (≥ 130 %) : les options les unes sous les autres. Groupe « une seule option » pour TalkBack, utilisable au
 * clavier et à la télécommande.
 */
@Composable
fun <T> Segmented(choices: List<Choice<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val p = AppTheme.palette
    val stacked = LocalDensity.current.fontScale >= 1.3f
    val container = modifier.fillMaxWidth().background(p.surface2, AppShapes.large).padding(4.dp).selectableGroup()
    val item: @Composable (Choice<T>, Modifier) -> Unit = { c, m ->
        val on = c.value == selected
        Row(
            m.focusRing(PillShape).heightIn(min = Dimens.target)
                .background(if (on) p.text else Color.Transparent, PillShape)
                .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(c.value) })
                .padding(horizontal = Dimens.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            val color = if (on) p.bg else p.text2
            c.icon?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(18.dp)) }
            Text(c.label, color = color, style = MaterialTheme.typography.labelLarge, maxLines = if (stacked) 2 else 1, softWrap = stacked, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 10.dp))
        }
    }
    if (stacked) {
        Column(container, verticalArrangement = Arrangement.spacedBy(4.dp)) { choices.forEach { item(it, Modifier.fillMaxWidth()) } }
    } else {
        Row(container, horizontalArrangement = Arrangement.spacedBy(4.dp)) { choices.forEach { item(it, Modifier.weight(1f)) } }
    }
}
