package fr.plexwish.anime.feature.player

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import fr.plexwish.anime.data.api.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Progression : cohérence, rythme, pause / arrière-plan / fin / sortie, nouvel essai après un échec réseau. */
class ProgressTest : PlayerTestBase() {

    @Test
    fun incoherentPositionsAreNeverSent() {
        assertNull(ProgressReporter.sample(600_000, -1)) // durée inconnue
        assertNull(ProgressReporter.sample(600_000, 0))
        assertNull(ProgressReporter.sample(-5, 1_440_000))
        assertNull(ProgressReporter.sample(500, 1_440_000)) // moins d'une seconde
        assertNull(ProgressReporter.sample(1_500_000, 1_440_000)) // bien au-delà de la durée
        assertNull(ProgressReporter.sample(1_000, 90_000_000)) // plus de 24 h
        assertEquals(ProgressReporter.Sample(1440, 1440), ProgressReporter.sample(1_441_000, 1_440_000)) // fin de fichier
        assertEquals(ProgressReporter.Sample(600, 1440), ProgressReporter.sample(600_999, 1_440_000))
    }

    @Test
    fun reporterThrottlesDeduplicatesAndRetriesAfterAFailure() {
        var now = 0L
        val sent = CopyOnWriteArrayList<Pair<Int, Int>>()
        var down = false
        val r = ProgressReporter(
            send = { p, d -> if (down) throw ApiException(0, null, "réseau") else sent += p to d },
            scope = CoroutineScope(Dispatchers.Unconfined), clock = { now },
        )
        r.tick(10_000, 1_440_000)
        now = 5_000; r.tick(15_000, 1_440_000) // moins de 10 s : rien
        now = 10_000; r.tick(20_000, 1_440_000)
        assertEquals(listOf(10 to 1440, 20 to 1440), sent)
        r.flush(20_400, 1_440_000) // pause juste après : même seconde, déjà enregistrée
        assertEquals(2, sent.size)
        down = true
        now = 20_000; r.tick(30_000, 1_440_000) // échec réseau : la lecture continue
        down = false
        r.flush(30_000, 1_440_000) // prochaine occasion (pause) : renvoyé
        assertEquals(30 to 1440, sent.last())
        assertEquals(3, sent.size)
    }

    @Test
    fun playerSendsOnTickPauseBackgroundEndAndExit() {
        val engine = FakeEngine()
        val vm = player(engine, intervalMs = 0)
        await("chargement") { engine.loads.size == 1 }
        engine.durationMs = 1_440_000
        engine.current!!.onReady()
        engine.playing(true)
        engine.positionMs = 610_000
        await("envoi périodique") { progressPuts.isNotEmpty() }
        assertEquals("""{"positionSeconds":610,"durationSeconds":1440}""", progressPuts.last())
        engine.positionMs = 615_000
        engine.pause() // pause : envoi immédiat
        await("pause") { progressPuts.last().contains("615") }
        engine.positionMs = 620_000
        vm.onBackground()
        await("arrière-plan") { progressPuts.last().contains("620") }
        engine.positionMs = 1_440_000
        engine.current!!.onEnded()
        await("fin") { progressPuts.last() == """{"positionSeconds":1440,"durationSeconds":1440}""" }
        // Sortie : envoyé même ViewModel détruit (portée de l'app).
        val store = ViewModelStore()
        val engine2 = FakeEngine()
        ViewModelProvider.create(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = player(engine2) as T
        })[PlayerViewModel::class.java]
        await("chargement 2") { engine2.loads.size == 1 }
        engine2.durationMs = 1_440_000
        engine2.current!!.onReady()
        engine2.positionMs = 700_000
        store.clear()
        await("sortie") { progressPuts.last().contains("700") }
        assertTrue(engine2.released)
    }

    @Test
    fun nothingIsSentDuringAReconnectionOrWhenTheServerIsDown() {
        val engine = FakeEngine()
        player(engine)
        await("chargement") { engine.loads.size == 1 }
        engine.durationMs = 1_440_000
        engine.current!!.onReady()
        engine.playing(true)
        engine.positionMs = 650_000
        progressStatus = 503
        engine.pause()
        Thread.sleep(200)
        val failed = progressPuts.size
        progressStatus = 200
        engine.playing(true)
        engine.pause() // même position, mais le précédent envoi a échoué : renvoyé
        await("renvoi") { progressPuts.size > failed }
        // Pendant une reconnexion, la position n'est pas sûre : rien n'est envoyé.
        val before = progressPuts.size
        engine.error(failure(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        engine.positionMs = 0
        engine.current!!.onPlayingChanged(false)
        Thread.sleep(200)
        assertEquals(before, progressPuts.size)
    }

    @Test
    fun reporterAnnouncesOnlyWhatTheServerAccepted() {
        val saved = CopyOnWriteArrayList<ProgressReporter.Sample>()
        var down = true
        val r = ProgressReporter(
            send = { _, _ -> if (down) throw ApiException(0, null, "réseau") },
            scope = CoroutineScope(Dispatchers.Unconfined), clock = { 0L }, onSaved = { saved += it },
        )
        r.flush(600_000, 1_440_000)
        assertTrue("échec : rien d'annoncé", saved.isEmpty())
        down = false
        r.flush(610_000, 1_440_000)
        assertEquals(listOf(ProgressReporter.Sample(610, 1440)), saved)
    }

    @Test
    fun leavingThePlayerAnnouncesTheLastPositionWithItsAnime() {
        // Scénario du S24 : retour à la fiche, la barre de l'épisode doit suivre la dernière position, même si
        // l'envoi de sortie arrive après le retour à l'écran.
        val events = CopyOnWriteArrayList<ProgressSaved>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { bus.events.collect { events += it } }
        val store = ViewModelStore()
        val engine = FakeEngine()
        ViewModelProvider.create(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = player(engine) as T
        })[PlayerViewModel::class.java]
        await("chargement") { engine.loads.size == 1 }
        engine.durationMs = 1_440_000
        engine.current!!.onReady()
        engine.positionMs = 1_200_000
        store.clear() // sortie du lecteur
        await("annonce") { events.any { it.positionSeconds == 1200 } }
        assertEquals(ProgressSaved(7, 5, 1200, 1440), events.last())
        collector.cancel()
    }
}
