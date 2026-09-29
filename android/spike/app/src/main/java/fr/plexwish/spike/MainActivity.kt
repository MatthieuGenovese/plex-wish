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

/**
 * Spike phase 0 (jetable) : une URL, un lecteur plein écran.
 * Retour = arrêt de la lecture et retour au formulaire.
 */
@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private lateinit var form: View
    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var playerView: PlayerView
    private lateinit var info: TextView
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

        urlInput.setText(prefs.getString(KEY_URL, ""))
        findViewById<Button>(R.id.play).setOnClickListener {
            play(urlInput.text.toString().trim())
        }
        // Les infos de pistes s'affichent en même temps que les contrôles du lecteur.
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> info.visibility = visibility }
        )
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

    private fun stopPlayback() {
        player?.release()
        player = null
        playerView.player = null
        playerView.visibility = View.GONE
        info.visibility = View.GONE
        form.visibility = View.VISIBLE
        enterFullscreen(false)
    }

    /** Une ligne par piste : type, codec, et si le téléphone sait la décoder. */
    private fun describe(tracks: Tracks): String = buildString {
        for (group in tracks.groups) {
            val type = when (group.type) {
                C.TRACK_TYPE_VIDEO -> "VIDÉO"
                C.TRACK_TYPE_AUDIO -> "AUDIO"
                C.TRACK_TYPE_TEXT -> "SOUS-TITRES"
                else -> "AUTRE"
            }
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                val supported = if (group.isTrackSupported(i)) "décodable" else "NON SUPPORTÉE"
                val selected = if (group.isTrackSelected(i)) " ▶" else ""
                appendLine("$type ${f.sampleMimeType ?: "?"} ${f.codecs ?: ""} ${details(f)} — $supported$selected")
            }
        }
    }.trim()

    private fun details(f: Format): String = buildList {
        if (f.width != Format.NO_VALUE) add("${f.width}x${f.height}")
        if (f.channelCount != Format.NO_VALUE) add("${f.channelCount} canaux")
        f.language?.let { add(it) }
    }.joinToString(" ")

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
