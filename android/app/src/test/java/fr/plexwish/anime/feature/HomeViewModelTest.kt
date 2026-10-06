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
}
