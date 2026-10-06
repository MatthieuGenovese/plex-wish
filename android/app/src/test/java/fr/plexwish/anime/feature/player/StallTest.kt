package fr.plexwish.anime.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Lecture bloquée en mise en tampon (AVI Air Gear : Xvid + MP3, erreur Media3 1004 « stuck buffering ») :
 * message de remux, « Réessayer » et « Lire sans le son », détails (durée de mise en tampon, pistes actives).
 */
class StallTest : PlayerTestBase() {

    private val xvid = TrackInfo(TrackType.VIDEO, "video/mp4v-es", supported = true, selected = true)
    private val mp3 = TrackInfo(TrackType.AUDIO, "audio/mpeg", supported = true, selected = true)

    @Test
    fun detectorIgnoresSlowButProgressingLoads() {
        val d = StallDetector(windowMs = 20_000, minProgressMs = 2_000)
        assertFalse(d.update(0, true, 0))
        assertFalse(d.update(10_000, true, 1_000))
        assertTrue(d.update(20_000, true, 1_500)) // 20 s, moins de 2 s chargées : bloqué
        assertEquals(20_000, d.bufferingMs)
        d.reset()
        // Connexion lente : la position chargée avance de 2 s toutes les 15 s → jamais « bloqué ».
        var buffered = 0L
        for (t in 0..120_000L step 15_000) {
            assertFalse("à $t ms", d.update(t, true, buffered))
            buffered += 2_000
        }
        assertFalse(d.update(130_000, false, buffered)) // lecture repartie
        assertEquals(0, d.bufferingMs)
    }

    @Test
    fun media3StuckBufferingOffersRetryAndPlayWithoutSound() {
        val engine = FakeEngine()
        val vm = player(engine)
        await("chargement") { engine.loads.size == 1 }
        engine.current!!.onTracks(listOf(xvid, mp3))
        engine.bufferedPositionMs = 31
        engine.error(PlaybackFailure(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, "ERROR_CODE_FAILED_RUNTIME_CHECK", null,
            "IllegalStateException: Playback stuck buffering and not loading"))
        val s = vm.state.value
        assertEquals(PlayerPhase.ERROR, s.phase)
        assertTrue(s.canPlayWithoutSound)
        assertEquals(1, engine.loads.size) // pas de nouvel essai automatique
        assertTrue(s.error!!.details.any { it.first == "Pistes actives" && it.second.contains("MPEG-4 Part 2") && it.second.contains("MP3") })
        assertTrue(s.error!!.details.any { it.first == "Mise en tampon" })
        // AVI ? Le test utilise un MKV : message général ; le cas AVI est vérifié plus bas.
        assertTrue(s.error!!.message.contains("remux"))

        vm.playWithoutSound()
        await("rechargement sans le son") { engine.loads.size == 2 }
        assertFalse(engine.audioOn)
        assertEquals(600_000, engine.loads[1].startMs)
        assertTrue(engine.loads[1].play)
        assertTrue(vm.state.value.soundOff)
        assertFalse(vm.state.value.canPlayWithoutSound)
    }

    @Test
    fun aviMessageExplainsTheRemux() {
        val d = Diagnostics.failure(PlaybackFailure(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, "X", null,
            "IllegalStateException: Playback stuck buffering and not loading"), listOf(xvid, mp3), "avi")
        assertEquals(FailureKind.STALLED, Diagnostics.kind(PlaybackFailure(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, "X", null,
            "IllegalStateException: Playback stuck buffering and not loading")))
        assertTrue(d.message.contains("AVI") && d.message.contains("phase 9") && d.message.contains("Lire sans le son"))
        // Un autre 1004 reste une erreur inconnue.
        assertEquals(FailureKind.UNKNOWN, Diagnostics.kind(PlaybackFailure(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, "X", null, "autre")))
    }

    @Test
    fun watchdogStopsAPlaybackThatNeverStarts() {
        val now = AtomicLong(0)
        val engine = FakeEngine()
        val vm = PlayerViewModel(api, { engine }, SavedStateHandle(mapOf("id" to 5L)), appScope,
            RecoveryPolicy(networkDelaysMs = listOf(0)), clock = { now.get() })
        await("chargement") { engine.loads.size == 1 }
        engine.current!!.onTracks(listOf(xvid, mp3))
        engine.isBuffering = true
        engine.bufferedPositionMs = 20
        Thread.sleep(1_200) // un passage de la boucle : début de la mise en tampon
        now.set(25_000)
        await("blocage détecté") { vm.state.value.phase == PlayerPhase.ERROR }
        val s = vm.state.value
        assertTrue(s.canPlayWithoutSound)
        assertTrue(engine.pauses >= 1)
        assertTrue(s.error!!.details.any { it.first == "Mise en tampon" && it.second.startsWith("25 s") })
        assertTrue(s.error!!.details.any { it.first == "Position" && it.second.contains("chargé jusqu'à 0 s") })
    }
}
