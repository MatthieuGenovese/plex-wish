package fr.plexwish.anime.feature

import androidx.lifecycle.SavedStateHandle
import fr.plexwish.anime.FakeCipher
import fr.plexwish.anime.MemoryStore
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.api.AppJson
import fr.plexwish.anime.data.api.AppTokens
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.feature.library.LibrarySort
import fr.plexwish.anime.feature.library.LibraryViewModel
import fr.plexwish.anime.tokensJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Bibliothèque : pages, recherche et tri envoyés au serveur, état restauré, images limitées à notre serveur. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val server = MockWebServer()
    private lateinit var session: SessionStore
    private lateinit var api: AnimeApi
    private val total = 130

    @Before
    fun setUp() {
        // Temps réel (la recherche attend 300 ms avant de partir).
        Dispatchers.setMain(Dispatchers.Unconfined)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val page = url.queryParameter("page")!!.toInt()
                val size = url.queryParameter("size")!!.toInt()
                val q = url.queryParameter("q")
                val count = if (q != null) 1 else total
                val items = (page * size until minOf(count, (page + 1) * size)).joinToString(",") { i ->
                    val poster = if (i == 0) "/api/posters/abc" else "https://image.tmdb.org/t/p/w500/$i.jpg"
                    """{"id":${i + 1},"title":"${q ?: "Titre"} $i","year":2020,"posterUrl":"$poster","episodeCount":12}"""
                }
                return MockResponse().setBody("""{"total":$count,"page":$page,"size":$size,"items":[$items]}""")
            }
        }
        server.start()
        session = SessionStore(MemoryStore(), FakeCipher())
        session.startSession(server.url("/"), AppJson.decodeFromString<AppTokens>(tokensJson("a", "r")))
        api = AnimeApi(session, OkHttpClient())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    private fun waitFor(vm: LibraryViewModel, predicate: (fr.plexwish.anime.feature.library.LibraryState) -> Boolean) =
        runBlocking { withTimeout(5000) { vm.state.first(predicate) } }

    @Test
    fun pagesAreAppendedUntilTheEnd() {
        val vm = LibraryViewModel(api, session, SavedStateHandle())
        waitFor(vm) { !it.loading }
        assertEquals(60, vm.state.value.items.size)
        assertEquals("http://localhost:${server.port}/api/posters/abc", vm.state.value.items[0].posterUrl)
        assertNull("jamais d'image TMDB / AniList", vm.state.value.items[1].posterUrl)
        vm.loadMore(); waitFor(vm) { it.items.size == 120 && !it.loadingMore }
        vm.loadMore(); waitFor(vm) { it.items.size == 130 && !it.loadingMore }
        assertTrue(vm.state.value.endReached)
        val requests = server.requestCount
        vm.loadMore()
        assertEquals(requests, server.requestCount) // fin atteinte : plus de requête
    }

    @Test
    fun searchAndSortGoToTheServerAndSurviveRestoration() {
        val saved = SavedStateHandle()
        val vm = LibraryViewModel(api, session, saved)
        waitFor(vm) { !it.loading }
        vm.onSort(LibrarySort.RECENT)
        waitFor(vm) { !it.loading }
        vm.onQuery("Frieren")
        waitFor(vm) { it.items.size == 1 }
        val last = generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }.last().requestUrl!!
        assertEquals("recent", last.queryParameter("sort"))
        assertEquals("Frieren", last.queryParameter("q"))
        // Processus recréé : recherche et tri restaurés.
        val restored = LibraryViewModel(api, session, saved)
        assertEquals("Frieren", restored.state.value.query)
        assertEquals(LibrarySort.RECENT, restored.state.value.sort)
    }
}
