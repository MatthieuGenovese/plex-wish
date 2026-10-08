package fr.plexwish.anime.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Formulaire « Mot de passe » de l'écran Compte. Les mots de passe ne sont jamais journalisés (toString masqué). */
data class PasswordForm(
    val current: String = "",
    val new: String = "",
    val confirm: String = "",
    val visible: Boolean = false,
    val saving: Boolean = false,
    /** Message de réussite (« Mot de passe changé… »). */
    val done: String? = null,
    /** Erreur à afficher (vérification locale ou réponse du serveur). */
    val error: String? = null,
) {
    val canSubmit get() = !saving && current.isNotEmpty() && new.isNotEmpty() && confirm.isNotEmpty()
    override fun toString() = "PasswordForm(saving=$saving, done=${done != null}, error=$error)"
}

/**
 * Compte : changement du mot de passe (S4, POST /api/auth/app/password). La session de ce téléphone reste ouverte ;
 * les autres appareils sont déconnectés. Vérifications locales d'abord (10 caractères, confirmation, différent de
 * l'actuel), les mêmes règles que le serveur, qui reste juge.
 */
class AccountViewModel(private val api: AnimeApi) : ViewModel() {

    private val _form = MutableStateFlow(PasswordForm())
    val form: StateFlow<PasswordForm> = _form.asStateFlow()

    fun onCurrent(v: String) = _form.update { it.copy(current = v, error = null, done = null) }
    fun onNew(v: String) = _form.update { it.copy(new = v, error = null, done = null) }
    fun onConfirm(v: String) = _form.update { it.copy(confirm = v, error = null, done = null) }
    fun toggleVisible() = _form.update { it.copy(visible = !it.visible) }

    fun submit() {
        val f = _form.value
        if (!f.canSubmit) return
        val local = when {
            f.new.length < MIN_LENGTH -> "Le nouveau mot de passe doit faire au moins $MIN_LENGTH caractères."
            f.new != f.confirm -> "La confirmation ne correspond pas au nouveau mot de passe."
            f.new == f.current -> "Le nouveau mot de passe doit être différent de l'actuel."
            else -> null
        }
        if (local != null) {
            _form.update { it.copy(error = local) }
            return
        }
        _form.update { it.copy(saving = true, error = null, done = null) }
        viewModelScope.launch {
            try {
                val closed = api.changePassword(f.current, f.new)
                val others = when (closed) {
                    0 -> "Aucun autre appareil n'était connecté."
                    1 -> "1 autre appareil a été déconnecté."
                    else -> "$closed autres appareils ont été déconnectés."
                }
                _form.value = PasswordForm(done = "Mot de passe changé. $others")
            } catch (e: ApiException) {
                val message = if (e.code == "WRONG_PASSWORD") "Le mot de passe actuel est incorrect." else e.message.let {
                    if (it.endsWith(".")) it else "$it."
                }
                _form.update { it.copy(saving = false, error = message) }
            }
        }
    }

    companion object {
        const val MIN_LENGTH = 10
    }
}
