package fr.plexwish.anime.feature.player

import fr.plexwish.anime.ui.phone.timeLabel
import fr.plexwish.anime.ui.phone.trackName
import org.junit.Assert.assertEquals
import org.junit.Test

/** Surcouche du lecteur (P3.6) : lecture / pause, ±10 s bornés, choix des pistes, libellés. */
class OverlayControlsTest : PlayerTestBase() {

    @Test
    fun playPauseSeekAndTracksGoToTheEngine() {
        val engine = FakeEngine()
        val vm = player(engine)
        await("chargement") { engine.loads.size == 1 }
        engine.durationMs = 1_440_000
        engine.current!!.onReady()
        engine.playing(true)
        vm.togglePlay()
        assertEquals(1, engine.pauses)
        vm.togglePlay()
        assertEquals(1, engine.plays)
        engine.positionMs = 5_000
        vm.seekBy(-10_000) // jamais avant le début
        assertEquals(0L, engine.seeks.last())
        engine.positionMs = 1_435_000
        vm.seekBy(10_000) // jamais après la fin
        assertEquals(1_440_000L, engine.seeks.last())
        val fr = TrackInfo(TrackType.TEXT, "application/x-media3-cues", "text/x-ssa", "fr", group = 2, index = 0)
        engine.current!!.onTracks(listOf(TrackInfo(TrackType.VIDEO, "video/hevc", group = 0, index = 0),
            TrackInfo(TrackType.AUDIO, "audio/opus", language = "ja", selected = true, group = 1, index = 0), fr))
        assertEquals(1, vm.state.value.audioTracks.size)
        assertEquals(listOf(fr), vm.state.value.textTracks)
        vm.selectTrack(TrackType.TEXT, fr)
        vm.selectTrack(TrackType.TEXT, null) // sous-titres désactivés
        assertEquals(listOf(TrackType.TEXT to fr, TrackType.TEXT to null), engine.selections.toList())
    }

    @Test
    fun labels() {
        assertEquals("4:05", timeLabel(245_000))
        assertEquals("1:02:09", timeLabel(3_729_000))
        assertEquals("Japonais", trackName(TrackInfo(TrackType.AUDIO, "audio/aac", language = "ja"), 1))
        assertEquals("Français", trackName(TrackInfo(TrackType.TEXT, "text/x-ssa", language = "fra"), 1))
        assertEquals("Signs · Anglais", trackName(TrackInfo(TrackType.TEXT, "text/x-ssa", language = "en", label = "Signs"), 2))
        assertEquals("Piste 3", trackName(TrackInfo(TrackType.TEXT, "text/x-ssa", language = "und"), 3))
    }
}
