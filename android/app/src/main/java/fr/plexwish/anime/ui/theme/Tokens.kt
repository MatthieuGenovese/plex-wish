package fr.plexwish.anime.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Jetons de couleur, les mêmes que le web (web/src/styles/_tokens.scss, docs/DESIGN.md §5) : accent « Lagune »,
 * thème sombre et thème clair. Toutes les couleurs des écrans passent par ici (ou par le schéma Material qui en
 * découle, Theme.kt). Un changement de style = modifier ce fichier.
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val outline: Color,
    val outlineStrong: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    /** Anneau de focus (clavier, télécommande). */
    val focus: Color,
    val ok: Color,
    val warn: Color,
    val err: Color,
    val errBg: Color,
    val warnBg: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    /** Luminosité et saturation des couvertures composées (sans affiche). */
    val posterL: Float,
    val posterS: Float,
)

val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF0D0F14), surface1 = Color(0xFF151821), surface2 = Color(0xFF1C202B), surface3 = Color(0xFF262B38),
    outline = Color(0xFF2B303D), outlineStrong = Color(0xFF646D82),
    text = Color(0xFFEEF0F5), text2 = Color(0xFFB3B9C7), text3 = Color(0xFF8E95A6),
    focus = Color(0xFFFFD54A), ok = Color(0xFF5AD48F), warn = Color(0xFFF2C464), err = Color(0xFFFF7D7D),
    errBg = Color(0xFF3A1A20), warnBg = Color(0xFF352B16),
    accent = Color(0xFF3ED6C2), onAccent = Color(0xFF06221F), accentSoft = Color(0x293ED6C2),
    posterL = 0.30f, posterS = 0.38f,
)

val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFF5F6F8), surface1 = Color(0xFFFFFFFF), surface2 = Color(0xFFECEEF3), surface3 = Color(0xFFE1E4EB),
    outline = Color(0xFFD9DDE5), outlineStrong = Color(0xFF7D8596),
    text = Color(0xFF12151C), text2 = Color(0xFF4A5162), text3 = Color(0xFF636B7C),
    focus = Color(0xFF0A58CA), ok = Color(0xFF1B7A43), warn = Color(0xFF8A5A00), err = Color(0xFFC62828),
    errBg = Color(0xFFFDECEC), warnBg = Color(0xFFFDF3DC),
    accent = Color(0xFF0A7468), onAccent = Color(0xFFFFFFFF), accentSoft = Color(0x1C0A7468),
    posterL = 0.46f, posterS = 0.42f,
)

/**
 * Surfaces toujours sombres (images floutées du héros, lecteur) : on y garde l'accent clair et le focus jaune du
 * thème sombre, quel que soit le thème (comme le web).
 */
val OnImagePalette = DarkPalette

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Espacements (base 4 dp, comme le web) et tailles communes. */
object Dimens {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 24.dp
    val s6 = 32.dp
    val s7 = 48.dp
    /** Cible tactile minimale (et focus télécommande). */
    val target = 48.dp
    /** Largeur d'une affiche dans une rangée. */
    val posterWidth = 116.dp
    val gutter = 16.dp
}
