package fr.plexwish.anime.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

/** Préférence de thème, enregistrée sur l'appareil (ThemePrefs). Sombre par défaut, comme le web. */
enum class ThemeMode(val label: String) { SYSTEM("Système"), DARK("Sombre"), LIGHT("Clair") }

/** Schéma Material 3 construit depuis les jetons (DESIGN §5.6). */
fun colorSchemeOf(p: Palette): ColorScheme {
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.accent, onPrimary = p.onAccent,
        primaryContainer = p.accentSoft, onPrimaryContainer = p.text,
        secondary = p.accent, onSecondary = p.onAccent,
        secondaryContainer = p.accentSoft, onSecondaryContainer = p.text,
        tertiary = p.accent, onTertiary = p.onAccent,
        background = p.bg, onBackground = p.text,
        surface = p.bg, onSurface = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.text2,
        surfaceDim = p.bg, surfaceBright = p.surface3,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.surface1, surfaceContainer = p.surface1,
        surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface3,
        inverseSurface = p.text, inverseOnSurface = p.bg, inversePrimary = p.accent,
        outline = p.outlineStrong, outlineVariant = p.outline,
        error = p.err, onError = if (p.dark) p.onAccent else p.surface1, errorContainer = p.errBg, onErrorContainer = p.text,
        scrim = androidx.compose.ui.graphics.Color.Black,
    )
}

/** Formes : petites 6 dp, moyennes 10 dp, grandes 16 dp (boutons : pilule, CircleShape). */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

val PillShape = CircleShape

@Composable
fun AnimeTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    PaletteTheme(if (dark) DarkPalette else LightPalette, content)
}

/** Applique une palette (sert aussi aux surfaces toujours sombres : héros, lecteur). */
@Composable
fun PaletteTheme(palette: Palette, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides palette) {
        // Tailles de texte en sp : la taille de police du système s'applique (testée à 200 %).
        MaterialTheme(colorScheme = colorSchemeOf(palette), typography = AppTypography, shapes = AppShapes, content = content)
    }
}

/** Accès court aux jetons : {@code AppTheme.palette.accent}. */
object AppTheme {
    val palette: Palette
        @Composable @ReadOnlyComposable get() = LocalPalette.current
}
