package fr.plexwish.anime.feature.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messages distincts pour les cas réels à tester : AVI, OGM, MKV HEVC 10 bits + ASS, MP4 H.264 + ASS, réseau, 403. */
class DiagnosticsTest {

    private fun failure(code: Int, http: Int? = null, mime: String? = null, codecs: String? = null) =
        PlaybackFailure(code, "ERROR_$code", http, "cause", mime, codecs)

    private val video = TrackInfo(TrackType.VIDEO, "video/avc", "avc1.64001F", supported = true, selected = true)
    private val aac = TrackInfo(TrackType.AUDIO, "audio/mp4a-latm", "mp4a.40.2", "ja", supported = true, selected = true)

    @Test
    fun ogmContainerIsExplained() {
        val d = Diagnostics.failure(failure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED), emptyList(), "ogm")
        assertTrue(d.message, d.message.contains("OGM"))
        assertTrue(d.message.contains("remux"))
        assertEquals(FailureKind.CONTAINER, Diagnostics.kind(failure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)))
    }

    @Test
    fun aviWithUnsupportedCodecIsExplained() {
        val d = Diagnostics.failure(failure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED), emptyList(), "avi")
        assertTrue(d.message.contains("AVI"))
    }

    @Test
    fun hevc10BitDecoderFailureNamesTheCodec() {
        val f = failure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, mime = "video/hevc", codecs = "hvc1.2.4.L120.B0")
        assertEquals(FailureKind.VIDEO_CODEC, Diagnostics.kind(f))
        val d = Diagnostics.failure(f, emptyList(), "mkv")
        assertTrue(d.message, d.message.contains("HEVC (H.265) 10 bits"))
        // Sans décodeur du tout : ExoPlayer jouerait le son sur écran noir → traité comme une erreur.
        val tracks = listOf(TrackInfo(TrackType.VIDEO, "video/hevc", "hev1.2.4.L120.90", supported = false), aac)
        assertTrue(Diagnostics.undecodableVideo(tracks, "mkv")!!.message.contains("HEVC (H.265) 10 bits"))
        assertNull(Diagnostics.undecodableVideo(listOf(video, aac), "mkv"))
    }

    @Test
    fun subtitlesNeverRaiseAWarningWhateverTheContainer() {
        // MKV avec ASS, MP4 sans piste de texte, AVI / OGM convertis (sous-titres parfois incrustés dans l'image),
        // sous-titres en images : aucun message, la lecture se fait sans bandeau.
        val ass = TrackInfo(TrackType.TEXT, "application/x-media3-cues", "text/x-ssa", "fr", supported = true, selected = true)
        val vobsub = TrackInfo(TrackType.TEXT, "application/x-media3-cues", "application/vobsub", "fr", supported = true)
        val pgsUnreadable = TrackInfo(TrackType.TEXT, "application/pgs", null, "fr", supported = false)
        assertTrue(Diagnostics.warnings(listOf(video, aac, ass), "mkv").isEmpty())
        assertTrue(Diagnostics.warnings(listOf(video, aac), "mp4").isEmpty())
        assertTrue(Diagnostics.warnings(listOf(video, aac), "mkv").isEmpty()) // copie remuxée d'un AVI ou d'un OGM
        assertTrue(Diagnostics.warnings(listOf(video, aac, vobsub), "mkv").isEmpty())
        assertTrue(Diagnostics.warnings(listOf(video, aac, pgsUnreadable), "mkv").isEmpty())
        val xvid = listOf(TrackInfo(TrackType.VIDEO, "video/mp4v-es", supported = true), TrackInfo(TrackType.AUDIO, "audio/mpeg", supported = true))
        assertTrue(Diagnostics.warnings(xvid, "avi").isEmpty())
        assertTrue(Diagnostics.warnings(xvid, "ogm").isEmpty())
    }

    @Test
    fun missingOrUnsupportedAudioIsStillReported() {
        // H.264 + HE-AAC non reconnu : pas de son (seul avertissement, rien sur les sous-titres).
        val w2 = Diagnostics.warnings(listOf(video), "avi")
        assertEquals(listOf(WarningKind.NO_AUDIO), w2.map { it.first })
        assertTrue(w2[0].second.message.contains("HE-AAC"))
        val vobsub = TrackInfo(TrackType.TEXT, "application/x-media3-cues", "application/vobsub", "fr", supported = true)
        val dts = TrackInfo(TrackType.AUDIO, "audio/vnd.dts", supported = false)
        val w = Diagnostics.warnings(listOf(video, dts, vobsub), "mkv")
        assertEquals(WarningKind.AUDIO_UNSUPPORTED, w[0].first)
        assertTrue(w[0].second.message.contains("DTS"))
    }

    @Test
    fun networkForbiddenAndMissingAreDistinct() {
        assertEquals(FailureKind.NETWORK, Diagnostics.kind(failure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        assertEquals(FailureKind.NETWORK, Diagnostics.kind(failure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)))
        assertEquals(FailureKind.FORBIDDEN, Diagnostics.kind(failure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403)))
        assertEquals(FailureKind.NOT_FOUND, Diagnostics.kind(failure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404)))
        assertEquals(FailureKind.SERVER, Diagnostics.kind(failure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 502)))
        assertEquals(FailureKind.AUDIO_CODEC, Diagnostics.kind(failure(PlaybackException.ERROR_CODE_DECODING_FAILED, mime = "audio/eac3")))
        val d = Diagnostics.failure(failure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403), emptyList(), "mkv")
        assertTrue(d.details.contains("Réponse du serveur" to "HTTP 403"))
    }

    @Test
    fun codecNames() {
        assertEquals("H.264 10 bits", Diagnostics.codecName("video/avc", "avc1.6E0028"))
        assertEquals("HEVC (H.265)", Diagnostics.codecName("video/hevc", "hvc1.1.6.L93.B0"))
        assertEquals("HE-AAC", Diagnostics.codecName("audio/mp4a-latm", "mp4a.40.5"))
        assertEquals("ASS/SSA", Diagnostics.codecName("application/x-media3-cues", "text/x-ssa"))
        assertEquals("PGS (images)", Diagnostics.codecName("application/pgs", null))
    }
}
