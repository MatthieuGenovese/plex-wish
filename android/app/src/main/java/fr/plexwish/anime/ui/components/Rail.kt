package fr.plexwish.anime.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import fr.plexwish.anime.ui.theme.AppIcons
import fr.plexwish.anime.ui.theme.Dimens

/**
 * Rangée horizontale (accueil, distribution) : titre, « Tout voir » éventuel, cartes qui défilent jusqu'au bord de
 * l'écran. À la télécommande, revenir sur la rangée redonne le focus à la dernière carte visitée (focusRestorer).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Rail(
    title: String,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    moreLabel: String = "Tout voir",
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.s2)) {
        Row(Modifier.fillMaxWidth().padding(start = Dimens.gutter, end = Dimens.s2), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(title, Modifier.weight(1f))
            if (onMore != null) LinkButton(moreLabel, onMore, icon = null, trailing = AppIcons.ChevronRight)
        }
        LazyRow(
            Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(horizontal = Dimens.gutter, vertical = Dimens.s1),
            horizontalArrangement = Arrangement.spacedBy(Dimens.s3),
            content = content,
        )
    }
}
