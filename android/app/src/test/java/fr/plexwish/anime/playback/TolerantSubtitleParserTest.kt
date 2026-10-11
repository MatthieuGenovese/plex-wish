package fr.plexwish.anime.playback

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Captain Tsubasa, piste ASS « Forcés » : une réplique de durée nulle fait lever une IllegalStateException à SsaParser
 * de Media3, ce qui faisait échouer tout l'épisode sur Android. Le lecteur tolérant l'ignore.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class TolerantSubtitleParserTest {

    private val zeroDuration = """
        [Script Info]
        ScriptType: v4.00+

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: DefaultTop,Asap,55,&H00FFFFFF,&H000000FF,&H000030&,&HAA000000,-1,0,0,0,100,100,0,0,1,3.86,0,8,0,0,85,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:00.00,0:00:00.00,DefaultTop,,0,0,0,,
    """.trimIndent().toByteArray()

    private val ssa = Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()

    private fun parse(factory: SubtitleParser.Factory, data: ByteArray): List<CuesWithTiming> {
        val out = mutableListOf<CuesWithTiming>()
        factory.create(ssa).parse(data, SubtitleParser.OutputOptions.allCues()) { out += it }
        return out
    }

    @Test
    fun media3RejectsAZeroDurationLine() {
        assertThrows(IllegalStateException::class.java) { parse(DefaultSubtitleParserFactory(), zeroDuration) }
    }

    @Test
    fun tolerantParserIgnoresItAndKeepsNormalLines() {
        assertEquals(emptyList<CuesWithTiming>(), parse(TolerantSubtitleParserFactory(), zeroDuration))
        val normal = String(zeroDuration).replace("0:00:00.00,0:00:00.00,DefaultTop,,0,0,0,,", "0:00:01.00,0:00:02.50,DefaultTop,,0,0,0,,Bonjour").toByteArray()
        val cues = parse(TolerantSubtitleParserFactory(), normal)
        assertEquals("Bonjour", cues.first().cues.single().text.toString())
        assertEquals(1_000_000L, cues.first().startTimeUs)
    }
}
