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
    override var isBuffering: Boolean = false
    override var bufferedPositionMs: Long = 0
    var audioOn = true
    var released = false

    override fun setAudioEnabled(enabled: Boolean) {
        audioOn = enabled
    }
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

    var plays = 0
    val seeks = CopyOnWriteArrayList<Long>()
    val selections = CopyOnWriteArrayList<Pair<TrackType, TrackInfo?>>()

    override fun play() {
        plays++
        playWhenReady = true
        current?.onResumeRequested()
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        this.positionMs = positionMs
    }

    override fun selectTrack(type: TrackType, track: TrackInfo?) {
        selections += type to track
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
