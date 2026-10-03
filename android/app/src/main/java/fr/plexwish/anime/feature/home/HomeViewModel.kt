package fr.plexwish.anime.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.api.AnimeSummary
import fr.plexwish.anime.data.api.ApiException
import fr.plexwish.anime.data.api.ContinueWatching
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.feature.image
import kotlinx.coroutines.async
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

class HomeViewModel(private val api: AnimeApi, private val session: SessionStore) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val cw = async { api.continueWatching(20) }
                val recent = async { api.animes("recent", null, 0, 20) }
                _state.value = HomeState(
                    loading = false,
                    continueWatching = cw.await().map { it.copy(posterUrl = session.image(it.posterUrl)) },
                    recent = recent.await().items.map { it.copy(posterUrl = session.image(it.posterUrl)) },
                )
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }
}
