package fr.plexwish.anime.data

import fr.plexwish.anime.FakeCipher
import fr.plexwish.anime.MemoryStore
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.api.ApiException
import fr.plexwish.anime.data.api.AppJson
import fr.plexwish.anime.data.api.AppTokens
import fr.plexwish.anime.data.auth.AuthInterceptor
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.data.auth.TokenAuthenticator
import fr.plexwish.anime.data.auth.TokenRefresher
import fr.plexwish.anime.tokensJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Jetons contre une fausse API : 401 → un seul refresh à la fois, rejeu des requêtes en attente, déconnexion propre
 * si le refresh est refusé, session gardée si le réseau manque, jeton jamais envoyé à un autre hôte.
 */
class TokenRefreshTest {

    private val server = MockWebServer()
    private val store = MemoryStore()
    private lateinit var session: SessionStore
    private lateinit var api: AnimeApi
    private val refreshes = AtomicInteger()
    @Volatile private var validAccess = "access-2"
    @Volatile private var refreshReply: () -> MockResponse = {
        refreshes.incrementAndGet()
        Thread.sleep(200) // laisse les autres requêtes arriver pendant le refresh
        MockResponse().setBody(tokensJson(validAccess, "refresh-2"))
    }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path!!.substringBefore('?')) {
                "/api/auth/app/refresh" -> refreshReply()
                "/api/me/continue-watching" ->
                    if (request.getHeader("Authorization") == "Bearer $validAccess") MockResponse().setBody("[]")
                    else MockResponse().setResponseCode(401).setBody("""{"status":401,"error":"UNAUTHORIZED"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        session = SessionStore(store, FakeCipher())
        session.startSession(server.url("/"), AppJson.decodeFromString<AppTokens>(tokensJson("access-1", "refresh-1")))
        val base = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        val client = base.newBuilder().addInterceptor(AuthInterceptor(session))
            .authenticator(TokenAuthenticator(TokenRefresher(session, base))).build()
        api = AnimeApi(session, client)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun concurrent401sTriggerASingleRefreshAndAreReplayed() = runBlocking {
        val results = (1..5).map { async(kotlinx.coroutines.Dispatchers.IO) { api.continueWatching() } }.awaitAll()
        assertEquals(5, results.size)
        assertEquals(1, refreshes.get())
        assertEquals("access-2", session.accessToken)
        assertEquals("refresh-2", session.refreshToken())
        assertEquals("enc:2-hserfer", store.values["refresh_token"]) // stocké chiffré, jamais en clair
    }

    @Test
    fun startupWithoutAccessTokenRefreshesFirst() = runBlocking {
        val restarted = SessionStore(store, FakeCipher()) // access token perdu (mémoire), refresh token gardé
        assertNull(restarted.accessToken)
        assertTrue(restarted.loggedIn.value)
        val base = OkHttpClient()
        val client = base.newBuilder().addInterceptor(AuthInterceptor(restarted))
            .authenticator(TokenAuthenticator(TokenRefresher(restarted, base))).build()
        AnimeApi(restarted, client).continueWatching()
        assertEquals("access-2", restarted.accessToken)
    }

    @Test
    fun rejectedRefreshLogsOutCleanly() = runBlocking {
        refreshReply = { MockResponse().setResponseCode(401).setBody("""{"status":401,"error":"INVALID_REFRESH_TOKEN","message":"Session expirée, reconnectez-vous"}""") }
        try {
            api.continueWatching()
            fail("401 attendu")
        } catch (e: ApiException) {
            assertEquals(401, e.status)
        }
        assertFalse(session.loggedIn.value)
        assertTrue(session.expired.value)
        assertNull(session.accessToken)
        assertNull(store.values["refresh_token"])
        assertEquals("http://localhost:${server.port}/", session.serverUrl.toString()) // adresse gardée
    }

    @Test
    fun networkFailureDuringRefreshKeepsTheSession() = runBlocking {
        refreshReply = { MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        try {
            api.continueWatching()
            fail("erreur réseau attendue")
        } catch (e: ApiException) {
            assertTrue("${e.status} ${e.code} ${e.message} ${e.cause}", e.isNetwork)
        }
        assertTrue(session.loggedIn.value)
        assertEquals("refresh-1", session.refreshToken())
    }

    @Test
    fun tokenIsNeverSentToAnotherHost() {
        val other = MockWebServer()
        other.enqueue(MockResponse().setBody("ok"))
        other.start()
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(session)).build()
        // même machine, autre port : un autre serveur
        client.newCall(okhttp3.Request.Builder().url(other.url("/x")).build()).execute().close()
        assertNull(other.takeRequest().getHeader("Authorization"))
        other.shutdown()
    }

    @Test
    fun tokensNeverAppearInToString() {
        val t = AppJson.decodeFromString<AppTokens>(tokensJson("secret-access", "secret-refresh"))
        assertFalse(t.toString().contains("secret"))
    }
}
