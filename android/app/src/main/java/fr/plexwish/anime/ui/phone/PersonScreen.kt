package fr.plexwish.anime.ui.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.plexwish.anime.feature.detail.ROLE_LABELS
import fr.plexwish.anime.feature.person.PersonAnime
import fr.plexwish.anime.feature.person.PersonState
import fr.plexwish.anime.feature.person.PersonViewModel
import fr.plexwish.anime.ui.components.ErrorMessage
import fr.plexwish.anime.ui.components.LinkButton
import fr.plexwish.anime.ui.components.Poster
import fr.plexwish.anime.ui.components.ScreenTitle
import fr.plexwish.anime.ui.components.SectionTitle
import fr.plexwish.anime.ui.components.Skeleton
import fr.plexwish.anime.ui.components.StateBox
import fr.plexwish.anime.ui.components.focusRing
import fr.plexwish.anime.ui.components.largeText
import fr.plexwish.anime.ui.theme.AppIcons
import fr.plexwish.anime.ui.theme.AppShapes
import fr.plexwish.anime.ui.theme.AppTheme
import fr.plexwish.anime.ui.theme.Dimens

/** Page d'un comédien : portrait rond, noms, lien AniList, puis les animés de la bibliothèque où il joue (grille). */
@Composable
fun PersonScreen(vm: PersonViewModel, onAnime: (Long) -> Unit, padding: PaddingValues) {
    val s by vm.state.collectAsStateWithLifecycle()
    PersonContent(s, onAnime, vm::load, padding)
}

@Composable
fun PersonContent(s: PersonState, onAnime: (Long) -> Unit, onRetry: () -> Unit, padding: PaddingValues) {
    val person = s.person
    val p = AppTheme.palette
    val uri = LocalUriHandler.current
    val large = largeText()
    when {
        person == null && s.loading -> Column(Modifier.padding(padding).padding(Dimens.gutter), verticalArrangement = Arrangement.spacedBy(Dimens.s4)) {
            Skeleton(Modifier.width(112.dp).height(112.dp), androidx.compose.foundation.shape.CircleShape)
            repeat(3) { Skeleton(Modifier.fillMaxWidth().height(20.dp)) }
        }
        person == null -> Box(Modifier.padding(padding)) {
            if (s.notFound) {
                StateBox(AppIcons.Person, "Comédien introuvable", message = "Ce comédien ne joue dans aucun animé disponible de la bibliothèque.")
            } else {
                ErrorMessage(s.error ?: "Erreur inconnue.", onRetry, title = "Impossible de charger la page")
            }
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (large) 150.dp else 104.dp),
            contentPadding = PaddingValues(start = Dimens.gutter, end = Dimens.gutter, bottom = Dimens.s5),
            horizontalArrangement = Arrangement.spacedBy(Dimens.s3),
            verticalArrangement = Arrangement.spacedBy(Dimens.s4),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "head") {
                Box(Modifier.padding(top = Dimens.s3)) { Head(person, s.animes.size, large) { url -> uri.openUri(url) } }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "title") { SectionTitle("Dans la bibliothèque", Modifier.padding(top = Dimens.s3)) }
            items(s.animes, key = { it.animeId }) { a -> PersonAnimeCard(a) { onAnime(a.animeId) } }
        }
    }
}

@Composable
private fun Head(person: fr.plexwish.anime.data.api.PersonDetail, count: Int, large: Boolean, open: (String) -> Unit) {
    val p = AppTheme.palette
    run {
                val text: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("COMÉDIEN DE DOUBLAGE", style = MaterialTheme.typography.labelSmall, color = p.accent)
                        ScreenTitle(person.name)
                        person.nativeName?.let { Text(it, style = MaterialTheme.typography.titleMedium.copy(localeList = LocaleList("ja"))) }
                        Text("$count animé${if (count > 1) "s" else ""} de la bibliothèque",
                            style = MaterialTheme.typography.bodyMedium, color = p.text2)
                        person.sourceUrl?.let { url -> LinkButton("Source : AniList", { open(url) }) }
                    }
                }
                if (large) {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s3)) {
                        Poster(person.name, person.imageUrl, Modifier.width(96.dp), round = true)
                        text()
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s4), verticalAlignment = Alignment.CenterVertically) {
                        Poster(person.name, person.imageUrl, Modifier.width(112.dp), round = true)
                        Box(Modifier.weight(1f)) { text() }
                    }
                }
    }
}

/** Un animé où joue le comédien : affiche, titre et année, personnages joués (noms seulement) et rôle. */
@Composable
private fun PersonAnimeCard(a: PersonAnime, onClick: () -> Unit) {
    val p = AppTheme.palette
    val roles = a.roles.joinToString(", ") { "${it.character.name} (${ROLE_LABELS[it.role] ?: it.role})" }
    Column(
        Modifier.focusRing(AppShapes.medium, grow = true).clip(AppShapes.medium).clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(a.title, a.year?.toString(), roles).joinToString(", ")
                role = Role.Button
                onClick { onClick(); true }
            }.padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Poster(a.title, a.posterUrl, Modifier.fillMaxWidth())
        Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        a.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = p.text2) }
        a.roles.forEach { r ->
            Column {
                Text(r.character.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(ROLE_LABELS[r.role] ?: r.role, style = MaterialTheme.typography.labelSmall,
                    color = if (r.role == "MAIN") p.accent else p.text3)
            }
        }
    }
}
