package fr.plexwish.spike

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import java.util.Locale

/**
 * Spike phase 0 (jetable) : une URL, un lecteur plein écran, le choix des pistes audio / sous-titres
 * et le détail des pistes détectées. Retour = arrêt de la lecture et retour au formulaire.
 */
@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private lateinit var form: View
    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var playerView: PlayerView
    private lateinit var info: TextView
    private lateinit var overlay: View
    private lateinit var infoScroll: View
    private var player: ExoPlayer? = null

    private val prefs by lazy { getSharedPreferences("spike", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        form = findViewById(R.id.form)
        urlInput = findViewById(R.id.url)
        status = findViewById(R.id.status)
        playerView = findViewById(R.id.player_view)
        info = findViewById(R.id.info)
        overlay = findViewById(R.id.overlay)
        infoScroll = findViewById(R.id.info_scroll)

        urlInput.setText(prefs.getString(KEY_URL, ""))
        findViewById<Button>(R.id.play).setOnClickListener {
            play(urlInput.text.toString().trim())
        }
        // Boutons et détail des pistes s'affichent en même temps que les contrôles du lecteur.
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> overlay.visibility = visibility }
        )
        findViewById<Button>(R.id.audio_tracks).setOnClickListener { chooseTrack(C.TRACK_TYPE_AUDIO, "Piste audio") }
        findViewById<Button>(R.id.text_tracks).setOnClickListener { chooseTrack(C.TRACK_TYPE_TEXT, "Sous-titres") }
        findViewById<Button>(R.id.toggle_info).setOnClickListener {
            infoScroll.visibility = if (infoScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    private fun play(url: String) {
        if (url.isEmpty()) return
        prefs.edit().putString(KEY_URL, url).apply()
        status.text = ""
        info.text = ""

        val p = ExoPlayer.Builder(this).build()
        player = p
        playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                info.text = describe(tracks)
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause?.let { "\n${it.javaClass.simpleName} : ${it.message}" } ?: ""
                val tracks = info.text.let { if (it.isNullOrEmpty()) "" else "\n\nPistes :\n$it" }
                stopPlayback()
                status.text = "Erreur ${error.errorCodeName}$cause$tracks"
            }
        })
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true

        form.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        enterFullscreen(true)
    }

    /**
     * Dialogue Media3 de choix de piste. Pour les sous-titres, « Désactivé » est proposé.
     * Media3 n'active pas de sous-titres par défaut (sauf piste « forcée ») : il faut en choisir un ici.
     */
    private fun chooseTrack(trackType: Int, title: String) {
        val p = player ?: return
        if (p.currentTracks.groups.none { it.type == trackType }) {
            info.text = "Aucune piste « $title » dans ce fichier.\n\n" + describe(p.currentTracks)
            infoScroll.visibility = View.VISIBLE
            return
        }
        TrackSelectionDialogBuilder(this, title, p, trackType)
            .setShowDisableOption(trackType == C.TRACK_TYPE_TEXT)
            .setAllowAdaptiveSelections(false)
            .build()
            .show()
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        playerView.player = null
        playerView.visibility = View.GONE
        overlay.visibility = View.GONE
        form.visibility = View.VISIBLE
        enterFullscreen(false)
    }

    /**
     * Toutes les pistes détectées, groupées par type : langue, format, codec, détails,
     * drapeaux du fichier (défaut / forcé), et si le téléphone sait la décoder. ▶ = en cours.
     */
    private fun describe(tracks: Tracks): String = buildString {
        for ((type, label) in listOf(
            C.TRACK_TYPE_VIDEO to "VIDÉO",
            C.TRACK_TYPE_AUDIO to "AUDIO",
            C.TRACK_TYPE_TEXT to "SOUS-TITRES",
        )) {
            val groups = tracks.groups.filter { it.type == type }
            appendLine("== $label (${groups.sumOf { it.length }})")
            var n = 0
            for (group in groups) {
                for (i in 0 until group.length) {
                    n++
                    val f = group.getTrackFormat(i)
                    val selected = if (group.isTrackSelected(i)) "▶ " else "  "
                    val supported = if (group.isTrackSupported(i)) "décodable" else "NON SUPPORTÉE"
                    appendLine("$selected#$n ${language(f)} · ${formatName(f.sampleMimeType)} · $supported")
                    appendLine("     ${f.sampleMimeType ?: "?"}${f.codecs?.let { " ($it)" } ?: ""}${details(f)}")
                }
            }
            if (n == 0) appendLine("  (aucune)")
        }
        val others = tracks.groups.count { it.type !in setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT) }
        if (others > 0) appendLine("== AUTRES : $others (polices ASS intégrées, métadonnées…)")
    }.trim()

    /** "fr" → "français (fr)", avec le libellé du fichier s'il y en a un. */
    private fun language(f: Format): String {
        val code = f.language
        val name = when {
            code == null || code == C.LANGUAGE_UNDETERMINED -> "langue inconnue"
            else -> "${Locale.forLanguageTag(code).getDisplayLanguage(Locale.FRENCH).ifEmpty { code }} ($code)"
        }
        return f.label?.let { "$name « $it »" } ?: name
    }

    /** Nom lisible du format, et nature des sous-titres (texte ou image). */
    private fun formatName(mime: String?): String = when (mime) {
        "text/x-ssa" -> "ASS/SSA (texte stylé : styles avancés et polices ignorés)"
        "application/x-subrip" -> "SRT (texte)"
        "text/vtt" -> "WebVTT (texte)"
        "application/ttml+xml" -> "TTML (texte)"
        "application/vobsub" -> "VobSub (images, DVD)"
        "application/pgs" -> "PGS (images, Blu-ray)"
        "application/dvbsubs" -> "DVB (images)"
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "AC3 (Dolby Digital)"
        "audio/eac3" -> "E-AC3 (Dolby Digital Plus)"
        "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
        "audio/true-hd" -> "TrueHD"
        "audio/flac" -> "FLAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/mpeg" -> "MP3"
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC (H.265)"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/mp4v-es" -> "MPEG-4 Part 2"
        null -> "format inconnu"
        else -> mime
    }

    private fun details(f: Format): String = buildList {
        if (f.width != Format.NO_VALUE) add("${f.width}x${f.height}")
        if (f.frameRate != Format.NO_VALUE.toFloat()) add("%.3g im/s".format(f.frameRate))
        if (f.channelCount != Format.NO_VALUE) add("${f.channelCount} canaux")
        if (f.sampleRate != Format.NO_VALUE) add("${f.sampleRate} Hz")
        if (f.bitrate != Format.NO_VALUE) add("${f.bitrate / 1000} kb/s")
        if (f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0) add("défaut")
        if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) add("forcé")
    }.let { if (it.isEmpty()) "" else it.joinToString(prefix = " · ", separator = " · ") }

    @Suppress("DEPRECATION")
    private fun enterFullscreen(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (enabled) {
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (enabled) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    @Deprecated("API Activity simple, suffisant pour le spike")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (player != null) stopPlayback() else super.onBackPressed()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private companion object {
        const val KEY_URL = "url"
    }
}
