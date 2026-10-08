package fr.plexwish.anime.feature.player

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Épisode converti pour Android sur le serveur : « Préparation de l'épisode… », puis lecture ; échecs clairs. */
class PreparingTest : PlayerTestBase() {

    @Test
    fun preparingThenPlaysAtTheSavedPosition() {
        preparing.set(3)
        val engine = FakeEngine()
        val vm = player(engine)
        val seen = CopyOnWriteArrayList<PlayerState>()
        Thread { repeat(400) { seen += vm.state.value; Thread.sleep(5) } }.start()
        await("lecture après préparation") { engine.loads.size == 1 }
        assertEquals(600_000, engine.loads[0].startMs) // reprise à la position enregistrée
        assertEquals(4, urls.get() + 3) // 3 réponses 202, puis le lien
        val prep = seen.mapNotNull { it.preparing }
        assertTrue("écran de préparation affiché", prep.isNotEmpty())
        assertTrue(prep.any { it.position == 2 && it.estimatedSeconds == 160L })
        assertTrue(prep.any { it.position == 0 && it.progress == 0.5 })
        assertTrue(seen.any { it.phase == PlayerPhase.PREPARING })
        engine.current!!.onReady()
        assertEquals(PlayerPhase.PLAYING, vm.state.value.phase)
        assertEquals(null, vm.state.value.preparing)
    }

    @Test
    fun leavingThePlayerCancelsThePreparationRequests() {
        preparing.set(1000)
        val store = ViewModelStore()
        val engine = FakeEngine()
        ViewModelProvider.create(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = player(engine) as T
        })[PlayerViewModel::class.java]
        await("préparation") { server.requestCount >= 5 }
        store.clear() // « Annuler » / retour
        Thread.sleep(150)
        val after = server.requestCount
        Thread.sleep(300)
        assertEquals(after, server.requestCount)
        assertEquals(0, engine.loads.size)
    }

    @Test
    fun conversionFailureAndFullCacheAreClearMessages() {
        streamUrlStatus = 409
        streamErrorBody = """{"status":409,"error":"REMUX_FAILED","message":"Ce fichier n'a pas pu être converti pour Android (genpts : code 234). L'administrateur peut relancer la conversion."}"""
        val vm = player(FakeEngine())
        await("échec") { vm.state.value.phase == PlayerPhase.ERROR }
        assertTrue(vm.state.value.error!!.message.contains("n'a pas pu être converti"))
        assertTrue(vm.state.value.error!!.details.contains("Erreur" to "REMUX_FAILED"))

        streamUrlStatus = 503
        streamErrorBody = """{"status":503,"error":"REMUX_CACHE_FULL","message":"Le serveur n'a plus de place pour préparer cet épisode. Réessayez dans quelques minutes."}"""
        val engine = FakeEngine()
        val vm2 = player(engine)
        await("cache plein") { vm2.state.value.phase == PlayerPhase.ERROR }
        assertTrue(vm2.state.value.error!!.message.contains("plus de place"))
        // « Réessayer » une fois la place revenue : lecture.
        streamUrlStatus = 200
        vm2.retry()
        await("lecture") { engine.loads.size == 1 }
    }

    @Test
    fun copyPurgedDuringPlaybackIsPreparedAgain() {
        val engine = FakeEngine()
        val vm = player(engine)
        await("chargement") { engine.loads.size == 1 }
        engine.playing(true)
        engine.positionMs = 900_000
        preparing.set(1)
        engine.error(failure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404))
        await("rechargement") { engine.loads.size == 2 }
        assertEquals(900_000, engine.loads[1].startMs)
        engine.current!!.onReady()
        assertEquals(PlayerPhase.PLAYING, vm.state.value.phase)
    }
}
