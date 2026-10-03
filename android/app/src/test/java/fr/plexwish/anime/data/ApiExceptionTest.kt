package fr.plexwish.anime.data

import fr.plexwish.anime.data.api.ApiErrorBody
import fr.plexwish.anime.data.api.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLHandshakeException

class ApiExceptionTest {

    @Test
    fun serverMessagesAreKept() {
        val e = ApiException.fromResponse(429, ApiErrorBody(429, "TOO_MANY_ATTEMPTS", "Trop de tentatives de connexion. Réessayez dans 12 minutes."))
        assertEquals(429, e.status)
        assertEquals("TOO_MANY_ATTEMPTS", e.code)
        assertTrue(e.message.contains("12 minutes"))
        assertEquals("Identifiant ou mot de passe incorrect",
            ApiException.fromResponse(401, ApiErrorBody(401, "INVALID_CREDENTIALS", "Identifiant ou mot de passe incorrect")).message)
    }

    @Test
    fun responsesWithoutJsonGetAUsefulMessage() {
        assertTrue(ApiException.fromResponse(502, null).message.contains("HTTP 502"))
        assertTrue(ApiException.fromResponse(200, null).message.contains("Anime Server"))
        assertTrue(ApiException.fromResponse(429, null).message.contains("réessayez"))
    }

    @Test
    fun networkErrorsAreExplained() {
        assertTrue(ApiException.fromNetwork(UnknownHostException("anime.x")).message.contains("introuvable"))
        assertTrue(ApiException.fromNetwork(ConnectException()).message.contains("ne répond pas"))
        assertTrue(ApiException.fromNetwork(SocketTimeoutException()).message.contains("trop de temps"))
        assertTrue(ApiException.fromNetwork(SSLHandshakeException("x")).message.contains("certificat"))
        assertTrue(ApiException.fromNetwork(UnknownServiceException("CLEARTEXT")).message.contains("https://"))
        assertTrue(ApiException.fromNetwork(UnknownHostException("x")).isNetwork)
    }
}
