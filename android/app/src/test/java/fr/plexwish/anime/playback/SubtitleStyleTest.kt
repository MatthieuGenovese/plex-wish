package fr.plexwish.anime.playback

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Sous-titres sans bandeau noir : contour noir par défaut, style d'accessibilité d'Android s'il est activé. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class SubtitleStyleTest {

    @Test
    fun defaultIsWhiteOutlineWithoutBackground() {
        val s = SubtitleStyle.OUTLINE
        assertEquals(Color.WHITE, s.foregroundColor)
        assertEquals(Color.TRANSPARENT, s.backgroundColor)
        assertEquals(Color.TRANSPARENT, s.windowColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, s.edgeType)
        assertEquals(Color.BLACK, s.edgeColor)
    }

    @Test
    fun accessibilitySettingsWinWhenEnabled() {
        val user = CaptionStyleCompat(Color.YELLOW, Color.BLUE, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, null)
        assertSame(user, SubtitleStyle.choose(true) { user })
        assertSame(SubtitleStyle.OUTLINE, SubtitleStyle.choose(false) { user })
    }

    @Test
    fun deviceWithCaptionsOffGetsOutline() {
        // Robolectric : sous-titres d'accessibilité désactivés, comme sur la plupart des téléphones.
        assertSame(SubtitleStyle.OUTLINE, SubtitleStyle.forDevice(RuntimeEnvironment.getApplication()))
    }
}
