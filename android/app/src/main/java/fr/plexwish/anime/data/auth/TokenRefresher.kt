package fr.plexwish.anime.data.auth

import fr.plexwish.anime.data.api.ApiException
import fr.plexwish.anime.data.api.AppJson
import fr.plexwish.anime.data.api.AppTokens
import fr.plexwish.anime.data.api.decode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Rafraîchissement de l'access token, **un seul à la fois** : les requêtes qui reçoivent un 401 en même temps
 * attendent celui en cours puis rejouent avec le nouveau jeton (même principe que l'interceptor du web).
 * Refresh refusé (401) : session effacée, retour à l'écran de connexion. Réseau indisponible : la session est
 * gardée (rien n'est effacé pour une coupure).
 */
class TokenRefresher(private val session: SessionStore, private val client: OkHttpClient) {

    sealed interface Outcome {
        data class Refreshed(val accessToken: String) : Outcome
        data object LoggedOut : Outcome
        data class Failed(val error: ApiException) : Outcome
    }

    private val lock = Any()

    /** {@code failedAccessToken} : jeton de la requête refusée (null si elle n'en avait pas). */
    fun refresh(failedAccessToken: String?): Outcome = synchronized(lock) {
        val current = session.accessToken
        if (current != null && current != failedAccessToken) {
            return Outcome.Refreshed(current) // un autre appel vient de rafraîchir : on rejoue directement
        }
        val server = session.serverUrl ?: return logOut()
        val token = session.refreshToken() ?: return logOut()
        val body = AppJson.encodeToString(buildJsonObject { put("refreshToken", token) })
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(server.resolve("/api/auth/app/refresh")!!).post(body).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 400) return logOut()
                val tokens = response.decode<AppTokens>()
                session.updateTokens(tokens.accessToken, tokens.refreshToken)
                Outcome.Refreshed(tokens.accessToken)
            }
        } catch (e: IOException) {
            Outcome.Failed(ApiException.fromNetwork(e))
        }
    }

    private fun logOut(): Outcome {
        session.clear(expired = true)
        return Outcome.LoggedOut
    }
}
