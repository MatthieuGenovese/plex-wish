package fr.plexwish.anime.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.api.AnimeDetail
import fr.plexwish.anime.data.api.ApiException
import fr.plexwish.anime.data.api.CastEntry
import fr.plexwish.anime.data.api.EpisodeSummary
import fr.plexwish.anime.data.api.ProgressDto
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.feature.image
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Libellés des rôles, comme sur le web. */
val ROLE_LABELS = mapOf("MAIN" to "Principal", "SUPPORTING" to "Secondaire")

/** Tranche d'épisodes (au-delà de {@value CHUNK} épisodes, comme le web : One Piece en compte plus de 1 000). */
data class Chunk(val index: Int, val label: String)

data class DetailState(
    val loading: Boolean = true,
    val error: String? = null,
    /** 404 : l'animé n'existe pas ou n'a plus d'épisode disponible. */
    val notFound: Boolean = false,
    /** Affiches déjà réduites à notre serveur (null sinon : visuel de remplacement). */
    val anime: AnimeDetail? = null,
    val seasonId: Long? = null,
    val episodes: List<EpisodeSummary> = emptyList(),
    val episodesLoading: Boolean = false,
    val episodesError: String? = null,
    val chunk: Int = 0,
    /** Progression de l'utilisateur, par épisode. */
    val progress: Map<Long, ProgressDto> = emptyMap(),
    /** Photos des comédiens réduites à notre serveur. Vide si pas de distribution ou en cas d'erreur (section masquée). */
    val cast: List<CastEntry> = emptyList(),
    val castSource: String? = null,
) {
    val season get() = anime?.seasons?.find { it.id == seasonId }
    val chunks: List<Chunk>
        get() = if (episodes.size <= CHUNK) emptyList() else episodes.indices.step(CHUNK).mapIndexed { i, start ->
            val end = minOf(start + CHUNK, episodes.size) - 1
            Chunk(i, "${episodes[start].episodeNumber}–${episodes[end].episodeNumber}")
        }
    val visibleEpisodes: List<EpisodeSummary>
        get() = if (episodes.size <= CHUNK) episodes else episodes.drop(chunk * CHUNK).take(CHUNK)

    /** Titres secondaires (français, alternatif) distincts du titre. */
    val subtitle: String?
        get() {
            val a = anime ?: return null
            val seen = mutableSetOf(a.title.lowercase())
            return listOfNotNull(a.frenchTitle, a.alternativeTitle).filter { seen.add(it.lowercase()) }
                .joinToString(" · ").ifEmpty { null }
        }

    companion object {
        const val CHUNK = 100
    }
}

/**
 * Fiche d'un animé : détails, saisons (la saison choisie survit à la rotation et à la mort du processus), épisodes
 * par tranches, progression de l'utilisateur et distribution (comédiens japonais). La distribution est chargée à part :
 * son échec n'empêche pas d'utiliser la fiche (comme sur le web).
 */
class AnimeDetailViewModel(
    private val api: AnimeApi,
    private val session: SessionStore,
    private val saved: SavedStateHandle,
) : ViewModel() {

    val animeId: Long = checkNotNull(saved.get<Long>("id")) { "identifiant d'animé manquant" }
    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()
    private var episodesJob: Job? = null

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null, notFound = false) }
        viewModelScope.launch {
            try {
                val a = api.anime(animeId)
                val anime = a.copy(posterUrl = session.image(a.posterUrl), posterLargeUrl = session.image(a.posterLargeUrl))
                val wanted = saved.get<Long>("season")
                val season = anime.seasons.find { it.id == wanted } ?: anime.seasons.firstOrNull()
                _state.update { it.copy(loading = false, anime = anime, seasonId = season?.id) }
                season?.let { loadEpisodes(it.id) }
                loadCast()
                refreshProgress()
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message, notFound = e.status == 404) }
            }
        }
    }

    fun selectSeason(id: Long) {
        if (id == _state.value.seasonId || _state.value.anime?.seasons?.none { it.id == id } != false) return
        saved["season"] = id
        _state.update { it.copy(seasonId = id, chunk = 0) }
        loadEpisodes(id)
    }

    fun selectChunk(index: Int) {
        if (index in _state.value.chunks.indices) _state.update { it.copy(chunk = index) }
    }

    fun retryEpisodes() {
        _state.value.seasonId?.let(::loadEpisodes)
    }

    /** Relu au retour sur la fiche (après le lecteur, au bloc suivant). Une erreur ici n'est pas affichée. */
    fun refreshProgress() {
        viewModelScope.launch {
            try {
                val list = api.progress(animeId)
                _state.update { s -> s.copy(progress = list.associateBy { it.episodeId }) }
            } catch (_: ApiException) {
                // Sans progression, la fiche reste utilisable.
            }
        }
    }

    private fun loadEpisodes(seasonId: Long) {
        episodesJob?.cancel()
        _state.update { it.copy(episodes = emptyList(), episodesLoading = true, episodesError = null) }
        episodesJob = viewModelScope.launch {
            try {
                val list = api.episodes(seasonId)
                _state.update { if (it.seasonId == seasonId) it.copy(episodes = list, episodesLoading = false) else it }
            } catch (e: ApiException) {
                _state.update { if (it.seasonId == seasonId) it.copy(episodesLoading = false, episodesError = e.message) else it }
            }
        }
    }

    private suspend fun loadCast() {
        try {
            val c = api.cast(animeId)
            val items = c.items.map { e -> e.copy(person = e.person?.let { p -> p.copy(imageUrl = session.image(p.imageUrl)) }) }
            _state.update { it.copy(cast = items, castSource = c.source) }
        } catch (_: ApiException) {
            // Comme le web : pas de section en cas d'erreur, la fiche reste utilisable.
        }
    }
}
