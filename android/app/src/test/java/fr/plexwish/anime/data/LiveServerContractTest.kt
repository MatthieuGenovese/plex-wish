package fr.plexwish.anime.data

import fr.plexwish.anime.FakeCipher
import fr.plexwish.anime.MemoryStore
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.auth.AuthInterceptor
import fr.plexwish.anime.data.auth.AuthRepository
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.data.auth.TokenAuthenticator
import fr.plexwish.anime.data.auth.TokenRefresher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Contrat avec un vrai serveur (facultatif) : le code réseau de l'app contre l'API réelle.
 * Lancé seulement si PLEXWISH_IT_SERVER est défini, par exemple :
 *   PLEXWISH_IT_SERVER=http://localhost:8080 PLEXWISH_IT_USER=admin PLEXWISH_IT_PASSWORD=… ./gradlew testDebugUnitTest
 */
class LiveServerContractTest {

    private val server: String? = System.getenv("PLEXWISH_IT_SERVER")

    @Test
    fun loginRefreshLibraryAndLogoutAgainstTheRealApi() = runBlocking {
        assumeTrue("PLEXWISH_IT_SERVER non défini : test ignoré", server != null)
        val store = MemoryStore()
        val session = SessionStore(store, FakeCipher())
        val base = OkHttpClient()
        val auth = AuthRepository(session, base, allowHttp = true, deviceName = "Test JVM")
        auth.login(server!!, System.getenv("PLEXWISH_IT_USER"), System.getenv("PLEXWISH_IT_PASSWORD"))
        val firstRefresh = session.refreshToken()

        val client = base.newBuilder().addInterceptor(AuthInterceptor(session))
            .authenticator(TokenAuthenticator(TokenRefresher(session, base))).build()
        val api = AnimeApi(session, client)
        val page = api.animes("title", null, 0, 60)
        assertTrue(page.total >= page.items.size)
        api.continueWatching()

        // Redémarrage de l'app : access token perdu, le refresh token chiffré suffit (rotation côté serveur).
        val restarted = SessionStore(store, FakeCipher())
        val client2 = base.newBuilder().addInterceptor(AuthInterceptor(restarted))
            .authenticator(TokenAuthenticator(TokenRefresher(restarted, base))).build()
        assertEquals(page.total, AnimeApi(restarted, client2).animes("title", null, 0, 60).total)
        assertNotEquals(firstRefresh, restarted.refreshToken())

        AuthRepository(restarted, base, true, "Test JVM").logout()
        assertTrue(!restarted.loggedIn.value)
        println("Contrat OK : ${page.total} animés")
    }
}
