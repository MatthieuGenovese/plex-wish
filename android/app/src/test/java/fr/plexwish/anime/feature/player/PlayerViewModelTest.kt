package fr.plexwish.anime.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.PlaybackException
import fr.plexwish.anime.FakeCipher
import fr.plexwish.anime.MemoryStore
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.api.AppJson
import fr.plexwish.anime.data.api.AppTokens
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.tokensJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Lecteur : reprise, nouvelle URL signée sur 403, coupure réseau, seek pendant la pause, erreurs bloquantes. */
@OptIn(ExperimentalCoroutinesApi::class)
open class PlayerTestBase {

    val server = MockWebServer()
    lateinit var session: SessionStore
    lateinit var api: AnimeApi
    val urls = AtomicInteger()
    var urlLifetimeSeconds = 6 * 3600L
    var progressBody = """[{"episodeId":5,"positionSeconds":600,"durationSeconds":1440,"completed":false,"updatedAt":"2026-10-05T10:00:00Z"}]"""
    var streamUrlStatus = 200
    val progressPuts = java.util.concurrent.CopyOnWriteArrayList<String>()
    var progressStatus = 200

    @Before
    fun setUpServer() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/api/episodes/5" -> MockResponse().setBody(
                    """{"id":5,"animeId":7,"animeTitle":"Frieren","seasonId":10,"seasonNumber":1,"episodeNumber":3,
                       "title":"Tuer des magiciens","durationSeconds":null,"container":"mkv","fileSize":1000}""")
                "/api/me/progress" -> MockResponse().setBody(progressBody)
                "/api/episodes/5/stream-url" -> if (streamUrlStatus != 200) MockResponse().setResponseCode(streamUrlStatus) else {
                    val n = urls.incrementAndGet()
                    val exp = Instant.now().plusSeconds(urlLifetimeSeconds)
                    MockResponse().setBody("""{"url":"/api/stream/12?u=1&exp=${exp.epochSecond}&sig=SECRETSIG$n","expiresAt":"$exp","mimeType":"video/x-matroska","fileSize":1000}""")
                }
                "/api/episodes/5/progress" -> {
                    progressPuts += request.body.readUtf8()
                    MockResponse().setResponseCode(progressStatus).setBody(
                        """{"episodeId":5,"positionSeconds":1,"durationSeconds":1,"completed":false,"updatedAt":"2026-10-05T10:00:00Z"}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        session = SessionStore(MemoryStore(), FakeCipher())
        session.startSession(server.url("/"), AppJson.decodeFromString<AppTokens>(tokensJson("ACCESSTOKEN123", "REFRESHTOKEN456")))
        api = AnimeApi(session, OkHttpClient())
    }

    @After
    fun tearDownServer() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    fun await(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "délai dépassé : $what" }
            Thread.sleep(10)
        }
    }

    fun failure(code: Int, http: Int? = null, cause: String = "") = PlaybackFailure(code, "ERROR_$code", http, cause)

    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    fun player(engine: FakeEngine, saved: SavedStateHandle = SavedStateHandle(mapOf("id" to 5L)), intervalMs: Long = 10_000) =
        PlayerViewModel(api, { engine }, saved, appScope, RecoveryPolicy(networkDelaysMs = listOf(0, 0, 0)), progressIntervalMs = intervalMs)
}

class PlayerViewModelTest : PlayerTestBase() {

    @Test
    fun resumesAtTheSavedPositionWithASignedUrl() {
        val engine = FakeEngine()
        val vm = player(engine)
        await("chargement") { engine.loads.size == 1 }
        assertEquals(600_000, engine.loads[0].startMs)
        assertTrue(engine.loads[0].play)
        assertTrue(engine.loads[0].url.startsWith("http://localhost:${server.port}/api/stream/12?"))
        assertEquals("Épisode 3 · Tuer des magiciens", vm.state.value.title)
        assertEquals("Frieren · Saison 1", vm.state.value.subtitle)
    }

    @Test
    fun completedEpisodeStartsFromTheBeginning() {
        progressBody = """[{"episodeId":5,"positionSeconds":1400,"durationSeconds":1440,"completed":true,"updatedAt":"2026-10-05T10:00:00Z"}]"""
        val engine = FakeEngine()
        player(engine)
        await("chargement") { engine.loads.size == 1 }
        assertEquals(0, engine.loads[0].startMs)
    }

    @Test
    fun forbiddenAsksForANewUrlAndKeepsThePosition() {
        val engine = FakeEngine()
        val vm = player(engine)
        await("chargement") { engine.loads.size == 1 }
        engine.playing(true)
        engine.positionMs = 812_000
        engine.error(failure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403))
        await("nouvelle URL") { engine.loads.size == 2 }
        assertEquals(812_000, engine.loads[1].startMs)
        assertTrue(engine.loads[1].url.contains("sig=SECRETSIG2"))
        assertEquals(PlayerPhase.RECONNECTING, vm.state.value.phase)
        engine.playing(true)
        assertEquals(PlayerPhase.PLAYING, vm.state.value.phase)
    }

    @Test
    fun persistentForbiddenEndsWithAClearError() {
        val engine = FakeEngine()
        val vm = player(engine)
        await("chargement") { engine.loads.size == 1 }
        repeat(3) { i ->
            engine.error(failure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403))
            if (i < 2) await("essai ${i + 2}") { engine.loads.size == i + 2 }
        }
        await("erreur") { vm.state.value.phase == PlayerPhase.ERROR }
        assertTrue(vm.state.value.error!!.message.contains("refuse la lecture"))
    }

    @Test
    fun seekDuringALongPauseThenDroppedConnectionResumesAtTheSeekTarget() {
        val engine = FakeEngine()
        player(engine)
        await("chargement") { engine.loads.size == 1 }
        engine.playing(true)
        engine.positionMs = 700_000
        engine.pause() // pause de plus de 60 s : le serveur ferme la connexion
        engine.positionMs = 1_000_000 // seek pendant la pause
        engine.error(failure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        await("reprise") { engine.loads.size == 2 }
        assertEquals(1_000_000, engine.loads[1].startMs)
        assertEquals(false, engine.loads[1].play) // toujours en pause
        // URL encore valable : pas de nouvelle demande.
        assertEquals(1, urls.get())
    }

    @Test
    fun shortNetworkCutRetriesThenGivesUp() {
        val engine = FakeEngine()
        val vm = player(engine)
        await("chargement") { engine.loads.size == 1 }
        engine.playing(true)
        engine.positionMs = 300_000
        repeat(3) { i ->
            engine.error(failure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
            await("essai ${i + 1}") { engine.loads.size == i + 2 }
            assertEquals(300_000, engine.loads.last().startMs)
        }
        engine.error(failure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        await("abandon") { vm.state.value.phase == PlayerPhase.ERROR }
        assertTrue(vm.state.value.error!!.message.contains("Connexion au serveur perdue"))
        // « Réessayer » : nouvelle URL, même position.
        vm.retry()
        await("réessai") { engine.loads.size == 5 }
        assertEquals(300_000, engine.loads.last().startMs)
        assertEquals(2, urls.get())
    }

    @Test
    fun expiringUrlIsRenewedWhenPlaybackResumes() {
        urlLifetimeSeconds = 60 // expire dans moins de 2 minutes
        val engine = FakeEngine()
        player(engine).also { vm ->
            await("chargement") { engine.loads.size == 1 }
            engine.positionMs = 42_000
            vm.onResumeRequested()
        }
        await("nouvelle URL") { engine.loads.size == 2 }
        assertEquals(42_000, engine.loads[1].startMs)
        assertEquals(2, urls.get())
    }

    @Test
    fun formatErrorsAreNotRetried() {
        val engine = FakeEngine()
        val vm = player(engine)
        await("chargement") { engine.loads.size == 1 }
        engine.error(failure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertEquals(PlayerPhase.ERROR, vm.state.value.phase)
        assertEquals(1, engine.loads.size)
        vm.showDetails(vm.state.value.error)
        assertTrue(vm.state.value.details!!.details.any { it.first == "Fichier" && it.second == "MKV" })
    }

    @Test
    fun undecodableVideoStopsWithAMessage() {
        val engine = FakeEngine()
        val vm = player(engine)
        await("chargement") { engine.loads.size == 1 }
        engine.current!!.onTracks(listOf(TrackInfo(TrackType.VIDEO, "video/hevc", "hvc1.2.4.L120.B0", supported = false),
            TrackInfo(TrackType.AUDIO, "audio/mp4a-latm", supported = true)))
        assertEquals(PlayerPhase.ERROR, vm.state.value.phase)
        assertTrue(vm.state.value.error!!.message.contains("HEVC (H.265) 10 bits"))
        assertEquals(1, engine.pauses)
    }

    @Test
    fun processDeathResumesAtTheLastKnownPosition() {
        val saved = SavedStateHandle(mapOf("id" to 5L))
        val engine = FakeEngine()
        player(engine, saved)
        await("chargement") { engine.loads.size == 1 }
        engine.playing(true)
        engine.positionMs = 900_000
        engine.playing(false)
        val engine2 = FakeEngine()
        player(engine2, saved)
        await("rechargement") { engine2.loads.size == 1 }
        assertEquals(900_000, engine2.loads[0].startMs)
    }
}
