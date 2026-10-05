package fr.plexwish.anime.feature.player

import androidx.media3.common.PlaybackException
import fr.plexwish.anime.data.log.SafeLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Ni URL signée (sig), ni jeton : dans les journaux (Logcat), les messages d'erreur, les détails, l'état du lecteur. */
class NoSecretsTest : PlayerTestBase() {

    private val lines = CopyOnWriteArrayList<String>()

    private fun capture() {
        SafeLog.minPriority = SafeLog.DEBUG
        SafeLog.sink = SafeLog.Sink { _, tag, msg -> lines += "$tag $msg" }
    }

    @After
    fun resetLog() {
        SafeLog.sink = SafeLog.Sink { _, _, _ -> }
        SafeLog.minPriority = SafeLog.INFO
    }

    private val secrets = listOf("SECRETSIG", "ACCESSTOKEN123", "REFRESHTOKEN456", "eyJhbGciOiJIUzI1NiJ9")

    private fun assertClean(what: String, text: String) {
        secrets.forEach { assertFalse("$what contient « $it » : $text", text.contains(it)) }
        // Une signature n'apparaît que masquée.
        assertFalse("$what contient une signature : $text", Regex("""sig=(?!\*\*\*)""").containsMatchIn(text))
    }

    @Test
    fun sanitizeMasksSignedUrlsAndTokens() {
        val s = SafeLog.sanitize(
            "GET https://anime.example.fr/api/stream/12?u=1&exp=1790000000&sig=SECRETSIG1 failed; " +
                "/api/stream/12?u=1&exp=2&sig=SECRETSIG2 ; Authorization: Bearer ACCESSTOKEN123 ; " +
                "{\"refreshToken\":\"REFRESHTOKEN456\"} ; eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcDEF123 ; sig=SECRETSIG3",
        )
        assertClean("sanitize", s)
        assertTrue(s, s.contains("https://anime.example.fr/api/stream/12?***"))
    }

    @Test
    fun playerErrorsNeverLeakTheSignedUrl() {
        capture()
        val engine = FakeEngine()
        val vm = player(engine)
        await("chargement") { engine.loads.size == 1 }
        val url = engine.loads[0].url
        assertTrue(url.contains("sig=SECRETSIG1")) // le moteur, lui, la reçoit
        // Causes d'erreur qui citeraient l'URL complète et le jeton (ex. message d'une exception réseau).
        val leaky = SafeLog.describe(java.io.IOException("unexpected end of stream on $url (Bearer ACCESSTOKEN123)",
            RuntimeException("sig=SECRETSIG1")))
        engine.error(failure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403, leaky))
        await("nouvelle URL") { engine.loads.size == 2 }
        engine.error(failure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, null, leaky))
        val s = vm.state.value
        assertEquals(PlayerPhase.ERROR, s.phase)
        assertClean("état", s.toString())
        assertClean("message", s.error!!.message)
        s.error!!.details.forEach { assertClean("détails", "${it.first} ${it.second}") }
        assertTrue(lines.isNotEmpty())
        lines.forEach { assertClean("journal", it) }
        // toString des objets qui portent l'URL : masqués.
        assertClean("SignedStream", fr.plexwish.anime.data.api.SignedStream(url, 0).toString())
    }

    /** Aucun appel direct à Logcat (ni println) hors de SafeLog / LogcatSink : tout passe par le nettoyage. */
    @Test
    fun noDirectLoggingInTheApp() {
        val root = File("src/main/java")
        assertTrue(root.absolutePath, root.isDirectory)
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "SafeLog.kt" && it.name != "LogcatSink.kt" }
            .filter { f ->
                val t = f.readText()
                t.contains("import android.util.Log") || Regex("""\bLog\.(v|d|i|w|e|wtf|println)\(""").containsMatchIn(t) ||
                    Regex("""\bprintln\(|printStackTrace\(""").containsMatchIn(t)
            }.map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
