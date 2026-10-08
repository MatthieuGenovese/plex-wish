package fr.plexwish.anime.data.api

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException

/**
 * Erreur présentable à l'utilisateur. {@code status} / {@code code} : réponse de l'API (ex. 429
 * TOO_MANY_ATTEMPTS) ; {@code status = 0} pour une erreur réseau.
 */
class ApiException(
    val status: Int,
    val code: String?,
    override val message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    val isNetwork get() = status == 0
    val isUnauthorized get() = status == 401

    companion object {
        /** Réponse d'erreur de l'API : son message (en français) s'il est là, sinon un message selon le statut. */
        fun fromResponse(status: Int, body: ApiErrorBody?): ApiException {
            val message = body?.message?.takeIf { it.isNotBlank() } ?: when (status) {
                401 -> "Session expirée, reconnectez-vous."
                403 -> "Accès refusé."
                404 -> "Introuvable."
                429 -> "Trop de tentatives, réessayez dans quelques minutes."
                in 500..599 -> "Le serveur a rencontré une erreur (HTTP $status). Réessayez plus tard."
                else -> "Réponse inattendue du serveur (HTTP $status). L'adresse est-elle bien celle du serveur ${fr.plexwish.anime.BuildConfig.APP_NAME} ?"
            }
            return ApiException(status, body?.error, message)
        }

        /** Erreur réseau → message utile (jamais d'URL ni de jeton dans le message). */
        fun fromNetwork(e: IOException): ApiException {
            if (e is ApiException) return e
            val message = when (e) {
                is UnknownHostException -> "Serveur introuvable : vérifiez l'adresse et la connexion Internet."
                is ConnectException -> "Le serveur ne répond pas : vérifiez l'adresse, et qu'il est bien démarré."
                is SocketTimeoutException -> "Le serveur met trop de temps à répondre. Réessayez."
                is SSLException -> "Connexion HTTPS impossible : certificat du serveur invalide ou expiré."
                is UnknownServiceException -> "Connexion en clair (http) refusée : utilisez une adresse en https://."
                is InterruptedIOException -> "Requête interrompue."
                else -> "Problème de connexion au serveur. Réessayez."
            }
            return ApiException(0, null, message, e)
        }
    }
}
