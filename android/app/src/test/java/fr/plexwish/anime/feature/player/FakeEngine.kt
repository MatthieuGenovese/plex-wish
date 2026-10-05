package fr.plexwish.anime.feature.player

import java.util.concurrent.CopyOnWriteArrayList

/** Faux moteur : enregistre les chargements, et laisse le test simuler lecture, pause, seek et erreurs. */
class FakeEngine : PlaybackEngine {
    data class Load(val url: String, val startMs: Long, val play: Boolean)

    val loads = CopyOnWriteArrayList<Load>()
    @Volatile
    var current: PlaybackEngine.Listener? = null
    override var positionMs: Long = 0
    override var durationMs: Long = -1
    override var isPlaying: Boolean = false
    override var playWhenReady: Boolean = false
    var released = false
    var pauses = 0

    override fun setListener(listener: PlaybackEngine.Listener?) {
        current = listener
    }

    override fun load(url: String, startMs: Long, playWhenReady: Boolean) {
        loads += Load(url, startMs, playWhenReady)
        positionMs = startMs
        this.playWhenReady = playWhenReady
    }

    override fun pause() {
        pauses++
        playWhenReady = false
        if (isPlaying) playing(false)
    }

    override fun release() {
        released = true
    }

    fun playing(p: Boolean) {
        isPlaying = p
        if (p) playWhenReady = true
        current?.onPlayingChanged(p)
    }

    fun error(f: PlaybackFailure) {
        isPlaying = false
        current?.onError(f)
    }
}
