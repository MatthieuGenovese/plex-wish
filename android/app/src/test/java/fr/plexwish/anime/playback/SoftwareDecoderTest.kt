package fr.plexwish.anime.playback

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.core.app.ApplicationProvider
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Décodeur FFmpeg (NextLib) en REPLI seulement : décodeurs du téléphone d'abord, FFmpeg quand le téléphone refuse.
 * Les bibliothèques natives ne se chargent pas sur la JVM : on vérifie l'ordre des renderers et la règle du
 * sélecteur de pistes, le décodage lui-même se vérifie sur le téléphone.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SoftwareDecoderTest {

    private fun renderers(factory: DefaultRenderersFactory): Array<Renderer> = factory.createRenderers(
        Handler(Looper.getMainLooper()), object : VideoRendererEventListener {}, object : AudioRendererEventListener {}, { }, { })

    private fun Array<Renderer>.indexOf(type: Class<*>) = indexOfFirst { type.isInstance(it) }.also { assertTrue("${type.simpleName} absent", it >= 0) }

    @Test
    fun phoneDecodersComeBeforeFfmpegInTheAppFactory() {
        val r = renderers(ExoPlaybackEngine.renderersFactory(ApplicationProvider.getApplicationContext()) as DefaultRenderersFactory)
        assertTrue(r.indexOf(MediaCodecVideoRenderer::class.java) < r.indexOf(FfmpegVideoRenderer::class.java))
        assertTrue(r.indexOf(MediaCodecAudioRenderer::class.java) < r.indexOf(FfmpegAudioRenderer::class.java))
    }

    /** Témoin : en mode PREFER (refusé), FFmpeg passerait devant ; le test ci-dessus distingue bien les deux modes. */
    @Test
    fun preferModeWouldPutFfmpegFirst() {
        val r = renderers(NextRenderersFactory(ApplicationProvider.getApplicationContext())
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
        assertTrue(r.indexOf(FfmpegVideoRenderer::class.java) < r.indexOf(MediaCodecVideoRenderer::class.java))
    }

    /** Capacités simulées : un décodeur « téléphone » qui gère le H.264 8 bits mais pas le 10 bits, puis FFmpeg. */
    private class Caps(private val name: String, private val type: Int, private val support: (Format) -> Int) : RendererCapabilities {
        override fun getName() = name
        override fun getTrackType() = type
        override fun supportsFormat(format: Format) = RendererCapabilities.create(support(format))
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
    }

    private fun chosenRenderer(format: Format, phone: Caps, ffmpeg: Caps): Int {
        val selector = DefaultTrackSelector(ApplicationProvider.getApplicationContext<android.content.Context>())
        selector.init({}, androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(ApplicationProvider.getApplicationContext()).build())
        val result = selector.selectTracks(arrayOf(phone, ffmpeg), TrackGroupArray(TrackGroup(format)), MediaSource.MediaPeriodId(Any()), Timeline.EMPTY)
        return (0..1).single { result.selections[it] != null }
    }

    private val h264 = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1920).setHeight(1080)
    private val phoneVideo = Caps("c2.qti.avc.decoder", C.TRACK_TYPE_VIDEO) {
        if (it.codecs == "avc1.6E0028") C.FORMAT_EXCEEDS_CAPABILITIES else C.FORMAT_HANDLED // profil 110 = High 10
    }
    private val ffmpegVideo = Caps("FfmpegVideoRenderer", C.TRACK_TYPE_VIDEO) { C.FORMAT_HANDLED }

    @Test
    fun h264EightBitStaysOnThePhoneDecoder() {
        assertEquals(0, chosenRenderer(h264.setCodecs("avc1.640028").build(), phoneVideo, ffmpegVideo))
    }

    @Test
    fun h264TenBitFallsBackToFfmpeg() {
        assertEquals(1, chosenRenderer(h264.setCodecs("avc1.6E0028").build(), phoneVideo, ffmpegVideo))
    }

    @Test
    fun dtsWithoutPhoneDecoderGoesToFfmpeg() {
        val dts = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS).setChannelCount(6).setSampleRate(48_000).build()
        val phone = Caps("c2.android.aac.decoder", C.TRACK_TYPE_AUDIO) { C.FORMAT_UNSUPPORTED_SUBTYPE }
        val ffmpeg = Caps("FfmpegAudioRenderer", C.TRACK_TYPE_AUDIO) { C.FORMAT_HANDLED }
        assertEquals(1, chosenRenderer(dts, phone, ffmpeg))
    }
}
