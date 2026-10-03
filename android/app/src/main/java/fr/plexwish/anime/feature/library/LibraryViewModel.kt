package fr.plexwish.anime.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.api.AnimeSummary
import fr.plexwish.anime.data.api.ApiException
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.feature.image
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibrarySort(val api: String, val label: String) { TITLE("title", "Titre"), RECENT("recent", "Récents") }

data class LibraryState(
    val query: String = "",
    val sort: LibrarySort = LibrarySort.TITLE,
    val items: List<AnimeSummary> = emptyList(),
    val total: Long = 0,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
) {
    val endReached get() = items.size >= total
}

/**
 * Bibliothèque : recherche (par le serveur, insensible aux accents), tri, pages de {@value PAGE_SIZE}. Recherche et tri
 * survivent à la rotation, à la navigation et à la mort du processus (SavedStateHandle) ; la liste est rechargée.
 */
@OptIn(FlowPreview::class)
class LibraryViewModel(
    private val api: AnimeApi,
    private val session: SessionStore,
    private val saved: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LibraryState(
            query = saved["q"] ?: "",
            sort = saved.get<String>("sort")?.let { s -> LibrarySort.entries.find { it.name == s } } ?: LibrarySort.TITLE,
        ),
    )
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    private val queries = MutableStateFlow(_state.value.query)
    private var job: Job? = null

    init {
        reload()
        queries.drop(1).debounce(300).distinctUntilChanged().onEach { reload() }.launchIn(viewModelScope)
    }

    fun onQuery(q: String) {
        saved["q"] = q
        _state.update { it.copy(query = q) }
        queries.value = q
    }

    fun onSort(sort: LibrarySort) {
        if (sort == _state.value.sort) return
        saved["sort"] = sort.name
        _state.update { it.copy(sort = sort) }
        reload()
    }

    fun reload() {
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val s = _state.value
                val page = api.animes(s.sort.api, s.query, 0, PAGE_SIZE)
                _state.update { it.copy(items = page.items.map(::withImage), total = page.total, loading = false) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    /** Après une erreur : tout recharger, ou seulement la page suivante si la grille est déjà remplie. */
    fun retry() {
        if (_state.value.items.isEmpty()) {
            reload()
        } else {
            _state.update { it.copy(error = null) }
            loadMore()
        }
    }

    /** Page suivante (appelée en approchant de la fin de la grille). */
    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || s.endReached || s.error != null) return
        _state.update { it.copy(loadingMore = true) }
        job = viewModelScope.launch {
            try {
                val page = api.animes(s.sort.api, s.query, s.items.size / PAGE_SIZE, PAGE_SIZE)
                _state.update { cur ->
                    val known = cur.items.map { it.id }.toHashSet()
                    cur.copy(items = cur.items + page.items.filter { it.id !in known }.map(::withImage),
                        total = page.total, loadingMore = false)
                }
            } catch (e: ApiException) {
                _state.update { it.copy(loadingMore = false, error = e.message) }
            }
        }
    }

    private fun withImage(a: AnimeSummary) = a.copy(posterUrl = session.image(a.posterUrl))

    companion object {
        const val PAGE_SIZE = 60
    }
}
