package fr.plexwish.anime.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import fr.plexwish.anime.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jetons (comme le web) : contrastes WCAG des deux thèmes, préférence de thème enregistrée. */
class PaletteTest {

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test
    fun bothThemesMeetWcagContrast() {
        for (p in listOf(DarkPalette, LightPalette)) {
            val name = if (p.dark) "sombre" else "clair"
            for ((label, fg, bg, min) in listOf(
                Quad("texte / fond", p.text, p.bg, 7.0),
                Quad("texte 2 / surface 2", p.text2, p.surface2, 4.5),
                Quad("texte 3 / fond", p.text3, p.bg, 4.5),
                Quad("accent / fond", p.accent, p.bg, 4.5),
                Quad("sur accent / accent", p.onAccent, p.accent, 4.5),
                Quad("erreur / surface 1", p.err, p.surface1, 4.5),
                Quad("focus / fond", p.focus, p.bg, 3.0),
                Quad("contour fort / fond", p.outlineStrong, p.bg, 3.0),
                Quad("texte / accent doux", p.text, p.accentSoft.compositeOver(p.bg), 4.5),
            )) {
                val c = contrast(fg, bg)
                assertTrue("$name, $label : ${"%.2f".format(c)} < $min", c >= min)
            }
        }
    }

    @Test
    fun themePreferenceIsDarkByDefaultAndRemembered() {
        val store = MemoryStore()
        assertEquals(ThemeMode.DARK, ThemePrefs(store).mode.value)
        ThemePrefs(store).set(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, ThemePrefs(store).mode.value)
        store.put("theme", "n'importe quoi")
        assertEquals(ThemeMode.DARK, ThemePrefs(store).mode.value)
    }

    private data class Quad(val a: String, val b: Color, val c: Color, val d: Double)
}
