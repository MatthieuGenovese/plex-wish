package fr.plexwish.anime.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {

    private fun ok(input: String, allowHttp: Boolean = false) =
        (ServerUrl.normalize(input, allowHttp) as ServerUrl.Result.Ok).url.let(ServerUrl::display)

    private fun invalid(input: String, allowHttp: Boolean = false) =
        (ServerUrl.normalize(input, allowHttp) as ServerUrl.Result.Invalid).message

    @Test
    fun normalizesToAnOrigin() {
        assertEquals("https://anime.mondomaine.fr", ok("anime.mondomaine.fr"))
        assertEquals("https://anime.mondomaine.fr", ok("  https://anime.mondomaine.fr/  "))
        assertEquals("https://anime.mondomaine.fr", ok("HTTPS://Anime.MonDomaine.fr"))
        assertEquals("https://monnas.synology.me:8443", ok("https://monnas.synology.me:8443/"))
        assertEquals("https://anime.mondomaine.fr", ok("https://anime.mondomaine.fr:443"))
    }

    @Test
    fun httpOnlyInDebugBuilds() {
        assertTrue(invalid("http://192.168.1.20:8080").contains("https://"))
        assertEquals("http://localhost:8080", ok("http://localhost:8080", allowHttp = true))
    }

    @Test
    fun refusesPathsCredentialsAndGarbage() {
        assertTrue(invalid("").contains("Saisissez"))
        assertTrue(invalid("https://anime.mondomaine.fr/app").contains("sans chemin"))
        assertTrue(invalid("https://anime.mondomaine.fr/?x=1").contains("sans chemin"))
        assertTrue(invalid("https://user:pw@anime.mondomaine.fr").contains("identifiant"))
        assertTrue(invalid("ftp://anime.mondomaine.fr").contains("invalide"))
        assertTrue(invalid("https://").contains("invalide"))
    }
}
