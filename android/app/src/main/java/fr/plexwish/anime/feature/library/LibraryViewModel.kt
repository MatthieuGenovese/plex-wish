package fr.plexwish.anime.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.api.AnimeSummary
import fr.plexwish.anime.data.api.ApiException
import fr.plexwish.anime.data.api.GenreCount
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

/** Tri (S6 : année, plus récents d'abord). */
enum class LibrarySort(val api: String, val label: String) {
    TITLE("title", "Titre (A → Z)"), RECENT("recent", "Derniers ajouts"), YEAR("year", "Année (récents d'abord)")
}

/** Filtre « vu » (S2), selon la progression de l'utilisateur. */
enum class WatchFilter(val api: String, val label: String) {
    UNSEEN("unseen", "Non vus"), IN_PROGRESS("inProgress", "En cours"), SEEN("seen", "Vus")
}

/** Périodes (S2), comme le web. */
enum class Period(val label: String, val from: Int?, val to: Int?) {
    Y2020("Années 2020", 2020, 2029), Y2010("Années 2010", 2010, 2019), Y2000("Années 2000", 2000, 2009),
    Y1990("Années 1990", 1990, 1999), BEFORE_1990("Avant 1990", null, 1989),
}

data class LibraryState(
    val query: String = "",
    val sort: LibrarySort = LibrarySort.TITLE,
    val watch: WatchFilter? = null,
    val genre: String? = null,
    val period: Period? = null,
    val genres: List<GenreCount> = emptyList(),
    val items: List<AnimeSummary> = emptyList(),
    val total: Long = 0,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
) {
    val endReached get() = items.size >= total
    val filtered get() = watch != null || genre != null || period != null
    /** Libellé du genre choisi (valeur anglaise s'il n'est plus dans la liste). */
    val genreLabel get() = genre?.let { g -> genres.find { it.genre == g }?.label ?: g }
}

/**
 * Bibliothèque et recherche, comme le web : recherche (par le serveur, insensible aux accents), filtres vus / en cours /
 * non vus (S2), genre (S3), période (S2), tri titre / derniers ajouts / année (S6), pages de {@value PAGE_SIZE}.
 * Recherche, filtres et tri survivent à la rotation, à la navigation et à la mort du processus (SavedStateHandle) ;
 * un genre peut arriver de l'accueil (« Tout voir ») ou de la fiche (argument de navigation « genre »).
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
            watch = saved.get<String>("watch")?.let { s -> WatchFilter.entries.find { it.name == s } },
            genre = saved.get<String>("genre")?.takeIf { it.isNotBlank() && it.length <= 40 },
            period = saved.get<String>("period")?.let { s -> Period.entries.find { it.name == s } },
        ),
    )
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    private val queries = MutableStateFlow(_state.value.query)
    private var job: Job? = null

    init {
        reload()
        queries.drop(1).debounce(300).distinctUntilChanged().onEach { reload() }.launchIn(viewModelScope)
        viewModelScope.launch {
            runCatching { api.genres() }.getOrNull()?.let { g -> _state.update { it.copy(genres = g) } }
        }
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

    /** Toucher le filtre déjà choisi le retire. */
    fun onWatch(w: WatchFilter) {
        val next = if (_state.value.watch == w) null else w
        saved["watch"] = next?.name
        _state.update { it.copy(watch = next) }
        reload()
    }

    fun onGenre(genre: String?) {
        if (genre == _state.value.genre) return
        saved["genre"] = genre
        _state.update { it.copy(genre = genre) }
        reload()
    }

    fun onPeriod(period: Period?) {
        if (period == _state.value.period) return
        saved["period"] = period?.name
        _state.update { it.copy(period = period) }
        reload()
    }

    fun clearFilters() {
        saved["watch"] = null; saved["genre"] = null; saved["period"] = null
        _state.update { it.copy(watch = null, genre = null, period = null) }
        reload()
    }

    fun clearAll() {
        saved["q"] = ""
        queries.value = ""
        _state.update { it.copy(query = "") }
        clearFilters()
    }

    fun reload() {
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val page = fetch(_state.value, 0)
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
                val page = fetch(s, s.items.size / PAGE_SIZE)
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

    private suspend fun fetch(s: LibraryState, page: Int) = api.animes(
        s.sort.api, s.query, page, PAGE_SIZE, watch = s.watch?.api, genre = s.genre, yearFrom = s.period?.from, yearTo = s.period?.to,
    )

    private fun withImage(a: AnimeSummary) = a.copy(posterUrl = session.image(a.posterUrl))

    companion object {
        const val PAGE_SIZE = 60
    }
}
