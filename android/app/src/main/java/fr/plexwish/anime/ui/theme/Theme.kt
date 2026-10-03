package fr.plexwish.anime.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Mêmes couleurs que l'interface web (thème sombre, contrastes vérifiés sur le web). */
object AppColors {
    val Background = Color(0xFF101218)
    val Surface = Color(0xFF171A22)
    val SurfaceRaised = Color(0xFF212531)
    val Border = Color(0xFF2C3140)
    val Text = Color(0xFFECEEF3)
    val TextMuted = Color(0xFFA3A9B8)
    val Accent = Color(0xFF7AA2FF)
    val OnAccent = Color(0xFF0B1020)
    val Success = Color(0xFF5FD49A)
    val Danger = Color(0xFFFF7B7B)
}

private val Colors = darkColorScheme(
    primary = AppColors.Accent,
    onPrimary = AppColors.OnAccent,
    secondary = AppColors.Accent,
    onSecondary = AppColors.OnAccent,
    background = AppColors.Background,
    onBackground = AppColors.Text,
    surface = AppColors.Surface,
    onSurface = AppColors.Text,
    surfaceVariant = AppColors.SurfaceRaised,
    onSurfaceVariant = AppColors.TextMuted,
    surfaceContainer = AppColors.Surface,
    surfaceContainerHigh = AppColors.SurfaceRaised,
    outline = AppColors.Border,
    error = AppColors.Danger,
    onError = AppColors.OnAccent,
)

@Composable
fun AnimeTheme(content: @Composable () -> Unit) {
    // Tailles de texte en sp (typographie Material) : la taille de police du système s'applique.
    MaterialTheme(colorScheme = Colors, content = content)
}
