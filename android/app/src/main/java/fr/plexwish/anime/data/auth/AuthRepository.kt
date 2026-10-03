package fr.plexwish.anime.data.auth

import fr.plexwish.anime.data.ServerUrl
import fr.plexwish.anime.data.api.ApiException
import fr.plexwish.anime.data.api.AppJson
import fr.plexwish.anime.data.api.AppTokens
import fr.plexwish.anime.data.api.StatusDto
import fr.plexwish.anime.data.api.await
import fr.plexwish.anime.data.api.decode
import fr.plexwish.anime.data.api.requireSuccess
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Connexion / déconnexion de l'app (endpoints natifs /api/auth/app, ARCHITECTURE §5.1.1). Le mot de passe n'est
 * jamais stocké ni journalisé ; le refresh token est chiffré (Keystore) et l'access token reste en mémoire.
 */
class AuthRepository(
    private val session: SessionStore,
    private val client: OkHttpClient,
    private val allowHttp: Boolean,
    private val deviceName: String,
) {

    val savedServer: String? get() = session.serverUrl?.let(ServerUrl::display)
    val savedUsername: String? get() = session.username

    /** Vérifie l'adresse (le serveur répond comme un Anime Server), puis se connecte. Erreurs : {@link ApiException}. */
    suspend fun login(serverInput: String, login: String, password: String) {
        val server = when (val r = ServerUrl.normalize(serverInput, allowHttp)) {
            is ServerUrl.Result.Ok -> r.url
            is ServerUrl.Result.Invalid -> throw ApiException(0, "INVALID_SERVER", r.message)
        }
        try {
            val status = client.newCall(Request.Builder().url(server.resolve("/api/status")!!).get().build()).await()
                .decode<StatusDto>()
            if (status.status != "UP") throw notOurServer()
        } catch (e: ApiException) {
            throw if (e.isNetwork) e else notOurServer()
        } catch (e: IOException) {
            throw ApiException.fromNetwork(e)
        }
        val body = AppJson.encodeToString(buildJsonObject {
            put("login", login.trim())
            put("password", password)
            put("device", deviceName.take(100))
        }).toRequestBody(JSON)
        val tokens = try {
            client.newCall(Request.Builder().url(server.resolve("/api/auth/app/login")!!).post(body).build()).await()
                .decode<AppTokens>()
        } catch (e: IOException) {
            throw ApiException.fromNetwork(e)
        }
        session.startSession(server, tokens)
    }

    /** Révoque le refresh token côté serveur (au mieux : sans réseau, la session locale est quand même effacée). */
    suspend fun logout() {
        val server = session.serverUrl
        val token = session.refreshToken()
        session.clear(expired = false)
        if (server != null && token != null) {
            val body = AppJson.encodeToString(buildJsonObject { put("refreshToken", token) }).toRequestBody(JSON)
            runCatching {
                client.newCall(Request.Builder().url(server.resolve("/api/auth/app/logout")!!).post(body).build()).await()
                    .requireSuccess()
            }
        }
    }

    private fun notOurServer() = ApiException(0, "NOT_ANIME_SERVER",
        "Cette adresse ne répond pas comme un Anime Server. Vérifiez-la (ex. https://anime.mondomaine.fr).")

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
