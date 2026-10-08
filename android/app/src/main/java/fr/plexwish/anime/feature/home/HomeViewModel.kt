package fr.plexwish.anime.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.api.AnimeSummary
import fr.plexwish.anime.data.api.ApiException
import fr.plexwish.anime.data.api.ContinueWatching
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.feature.image
import fr.plexwish.anime.feature.player.ProgressBus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = true,
    val continueWatching: List<ContinueWatching> = emptyList(),
    val recent: List<AnimeSummary> = emptyList(),
    val error: String? = null,
)

class HomeViewModel(
    private val api: AnimeApi,
    private val session: SessionStore,
    progressBus: ProgressBus = ProgressBus(),
) : ViewModel() {

    private var refreshJob: kotlinx.coroutines.Job? = null
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        load()
        // Position enregistrée (sortie du lecteur) : « Continuer à regarder » relu quand l'envoi a abouti.
        viewModelScope.launch { progressBus.events.collect { refreshContinueWatching() } }
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                // coroutineScope : l'échec d'un des deux appels remonte ici (attrapé ci-dessous). Un async lancé
                // directement dans le launch ferait aussi échouer le launch lui-même → exception non attrapée, plantage.
                val (cw, recent) = coroutineScope {
                    val cw = async { api.continueWatching(20) }
                    val recent = async { api.animes("recent", null, 0, 20) }
                    cw.await() to recent.await()
                }
                _state.value = HomeState(
                    loading = false,
                    continueWatching = cw.map { it.copy(posterUrl = session.image(it.posterUrl)) },
                    recent = recent.items.map { it.copy(posterUrl = session.image(it.posterUrl)) },
                )
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    /** « Continuer à regarder » relu sans tout recharger (retour sur l'accueil, position enregistrée). Erreur ignorée. */
    fun refreshContinueWatching() {
        refreshJob?.cancel() // seule la dernière relecture compte
        refreshJob = viewModelScope.launch {
            try {
                val cw = api.continueWatching(20).map { it.copy(posterUrl = session.image(it.posterUrl)) }
                _state.update { it.copy(continueWatching = cw) }
            } catch (_: ApiException) {
            }
        }
    }
}
