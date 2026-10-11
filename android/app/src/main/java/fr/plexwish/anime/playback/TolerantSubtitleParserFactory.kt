package fr.plexwish.anime.playback

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import fr.plexwish.anime.data.log.SafeLog

/**
 * Lecteurs de sous-titres de Media3, mais une réplique illisible ne fait plus échouer toute la lecture : elle est
 * ignorée. Cas réel (Captain Tsubasa, piste ASS « Forcés ») : une ligne de durée nulle
 * (`Dialogue: 0,0:00:00.00,0:00:00.00,…`) fait lever une IllegalStateException à SsaParser (Media3 1.5.1, et encore
 * en 1.9.0) ; comme les sous-titres sont lus pendant le téléchargement, c'était tout l'épisode qui échouait
 * (« Reconnexion… » puis erreur), alors que le navigateur le lit sans problème.
 */
@OptIn(UnstableApi::class)
class TolerantSubtitleParserFactory(
    private val inner: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory {

    override fun supportsFormat(format: Format): Boolean = inner.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int = inner.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser = Tolerant(inner.create(format), format.sampleMimeType)

    private class Tolerant(private val parser: SubtitleParser, private val mime: String?) : SubtitleParser {
        private var warned = false

        override fun parse(
            data: ByteArray, offset: Int, length: Int, outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>,
        ) {
            try {
                parser.parse(data, offset, length, outputOptions, output)
            } catch (e: RuntimeException) {
                if (!warned) {
                    warned = true
                    SafeLog.w(TAG, "Réplique de sous-titres illisible ignorée ($mime)", e)
                }
            }
        }

        override fun getCueReplacementBehavior(): Int = parser.cueReplacementBehavior

        override fun reset() = parser.reset()
    }

    private companion object {
        const val TAG = "Sous-titres"
    }
}
