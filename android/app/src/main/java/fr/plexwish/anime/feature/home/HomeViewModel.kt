package fr.plexwish.anime.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.api.AnimeSummary
import fr.plexwish.anime.data.api.ApiException
import fr.plexwish.anime.data.api.ContinueWatching
import fr.plexwish.anime.data.api.GenreCount
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.feature.image
import fr.plexwish.anime.feature.player.ProgressBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Une rangée de genre de l'accueil (« Comédie », « Action »…). */
data class GenreRow(val genre: GenreCount, val items: List<AnimeSummary>)

data class HomeState(
    val loading: Boolean = true,
    val error: String? = null,
    /** « Continuer à regarder » (S1) : le premier va dans le bandeau, les suivants dans la rangée. */
    val continueWatching: List<ContinueWatching> = emptyList(),
    val recent: List<AnimeSummary> = emptyList(),
    /** Nombre d'animés de la bibliothèque (0 : bibliothèque vide). */
    val total: Long = 0,
    val genres: List<GenreRow> = emptyList(),
    /** « À découvrir » : une page de la bibliothèque tirée au hasard. */
    val discover: List<AnimeSummary> = emptyList(),
) {
    val empty get() = !loading && error == null && total == 0L
}

/**
 * Accueil, comme le web : bandeau « À reprendre » / « À suivre » (sinon le dernier ajout), rangées « Continuer à
 * regarder », « Récemment ajoutés », trois genres (jamais Hentai ni Ecchi) et « À découvrir ». Une rangée en erreur
 * est masquée ; seuls « Continuer » et « Récents » sont indispensables.
 */
class HomeViewModel(
    private val api: AnimeApi,
    private val session: SessionStore,
    progressBus: ProgressBus = ProgressBus(),
    private val random: Random = Random.Default,
) : ViewModel() {

    private var refreshJob: Job? = null
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
                // coroutineScope : l'échec d'un appel indispensable remonte ici (attrapé ci-dessous).
                val s = coroutineScope {
                    val cw = async { api.continueWatching(ROW + 1) }
                    val recent = async { api.animes("recent", null, 0, ROW) }
                    val genres = async { runCatching { api.genres() }.getOrDefault(emptyList()) }
                    val r = recent.await()
                    val top = genres.await().filter { it.genre !in HIDDEN_GENRES && it.animeCount >= 4 }
                        .sortedByDescending { it.animeCount }.take(3)
                    val rows = top.map { g -> async { runCatching { GenreRow(g, api.animes("recent", null, 0, ROW, genre = g.genre).items) }.getOrNull() } }
                    val pages = ((r.total + ROW - 1) / ROW).toInt()
                    val discover = async {
                        if (r.total > ROW) runCatching { api.animes("title", null, random.nextInt(pages), ROW).items }.getOrDefault(emptyList())
                        else emptyList()
                    }
                    HomeState(
                        loading = false,
                        continueWatching = cw.await().map { it.copy(posterUrl = session.image(it.posterUrl)) },
                        recent = r.items.map(::withImage),
                        total = r.total,
                        genres = rows.awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
                            .map { it.copy(items = it.items.map(::withImage)) },
                        discover = discover.await().map(::withImage),
                    )
                }
                _state.value = s
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
                val cw = api.continueWatching(ROW + 1).map { it.copy(posterUrl = session.image(it.posterUrl)) }
                _state.update { it.copy(continueWatching = cw) }
            } catch (_: ApiException) {
            }
        }
    }

    private fun withImage(a: AnimeSummary) = a.copy(posterUrl = session.image(a.posterUrl))

    companion object {
        /** Cartes par rangée : assez pour défiler, pas plus (la rangée « Tout voir » mène à la suite). */
        const val ROW = 12
        /** Genres jamais proposés en rangée d'accueil (contenus pour adultes). */
        val HIDDEN_GENRES = setOf("Hentai", "Ecchi")
    }
}
