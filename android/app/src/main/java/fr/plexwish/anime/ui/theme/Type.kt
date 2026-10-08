package fr.plexwish.anime.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.plexwish.anime.R

/**
 * Figtree (SIL OFL 1.1, android/app/licenses/OFL-Figtree.txt), embarquée : 5 graisses statiques (~200 Ko). Les
 * caractères absents (kanji, kana, Δ…) passent par la police du système. Tailles en sp (taille de texte du système).
 */
val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
    Font(R.font.figtree_extrabold, FontWeight.ExtraBold),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Figtree, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em,
)

/** Échelle du web (_tokens.scss) : display 40, h1 32, h2 22, titre 17, corps 16, petit 14, légende 13. */
val AppTypography = Typography(
    displayLarge = style(40, 46, FontWeight.ExtraBold, -0.02),
    displayMedium = style(34, 40, FontWeight.ExtraBold, -0.02),
    displaySmall = style(30, 36, FontWeight.Bold, -0.02),
    headlineLarge = style(32, 38, FontWeight.Bold, -0.01),
    headlineMedium = style(26, 32, FontWeight.Bold, -0.01),
    headlineSmall = style(22, 28, FontWeight.Bold, -0.01),
    titleLarge = style(22, 28, FontWeight.SemiBold, -0.01),
    titleMedium = style(17, 22, FontWeight.SemiBold),
    titleSmall = style(14, 19, FontWeight.SemiBold),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(13, 18, FontWeight.Normal),
    labelLarge = style(15, 20, FontWeight.SemiBold),
    labelMedium = style(13, 18, FontWeight.SemiBold),
    labelSmall = style(12, 16, FontWeight.Bold, 0.04),
)
