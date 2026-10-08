package fr.plexwish.anime.feature.account

import fr.plexwish.anime.FakeCipher
import fr.plexwish.anime.MemoryStore
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.api.AppJson
import fr.plexwish.anime.data.api.AppTokens
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.tokensJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Changement de mot de passe depuis l'app (S4) : vérifications locales, réponse du serveur, rien de secret ailleurs. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {

    private val server = MockWebServer()
    private lateinit var vm: AccountViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server.start()
        val session = SessionStore(MemoryStore(), FakeCipher())
        session.startSession(server.url("/"), AppJson.decodeFromString<AppTokens>(tokensJson("a", "refresh-du-telephone")))
        vm = AccountViewModel(AnimeApi(session, OkHttpClient()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    private fun fill(current: String, new: String, confirm: String = new) {
        vm.onCurrent(current); vm.onNew(new); vm.onConfirm(confirm)
    }

    private fun waitDone() = runBlocking { withTimeout(5000) { vm.form.first { !it.saving } } }

    @Test
    fun localChecksNeverReachTheServer() {
        fill("ancien-mdp-123", "court")
        vm.submit()
        assertEquals("Le nouveau mot de passe doit faire au moins 10 caractères.", vm.form.value.error)
        fill("ancien-mdp-123", "nouveau-mdp-456", "nouveau-mdp-457")
        vm.submit()
        assertEquals("La confirmation ne correspond pas au nouveau mot de passe.", vm.form.value.error)
        fill("meme-mot-de-passe", "meme-mot-de-passe")
        vm.submit()
        assertEquals("Le nouveau mot de passe doit être différent de l'actuel.", vm.form.value.error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun successKeepsThisPhoneAndTellsHowManyDevicesWereClosed() {
        server.enqueue(MockResponse().setBody("""{"closedSessions":2}"""))
        fill("ancien-mdp-123", "nouveau-mdp-456")
        vm.submit()
        val f = waitDone()
        assertEquals("Mot de passe changé. 2 autres appareils ont été déconnectés.", f.done)
        assertEquals("", f.current) // formulaire vidé
        val r = server.takeRequest()
        assertEquals("/api/auth/app/password", r.path)
        assertEquals("POST", r.method)
        val body = r.body.readUtf8()
        assertTrue(body.contains(""""refreshToken":"refresh-du-telephone"""")) // la session de ce téléphone est gardée
        assertTrue(body.contains(""""currentPassword":"ancien-mdp-123"""") && body.contains(""""newPassword":"nouveau-mdp-456""""))
        assertFalse(f.toString().contains("mdp"))
    }

    @Test
    fun wrongCurrentPasswordAndTooManyAttemptsAreExplained() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"status":400,"error":"WRONG_PASSWORD","message":"Le mot de passe actuel est incorrect"}"""))
        fill("faux-mdp-123", "nouveau-mdp-456")
        vm.submit()
        assertEquals("Le mot de passe actuel est incorrect.", waitDone().error)
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"status":429,"error":"TOO_MANY_ATTEMPTS","message":"Trop de tentatives. Réessayez dans 12 minutes."}"""))
        vm.submit()
        assertEquals("Trop de tentatives. Réessayez dans 12 minutes.", waitDone().error)
    }
}
