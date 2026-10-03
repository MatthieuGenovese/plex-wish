package fr.plexwish.anime.data.auth

import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Ajoute l'access token aux requêtes vers notre serveur (jamais ailleurs, jamais aux endpoints de connexion). */
class AuthInterceptor(private val session: SessionStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val server = session.serverUrl
        val token = session.accessToken
        val ours = server != null && request.url.host == server.host && request.url.port == server.port
        if (!ours || token == null || request.url.encodedPath.startsWith("/api/auth/") || request.header("Authorization") != null) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/**
 * Sur 401 : rafraîchit (une seule fois à la fois, voir {@link TokenRefresher}) et rejoue la requête.
 * Session refusée : la requête échoue en 401 et l'app revient à la connexion. Réseau indisponible pendant le
 * rafraîchissement : erreur réseau (message clair), session conservée.
 */
class TokenAuthenticator(private val refresher: TokenRefresher) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath.startsWith("/api/auth/")) return null
        if (priorResponses(response) >= 2) return null
        val failed = response.request.header("Authorization")?.removePrefix("Bearer ")
        return when (val outcome = refresher.refresh(failed)) {
            is TokenRefresher.Outcome.Refreshed ->
                response.request.newBuilder().header("Authorization", "Bearer ${outcome.accessToken}").build()
            TokenRefresher.Outcome.LoggedOut -> null
            is TokenRefresher.Outcome.Failed -> throw outcome.error
        }
    }

    private fun priorResponses(response: Response): Int {
        var n = 0
        var r = response.priorResponse
        while (r != null) {
            n++
            r = r.priorResponse
        }
        return n
    }
}
