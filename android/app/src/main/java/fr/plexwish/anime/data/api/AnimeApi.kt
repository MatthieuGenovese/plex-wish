package fr.plexwish.anime.data.api

import fr.plexwish.anime.data.auth.SessionStore
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Appels à l'API REST (la même que le web), avec le client authentifié (access token ajouté, rafraîchi sur 401).
 * Aucune logique métier ici : l'app affiche ce que renvoie le serveur.
 */
class AnimeApi(private val session: SessionStore, private val client: OkHttpClient) {

    private fun url(path: String, vararg query: Pair<String, Any?>): HttpUrl {
        val server = session.serverUrl ?: throw ApiException(401, "NO_SERVER", "Session expirée, reconnectez-vous.")
        val b = server.newBuilder().encodedPath(path)
        query.forEach { (k, v) -> if (v != null && v.toString().isNotEmpty()) b.addQueryParameter(k, v.toString()) }
        return b.build()
    }

    suspend inline fun <reified T> get(url: HttpUrl): T = try {
        call(Request.Builder().url(url).get().build()).decode<T>()
    } catch (e: IOException) {
        throw ApiException.fromNetwork(e)
    }

    @PublishedApi
    internal suspend fun call(request: Request) = client.newCall(request).await()

    suspend fun continueWatching(limit: Int = 20): List<ContinueWatching> =
        get(url("/api/me/continue-watching", "limit" to limit))

    /** {@code sort} : « title » (bibliothèque) ou « recent » (derniers ajouts) ; {@code page} à partir de 0. */
    suspend fun animes(sort: String, query: String?, page: Int, size: Int): Page<AnimeSummary> =
        get(url("/api/anime", "sort" to sort, "q" to query?.trim(), "page" to page, "size" to size))
}
