package fr.plexwish.anime.data.auth

import fr.plexwish.anime.data.api.AppTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Stockage clé → valeur (SharedPreferences dans l'app, une Map dans les tests). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * Session : adresse du serveur et identifiant (en clair, ce ne sont pas des secrets), refresh token **chiffré**
 * (Keystore), access token **en mémoire seulement**. Rien de tout cela n'est jamais journalisé.
 */
class SessionStore(private val store: KeyValueStore, private val cipher: TokenCipher) {

    @Volatile
    var accessToken: String? = null
        private set

    private val _loggedIn = MutableStateFlow(store.get(KEY_REFRESH) != null)
    /** false après une déconnexion, y compris forcée (refresh refusé) : l'interface revient à la connexion. */
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    private val _expired = MutableStateFlow(false)
    /** true si la dernière déconnexion vient d'une session expirée ou révoquée (message à l'écran de connexion). */
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    val serverUrl: HttpUrl? get() = store.get(KEY_SERVER)?.toHttpUrlOrNull()
    val username: String? get() = store.get(KEY_USER)

    /** Refresh token déchiffré, ou null (absent, ou clé du Keystore perdue : il faudra se reconnecter). */
    fun refreshToken(): String? {
        val encoded = store.get(KEY_REFRESH) ?: return null
        return runCatching { cipher.decrypt(encoded) }.getOrNull()
    }

    @Synchronized
    fun startSession(server: HttpUrl, tokens: AppTokens) {
        store.put(KEY_SERVER, server.toString())
        store.put(KEY_USER, tokens.user.username)
        updateTokens(tokens.accessToken, tokens.refreshToken)
        _expired.value = false
        _loggedIn.value = true
    }

    @Synchronized
    fun updateTokens(access: String, refresh: String) {
        store.put(KEY_REFRESH, cipher.encrypt(refresh))
        accessToken = access
    }

    /** Fin de session : jetons effacés ; adresse et identifiant gardés pour se reconnecter vite. */
    @Synchronized
    fun clear(expired: Boolean) {
        accessToken = null
        store.put(KEY_REFRESH, null)
        _expired.value = expired
        _loggedIn.value = false
    }

    private companion object {
        const val KEY_SERVER = "server"
        const val KEY_USER = "username"
        const val KEY_REFRESH = "refresh_token"
    }
}
