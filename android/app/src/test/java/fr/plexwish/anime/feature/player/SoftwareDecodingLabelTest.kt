package fr.plexwish.anime.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoftwareDecodingLabelTest {

    @Test
    fun phoneDecodersAreNotSignalled() {
        assertNull(softwareDecodingLabel(mapOf(TrackType.VIDEO to "c2.qti.avc.decoder", TrackType.AUDIO to "c2.android.aac.decoder")))
        assertNull(softwareDecodingLabel(emptyMap()))
    }

    @Test
    fun ffmpegIsSignalledPerTrackType() {
        assertEquals("Décodage logiciel : image", softwareDecodingLabel(mapOf(TrackType.VIDEO to "ffmpeg6.0", TrackType.AUDIO to "c2.android.opus.decoder")))
        assertEquals("Décodage logiciel : son", softwareDecodingLabel(mapOf(TrackType.VIDEO to "c2.qti.hevc.decoder", TrackType.AUDIO to "ffmpeg6.0")))
        assertEquals("Décodage logiciel : image et son", softwareDecodingLabel(mapOf(TrackType.VIDEO to "ffmpeg6.0", TrackType.AUDIO to "ffmpeg6.0")))
    }
}
