package fr.plexwish.anime.feature.person

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.api.ApiException
import fr.plexwish.anime.data.api.PersonDetail
import fr.plexwish.anime.data.api.PersonRole
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.feature.image
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Un animé de la bibliothèque et les personnages que le comédien y joue (noms seulement). */
data class PersonAnime(val animeId: Long, val title: String, val year: Int?, val posterUrl: String?, val roles: List<PersonRole>)

data class PersonState(
    val loading: Boolean = true,
    val error: String? = null,
    /** 404 : le comédien ne joue dans aucun animé disponible de la bibliothèque. */
    val notFound: Boolean = false,
    val person: PersonDetail? = null,
    val animes: List<PersonAnime> = emptyList(),
)

/** Page d'un comédien : photo, noms, et les animés de la bibliothèque où il joue (jamais sa filmographie complète). */
class PersonViewModel(
    private val api: AnimeApi,
    private val session: SessionStore,
    saved: SavedStateHandle,
) : ViewModel() {

    val personId: String = checkNotNull(saved.get<String>("id")) { "identifiant de comédien manquant" }
    private val _state = MutableStateFlow(PersonState())
    val state: StateFlow<PersonState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null, notFound = false) }
        viewModelScope.launch {
            try {
                val p = api.person(personId)
                val person = p.copy(imageUrl = session.image(p.imageUrl))
                _state.update { it.copy(loading = false, person = person, animes = group(person.roles)) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message, notFound = e.status == 404) }
            }
        }
    }

    /** Un animé par carte, avec tous les personnages qu'il y joue (le serveur les renvoie groupés par animé). */
    private fun group(roles: List<PersonRole>): List<PersonAnime> {
        val out = mutableListOf<PersonAnime>()
        for (r in roles) {
            val last = out.lastOrNull()
            if (last != null && last.animeId == r.animeId) {
                out[out.size - 1] = last.copy(roles = last.roles + r)
            } else {
                out += PersonAnime(r.animeId, r.animeTitle, r.year, session.image(r.posterUrl), listOf(r))
            }
        }
        return out
    }
}
