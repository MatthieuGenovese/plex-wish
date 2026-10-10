package fr.plexwish.anime.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.plexwish.anime.data.api.ApiException
import fr.plexwish.anime.data.auth.AuthRepository
import fr.plexwish.anime.data.auth.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginState(
    val server: String = "",
    val login: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    /** La session précédente a expiré ou a été révoquée. */
    val expired: Boolean = false,
    /** « Afficher le mot de passe ». */
    val visible: Boolean = false,
    /** Adresse fixée à la compilation : ni affichée ni modifiable (le champ n'existe pas à l'écran). */
    val fixedServer: Boolean = false,
) {
    val canSubmit get() = !loading && server.isNotBlank() && login.isNotBlank() && password.isNotEmpty()
    override fun toString() = "LoginState(server=$server, login=$login, loading=$loading)" // jamais le mot de passe
}

/**
 * Connexion. Adresse du serveur : celle fixée à la compilation ({@code BuildConfig.DEFAULT_SERVER_URL}, propriété
 * {@code plexwish.serverUrl}) quand il y en a une, toujours utilisée et jamais affichée ; sinon (versions d'essai sans
 * adresse) la dernière utilisée, saisie à l'écran.
 */
class LoginViewModel(private val auth: AuthRepository, session: SessionStore, defaultServer: String = "") : ViewModel() {

    private val _state = MutableStateFlow(
        if (defaultServer.isNotBlank()) {
            LoginState(server = defaultServer, fixedServer = true, login = auth.savedUsername.orEmpty(), expired = session.expired.value)
        } else {
            LoginState(server = auth.savedServer.orEmpty(), login = auth.savedUsername.orEmpty(), expired = session.expired.value)
        },
    )
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun onServer(v: String) = _state.update { if (it.fixedServer) it else it.copy(server = v, error = null) }
    fun onLogin(v: String) = _state.update { it.copy(login = v, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun toggleVisible() = _state.update { it.copy(visible = !it.visible) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                auth.login(s.server, s.login, s.password)
                _state.update { it.copy(loading = false, password = "", expired = false) }
            } catch (e: ApiException) {
                // 429 : le message du serveur donne le délai à attendre (« Réessayez dans 12 minutes. »).
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }
}
