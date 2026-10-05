package fr.plexwish.anime.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.api.ApiException
import fr.plexwish.anime.data.api.EpisodeDetail
import fr.plexwish.anime.data.api.SignedStream
import fr.plexwish.anime.data.log.SafeLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlayerPhase { LOADING, PLAYING, RECONNECTING, ERROR, ENDED }

data class PlayerState(
    val phase: PlayerPhase = PlayerPhase.LOADING,
    val title: String = "",
    val subtitle: String? = null,
    /** Erreur bloquante (message + détails techniques sans secret). */
    val error: Diagnosis? = null,
    /** Avertissements (son, sous-titres) : la lecture continue. */
    val warnings: List<Diagnosis> = emptyList(),
    /** Écran « Détails » ouvert sur ce diagnostic. */
    val details: Diagnosis? = null,
)

/**
 * Lecteur d'un épisode : URL signée, reprise à la position enregistrée, reprise automatique après coupure (403 →
 * nouvelle URL, réseau → nouvel essai), diagnostic lisible. L'URL signée ne sort jamais de cette classe et du moteur :
 * ni état, ni message, ni journal.
 */
class PlayerViewModel(
    private val api: AnimeApi,
    engineFactory: () -> PlaybackEngine,
    private val saved: SavedStateHandle,
    private val policy: RecoveryPolicy = RecoveryPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel(), PlaybackEngine.Listener {

    val episodeId: Long = checkNotNull(saved.get<Long>("id")) { "identifiant d'épisode manquant" }
    val engine: PlaybackEngine = engineFactory()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var episode: EpisodeDetail? = null
    private var stream: SignedStream? = null
    private var tracks: List<TrackInfo> = emptyList()
    private var warned = false
    private var networkAttempts = 0
    private var urlRefreshes = 0
    private var recovery: Job? = null

    /** Dernière position sûre (ms) : sert à repartir au bon endroit après une erreur. */
    private var lastPositionMs: Long
        get() = saved.get<Long>(POSITION) ?: -1
        set(v) {
            saved[POSITION] = v
        }

    init {
        engine.setListener(this)
        start()
    }

    private fun start() {
        _state.update { it.copy(phase = PlayerPhase.LOADING, error = null) }
        viewModelScope.launch {
            try {
                val e = api.episode(episodeId)
                episode = e
                _state.update {
                    it.copy(
                        title = e.title?.let { t -> "Épisode ${e.episodeNumber} · $t" } ?: "Épisode ${e.episodeNumber}",
                        subtitle = e.animeTitle + if (e.seasonNumber > 0) " · Saison ${e.seasonNumber}" else " · Spéciaux",
                    )
                }
                val startMs = if (lastPositionMs >= 0) lastPositionMs else resumeFor(e)
                lastPositionMs = startMs
                load(fresh = true, positionMs = startMs, play = true)
            } catch (ex: ApiException) {
                fail(Diagnosis(ex.message, listOf("Étape" to "préparation de la lecture", "Erreur" to (ex.code ?: "HTTP ${ex.status}"))))
            }
        }
    }

    /** Position enregistrée sur le serveur ; sans réponse, on part du début plutôt que de bloquer. */
    private suspend fun resumeFor(e: EpisodeDetail): Long = try {
        ResumePoint.startMs(api.progress(e.animeId).find { it.episodeId == e.id })
    } catch (_: ApiException) {
        0
    }

    private suspend fun load(fresh: Boolean, positionMs: Long, play: Boolean) {
        if (fresh || stream == null) stream = api.streamUrl(episodeId)
        engine.load(stream!!.url, positionMs.coerceAtLeast(0), play)
    }

    private fun urlExpiresSoon(): Boolean {
        val exp = stream?.expiresAtMs ?: return false
        return exp - clock() < RecoveryPolicy.EXPIRY_MARGIN_MS
    }

    /** Position à reprendre : celle du moteur (seek pendant la pause compris), sinon la dernière connue. */
    private fun currentPosition(): Long = engine.positionMs.takeIf { it > 0 } ?: lastPositionMs.coerceAtLeast(0)

    // --- Événements du moteur ----------------------------------------------------------------------------------

    override fun onReady() {
        if (_state.value.phase == PlayerPhase.RECONNECTING || _state.value.phase == PlayerPhase.LOADING) {
            _state.update { it.copy(phase = PlayerPhase.PLAYING) }
        }
    }

    override fun onPlayingChanged(playing: Boolean) {
        if (playing) {
            networkAttempts = 0
            urlRefreshes = 0
            _state.update { it.copy(phase = PlayerPhase.PLAYING, error = null) }
        }
        lastPositionMs = currentPosition()
    }

    override fun onEnded() {
        lastPositionMs = engine.durationMs.coerceAtLeast(0)
        _state.update { it.copy(phase = PlayerPhase.ENDED) }
    }

    override fun onTracks(tracks: List<TrackInfo>) {
        this.tracks = tracks
        if (tracks.isEmpty()) return
        Diagnostics.undecodableVideo(tracks, episode?.container)?.let { d ->
            engine.pause()
            SafeLog.w(TAG, "Vidéo non décodable : ${d.details.joinToString { "${it.first}=${it.second}" }}")
            fail(d)
            return
        }
        if (!warned) {
            warned = true
            val w = Diagnostics.warnings(tracks, episode?.container).map { it.second }
            if (w.isNotEmpty()) _state.update { it.copy(warnings = w) }
        }
    }

    override fun onError(failure: PlaybackFailure) {
        val position = currentPosition()
        lastPositionMs = position
        val kind = Diagnostics.kind(failure)
        SafeLog.w(TAG, "Erreur de lecture ${failure.errorCodeName} (HTTP ${failure.httpStatus}), $kind : ${failure.cause}")
        val decision = policy.decide(kind, networkAttempts, urlRefreshes, urlExpiresSoon())
        val diagnosis = Diagnostics.failure(failure, tracks, episode?.container)
        when (decision) {
            Recovery.Fail -> fail(diagnosis)
            Recovery.RefreshUrl -> {
                urlRefreshes++
                recover(0, fresh = true, position)
            }
            is Recovery.Retry -> {
                networkAttempts++
                recover(decision.delayMs, decision.refreshUrl, position)
            }
        }
    }

    private fun recover(delayMs: Long, fresh: Boolean, positionMs: Long) {
        val play = engine.playWhenReady
        _state.update { it.copy(phase = PlayerPhase.RECONNECTING) }
        recovery?.cancel()
        recovery = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            try {
                load(fresh, positionMs, play)
            } catch (ex: ApiException) {
                // Pas de nouvelle URL (réseau toujours coupé…) : même règle que pour une erreur du lecteur.
                if (ex.isNetwork && networkAttempts < MAX_URL_ATTEMPTS) {
                    networkAttempts++
                    recover(delayMs.coerceAtLeast(1_000) * 2, fresh, positionMs)
                } else {
                    fail(Diagnosis(ex.message, listOf("Étape" to "nouveau lien de lecture", "Erreur" to (ex.code ?: "HTTP ${ex.status}"))))
                }
            }
        }
    }

    private fun fail(d: Diagnosis) {
        _state.update { it.copy(phase = PlayerPhase.ERROR, error = d) }
    }

    // --- Actions de l'écran ------------------------------------------------------------------------------------------

    /** « Réessayer » après une erreur : nouvelle URL, à la dernière position connue. */
    fun retry() {
        networkAttempts = 0
        urlRefreshes = 0
        if (episode == null) {
            start()
            return
        }
        recover(0, fresh = true, lastPositionMs.coerceAtLeast(0))
    }

    /**
     * La lecture reprend après une pause : si l'URL a expiré entre-temps (pause de plusieurs heures), on en demande une
     * nouvelle avant que le serveur ne réponde 403. La connexion fermée après ~60 s, elle, est rouverte par ExoPlayer.
     */
    override fun onResumeRequested() {
        if (urlExpiresSoon()) recover(0, fresh = true, currentPosition())
    }

    fun showDetails(d: Diagnosis?) = _state.update { it.copy(details = d) }

    fun dismissWarnings() = _state.update { it.copy(warnings = emptyList()) }

    /** L'app passe en arrière-plan : pause (pas de lecture en fond). */
    fun onBackground() {
        lastPositionMs = currentPosition()
        engine.pause()
    }

    override fun onCleared() {
        engine.setListener(null)
        engine.release()
    }

    companion object {
        private const val TAG = "Player"
        private const val POSITION = "positionMs"
        private const val MAX_URL_ATTEMPTS = 5
    }
}
