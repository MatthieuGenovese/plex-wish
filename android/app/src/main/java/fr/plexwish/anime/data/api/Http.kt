package fr.plexwish.anime.data.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Appel OkHttp suspendu (annulé si la coroutine l'est). */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** Corps JSON d'une réponse 2xx, sinon {@link ApiException} avec le message de l'API. */
inline fun <reified T> Response.decode(): T = use {
    val text = body?.string().orEmpty()
    if (!isSuccessful) throw ApiException.fromResponse(code, runCatching { AppJson.decodeFromString<ApiErrorBody>(text) }.getOrNull())
    try {
        AppJson.decodeFromString<T>(text)
    } catch (e: Exception) {
        throw ApiException(code, null, "Réponse inattendue du serveur. L'adresse est-elle bien celle de l'Anime Server ?", e)
    }
}

fun Response.requireSuccess() = use {
    if (!isSuccessful) {
        val text = body?.string().orEmpty()
        throw ApiException.fromResponse(code, runCatching { AppJson.decodeFromString<ApiErrorBody>(text) }.getOrNull())
    }
}
