package fr.plexwish.anime.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import fr.plexwish.anime.ui.theme.DarkPalette
import fr.plexwish.anime.ui.theme.LightPalette
import fr.plexwish.anime.ui.theme.PaletteTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Une variante de rendu : thème et taille de texte du système. */
data class Variant(val dark: Boolean, val fontScale: Float) {
    val tag get() = (if (dark) "sombre" else "clair") + if (fontScale == 1f) "" else "-texte${(fontScale * 100).toInt()}"
}

/**
 * Écrans Compose rendus sur la JVM (Robolectric, rendu natif) : captures PNG dans build/screenshots (pour les
 * regarder, aucune comparaison automatique) et vérifications d'interface (libellés TalkBack, cibles de 48 dp).
 * Téléphone de référence : 384 × 832 dp (Galaxy S24, densité 3).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
abstract class ScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var variant by mutableStateOf(Variant(dark = true, fontScale = 1f))

    /** Monte {@code content} une fois, puis le capture dans chaque variante (thème, taille du texte). */
    fun shoot(
        name: String,
        variants: List<Variant> = ALL,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, variant.fontScale)) {
                PaletteTheme(if (variant.dark) DarkPalette else LightPalette) {
                    // Comme dans l'app (Scaffold) : fond et couleur de texte du thème.
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        variants.forEach { v ->
            variant = v
            compose.waitForIdle()
            save("$name-${v.tag}")
        }
    }

    fun save(file: String) {
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$file.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Accessibilité de base sur l'écran affiché : tout élément cliquable a un nom lu par TalkBack et mesure au moins
     * 48 × 48 dp (ou fait partie d'un élément qui les mesure).
     */
    fun assertAccessible() {
        val nodes = compose.onRoot(useUnmergedTree = false).fetchSemanticsNode().let { all(it) }
        val density = compose.density.density
        val problems = nodes.filter { it.config.getOrNull(SemanticsActions.OnClick) != null }.mapNotNull { n ->
            val label = listOfNotNull(
                n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
                n.config.getOrNull(SemanticsProperties.Text)?.joinToString(),
                n.config.getOrNull(SemanticsProperties.EditableText)?.text,
            ).joinToString().trim()
            // Zone de toucher (Material l'agrandit à 48 dp autour d'un élément plus petit) : c'est elle qui compte.
            val w = n.touchBoundsInRoot.width / density
            val h = n.touchBoundsInRoot.height / density
            when {
                label.isEmpty() -> "élément cliquable sans libellé (${n.boundsInRoot})"
                w < 47.5f || h < 47.5f -> "« $label » : ${w.toInt()} × ${h.toInt()} dp (< 48)"
                else -> null
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { all(it) }

    companion object {
        val ALL = listOf(Variant(true, 1f), Variant(false, 1f), Variant(true, 2f), Variant(false, 2f))
        val DARK_ONLY = listOf(Variant(true, 1f))
        @Suppress("unused")
        val hasClick = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)
    }
}
