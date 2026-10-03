package fr.plexwish.anime.data

import fr.plexwish.anime.FakeCipher
import fr.plexwish.anime.MemoryStore
import fr.plexwish.anime.data.api.ApiException
import fr.plexwish.anime.data.auth.AuthRepository
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.tokensJson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AuthRepositoryTest {

    private val server = MockWebServer()
    private val store = MemoryStore()
    private val session = SessionStore(store, FakeCipher())
    private fun repo(allowHttp: Boolean = true) = AuthRepository(session, OkHttpClient(), allowHttp, "Galaxy S24")

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun expectError(block: suspend () -> Unit): ApiException = runBlocking {
        try {
            block()
            fail("erreur attendue")
            throw IllegalStateException()
        } catch (e: ApiException) {
            e
        }
    }

    @Test
    fun loginChecksTheServerThenStoresTheSession() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"UP"}"""))
        server.enqueue(MockResponse().setBody(tokensJson("a1", "r1")))
        repo().login(server.url("/").toString(), " alice ", "mot-de-passe")
        assertEquals("/api/status", server.takeRequest().path)
        val login = server.takeRequest()
        assertEquals("/api/auth/app/login", login.path)
        assertNull("jamais d'en-tête Origin (réservé aux clients natifs)", login.getHeader("Origin"))
        val body = login.body.readUtf8()
        assertTrue(body.contains("\"login\":\"alice\"") && body.contains("\"device\":\"Galaxy S24\""))
        assertTrue(session.loggedIn.value)
        assertEquals("a1", session.accessToken)
        assertEquals("r1", session.refreshToken())
        assertFalse(store.values.values.any { it.contains("r1") && !it.startsWith("enc:") })
        assertFalse("le mot de passe n'est jamais stocké", store.values.values.any { it.contains("mot-de-passe") })
        assertEquals("alice", repo().savedUsername)
    }

    @Test
    fun lockoutMessageGivesTheDelay() {
        server.enqueue(MockResponse().setBody("""{"status":"UP"}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody(
            """{"status":429,"error":"TOO_MANY_ATTEMPTS","message":"Trop de tentatives de connexion. Réessayez dans 14 minutes."}"""))
        val e = expectError { repo().login(server.url("/").toString(), "alice", "x") }
        assertEquals("TOO_MANY_ATTEMPTS", e.code)
        assertTrue(e.message.contains("14 minutes"))
        assertFalse(session.loggedIn.value)
    }

    @Test
    fun anotherWebsiteIsDetected() {
        server.enqueue(MockResponse().setBody("<html>DSM</html>"))
        val e = expectError { repo().login(server.url("/").toString(), "alice", "x") }
        assertEquals("NOT_ANIME_SERVER", e.code)
        assertEquals(1, server.requestCount) // aucun mot de passe envoyé à un autre site
    }

    @Test
    fun httpRefusedInRelease() {
        val e = expectError { repo(allowHttp = false).login(server.url("/").toString(), "alice", "x") }
        assertTrue(e.message.contains("https://"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun logoutRevokesServerSideAndClearsLocally() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"UP"}"""))
        server.enqueue(MockResponse().setBody(tokensJson("a1", "r1")))
        server.enqueue(MockResponse().setResponseCode(204))
        repo().login(server.url("/").toString(), "alice", "pw")
        repo().logout()
        server.takeRequest(); server.takeRequest()
        val logout = server.takeRequest()
        assertEquals("/api/auth/app/logout", logout.path)
        assertTrue(logout.body.readUtf8().contains("r1"))
        assertFalse(session.loggedIn.value)
        assertFalse(session.expired.value)
        assertNull(session.refreshToken())
    }
}
