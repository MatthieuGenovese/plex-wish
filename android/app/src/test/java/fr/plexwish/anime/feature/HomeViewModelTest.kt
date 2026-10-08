package fr.plexwish.anime.feature

import fr.plexwish.anime.FakeCipher
import fr.plexwish.anime.MemoryStore
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.api.AppJson
import fr.plexwish.anime.data.api.AppTokens
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.feature.home.HomeViewModel
import fr.plexwish.anime.tokensJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Serveur injoignable au démarrage (oubli d'adb reverse, NAS éteint) : message d'erreur, jamais de plantage. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previous: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previous)
        Dispatchers.resetMain()
    }

    @Test
    fun unreachableServerShowsAnErrorInsteadOfCrashing() {
        // Port fermé : connexion refusée, comme sur le téléphone sans adb reverse.
        val closed = MockWebServer().apply { start() }
        val url = closed.url("/")
        closed.shutdown()
        val session = SessionStore(MemoryStore(), FakeCipher())
        session.startSession(url, AppJson.decodeFromString<AppTokens>(tokensJson("a", "r")))
        val vm = HomeViewModel(AnimeApi(session, OkHttpClient()), session)
        val s = runBlocking { withTimeout(10_000) { vm.state.first { !it.loading } } }
        assertTrue(s.error!!, s.error!!.contains("ne répond pas"))
        Thread.sleep(300)
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    @Test
    fun continueWatchingFollowsTheLastSavedPosition() {
        // Sortie du lecteur : « Continuer à regarder » relu quand la position est enregistrée (plus de délai au hasard).
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when (request.requestUrl!!.encodedPath) {
                    "/api/me/continue-watching" -> {
                        val pos = if (calls.incrementAndGet() == 1) 100 else 900
                        okhttp3.mockwebserver.MockResponse().setBody(
                            """[{"animeId":7,"animeTitle":"Frieren","posterUrl":null,"episodeId":5,"seasonId":10,"seasonNumber":1,"seasonLabel":"Saison 1","episodeNumber":3,
                                "positionSeconds":$pos,"durationSeconds":1440,"kind":"RESUME","updatedAt":"2026-10-05T10:00:00Z"}]""")
                    }
                    "/api/anime" -> okhttp3.mockwebserver.MockResponse().setBody("""{"total":0,"page":0,"size":20,"items":[]}""")
                    else -> okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        val session = SessionStore(MemoryStore(), FakeCipher())
        session.startSession(server.url("/"), AppJson.decodeFromString<AppTokens>(tokensJson("a", "r")))
        val bus = fr.plexwish.anime.feature.player.ProgressBus()
        val vm = HomeViewModel(AnimeApi(session, OkHttpClient()), session, bus)
        val s = runBlocking { withTimeout(10_000) { vm.state.first { !it.loading } } }
        assertEquals(null, s.error)
        assertEquals(100, s.continueWatching.single().positionSeconds)
        bus.saved(fr.plexwish.anime.feature.player.ProgressSaved(7, 5, 900, 1440))
        runBlocking { withTimeout(10_000) { vm.state.first { it.continueWatching.singleOrNull()?.positionSeconds == 900 } } }
        server.shutdown()
    }

    @Test
    fun rowsFollowTheWebHomeGenresDiscoverAndNextEpisode() {
        val paths = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val server = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    val url = request.requestUrl!!
                    paths += url.encodedPath + "?" + (url.encodedQuery ?: "")
                    fun page(total: Int, vararg ids: Int) = okhttp3.mockwebserver.MockResponse().setBody(
                        """{"total":$total,"page":0,"size":12,"items":[${ids.joinToString(",") { """{"id":$it,"title":"A$it","episodeCount":12}""" }}]}""")
                    return when {
                        url.encodedPath == "/api/me/continue-watching" -> okhttp3.mockwebserver.MockResponse().setBody(
                            """[{"animeId":7,"animeTitle":"Frieren","episodeId":5,"seasonId":10,"seasonNumber":1,"seasonLabel":"Saison 1",
                                "episodeNumber":4,"positionSeconds":0,"durationSeconds":1440,"kind":"NEXT"}]""")
                        url.encodedPath == "/api/genres" -> okhttp3.mockwebserver.MockResponse().setBody(
                            """[{"genre":"Hentai","label":"Hentai","animeCount":90},{"genre":"Comedy","label":"Comédie","animeCount":40},
                                {"genre":"Drama","label":"Drame","animeCount":2}]""")
                        url.queryParameter("genre") == "Comedy" -> page(40, 50, 51)
                        url.queryParameter("sort") == "title" -> page(300, 90, 91)
                        else -> page(300, 1, 2, 3)
                    }
                }
            }
            start()
        }
        val session = SessionStore(MemoryStore(), FakeCipher())
        session.startSession(server.url("/"), AppJson.decodeFromString<AppTokens>(tokensJson("a", "r")))
        val vm = HomeViewModel(AnimeApi(session, OkHttpClient()), session, random = kotlin.random.Random(1))
        val s = runBlocking { withTimeout(10_000) { vm.state.first { !it.loading } } }
        assertEquals(null, s.error)
        assertTrue(s.continueWatching.single().isNext)
        assertEquals(listOf("Comédie"), s.genres.map { it.genre.label }) // jamais Hentai, Drame trop petit
        assertEquals(listOf(90L, 91L), s.discover.map { it.id })
        assertEquals(300L, s.total)
        assertTrue(paths.none { it.contains("genre=Hentai") })
        assertTrue(paths.any { it.startsWith("/api/me/continue-watching?limit=13") })
        server.shutdown()
    }
}
