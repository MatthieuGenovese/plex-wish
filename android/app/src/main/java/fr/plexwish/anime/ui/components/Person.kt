package fr.plexwish.anime.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.plexwish.anime.data.api.CastEntry
import fr.plexwish.anime.feature.detail.ROLE_LABELS
import fr.plexwish.anime.ui.theme.AppShapes
import fr.plexwish.anime.ui.theme.AppTheme

/**
 * Comédien dans la distribution d'une fiche : portrait rond (initiales sans photo, jamais d'image de personnage),
 * nom, personnage joué, rôle. Vers la page du comédien ; un seul élément pour TalkBack.
 */
@Composable
fun PersonAvatar(e: CastEntry, onPerson: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = AppTheme.palette
    val person = e.person
    val roleLabel = ROLE_LABELS[e.role] ?: e.role
    val label = if (person != null) "${person.name}, voix de ${e.character.name}, $roleLabel" else "Voix non renseignée, ${e.character.name}, $roleLabel"
    Column(
        modifier.width(if (largeText()) 132.dp else 104.dp).focusRing(AppShapes.medium, grow = true).clip(AppShapes.medium)
            .then(if (person != null) Modifier.clickable(role = Role.Button) { onPerson(person.id) } else Modifier)
            .clearAndSetSemantics {
                contentDescription = label
                if (person != null) {
                    role = Role.Button
                    onClick("Ouvrir la page du comédien") { onPerson(person.id); true }
                }
            }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Poster(person?.name ?: e.character.name, person?.imageUrl, Modifier.width(84.dp), round = true)
        Text(person?.name ?: "Voix non renseignée", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
            color = if (person != null) p.text else p.text2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        Text(e.character.name, style = MaterialTheme.typography.bodySmall, color = p.text2, textAlign = TextAlign.Center, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        Text(roleLabel, style = MaterialTheme.typography.labelSmall, color = if (e.role == "MAIN") p.accent else p.text3)
    }
}
