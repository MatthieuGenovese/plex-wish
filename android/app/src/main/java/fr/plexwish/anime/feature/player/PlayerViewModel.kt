package fr.plexwish.anime.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.api.ApiException
import fr.plexwish.anime.data.api.EpisodeDetail
import fr.plexwish.anime.data.api.SignedStream
import fr.plexwish.anime.data.api.StreamAnswer
import fr.plexwish.anime.data.log.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlayerPhase { LOADING, PREPARING, PLAYING, RECONNECTING, ERROR, ENDED }

/** Préparation sur le serveur (conversion pour Android) : place dans la file, avancement, attente estimée. */
data class PreparingInfo(val position: Int, val progress: Double?, val estimatedSeconds: Long)

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
    /** Lecture bloquée en mise en tampon, son actif : proposer « Lire sans le son ». */
    val canPlayWithoutSound: Boolean = false,
    /** Son coupé à la demande (« Lire sans le son »). */
    val soundOff: Boolean = false,
    /** Phase PREPARING : l'épisode est converti pour Android sur le serveur. */
    val preparing: PreparingInfo? = null,
    /** Pistes du fichier (panneau « Audio et sous-titres »). */
    val tracks: List<TrackInfo> = emptyList(),
) {
    val audioTracks get() = tracks.filter { it.type == TrackType.AUDIO && it.supported }
    val textTracks get() = tracks.filter { it.type == TrackType.TEXT && it.supported }
}

/**
 * Lecteur d'un épisode : URL signée, reprise à la position enregistrée, reprise automatique après coupure (403 →
 * nouvelle URL, réseau → nouvel essai), diagnostic lisible. L'URL signée ne sort jamais de cette classe et du moteur :
 * ni état, ni message, ni journal.
 */
class PlayerViewModel(
    private val api: AnimeApi,
    engineFactory: () -> PlaybackEngine,
    private val saved: SavedStateHandle,
    /** Portée de l'app : l'envoi de la progression à la sortie survit au ViewModel. */
    progressScope: CoroutineScope,
    private val policy: RecoveryPolicy = RecoveryPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    progressIntervalMs: Long = 10_000,
    /** Unité du délai entre deux demandes pendant la préparation (1 s ; raccourcie dans les tests). */
    private val preparingDelayUnitMs: Long = 1_000,
    /** Positions enregistrées, pour la fiche et l'accueil. */
    private val progressBus: ProgressBus? = null,
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
    private val stall = StallDetector()
    private val progress = ProgressReporter(
        send = { pos, dur -> api.saveProgress(episodeId, pos, dur) },
        scope = progressScope, clock = clock, intervalMs = progressIntervalMs,
        onSaved = { progressBus?.saved(ProgressSaved(episode?.animeId, episodeId, it.positionSeconds, it.durationSeconds)) },
    )

    /** Dernière position sûre (ms) : sert à repartir au bon endroit après une erreur. */
    private var lastPositionMs: Long
        get() = saved.get<Long>(POSITION) ?: -1
        set(v) {
            saved[POSITION] = v
        }

    init {
        engine.setListener(this)
        start()
        // Chaque seconde pendant la lecture : position gardée (mort du processus) et progression envoyée toutes les ~10 s.
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                if (engine.isPlaying && _state.value.phase == PlayerPhase.PLAYING) {
                    lastPositionMs = engine.positionMs
                    progress.tick(engine.positionMs, durationMs())
                }
                checkStall()
            }
        }
    }

    /** Mise en tampon sans progrès (au démarrage ou en cours de lecture) : on arrête et on explique. */
    private fun checkStall() {
        val phase = _state.value.phase
        val watching = (phase == PlayerPhase.LOADING || phase == PlayerPhase.PLAYING) && engine.playWhenReady
        val stalled = stall.update(clock(), watching && engine.isBuffering, engine.bufferedPositionMs)
        if (stalled) {
            val d = Diagnostics.stalled(tracks, episode?.container, stall.bufferingMs, engine.bufferedPositionMs, currentPosition())
            SafeLog.w(TAG, "Lecture bloquée en mise en tampon : ${d.details.joinToString { "${it.first}=${it.second}" }}")
            lastPositionMs = currentPosition()
            stall.reset()
            engine.pause()
            fail(d, stalled = true)
        }
    }

    /** Durée : celle du fichier (lecteur), sinon celle connue du serveur. */
    private fun durationMs(): Long = engine.durationMs.takeIf { it > 0 } ?: ((episode?.durationSeconds ?: 0) * 1000L)

    /** Envoi immédiat, seulement dans un état sûr (pas pendant une erreur ou une reconnexion : position douteuse). */
    private fun flushProgress(positionMs: Long = engine.positionMs) {
        if (_state.value.phase == PlayerPhase.PLAYING || _state.value.phase == PlayerPhase.ENDED) progress.flush(positionMs, durationMs())
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
        if (fresh || stream == null) stream = obtainStream()
        _state.update { it.copy(preparing = null) }
        engine.load(stream!!.url, positionMs.coerceAtLeast(0), play)
    }

    /**
     * Lien de lecture ; si le serveur prépare l'épisode (AVI, OGM convertis pour Android), écran « Préparation de
     * l'épisode… » et nouvelle demande après le délai indiqué, jusqu'à ce que la copie soit prête. Annulé en quittant
     * le lecteur (le ViewModel disparaît avec ses coroutines).
     */
    private suspend fun obtainStream(): SignedStream {
        while (true) {
            when (val a = api.stream(episodeId)) {
                is StreamAnswer.Ready -> return a.stream
                is StreamAnswer.Preparing -> {
                    _state.update { it.copy(phase = PlayerPhase.PREPARING, preparing = PreparingInfo(a.position, a.progress, a.estimatedSeconds)) }
                    delay(a.retryAfterSeconds * preparingDelayUnitMs)
                }
            }
        }
    }

    private fun urlExpiresSoon(): Boolean {
        val exp = stream?.expiresAtMs ?: return false
        return exp - clock() < RecoveryPolicy.EXPIRY_MARGIN_MS
    }

    /** Position à reprendre : celle du moteur (seek pendant la pause compris), sinon la dernière connue. */
    private fun currentPosition(): Long = engine.positionMs.takeIf { it > 0 } ?: lastPositionMs.coerceAtLeast(0)

    // --- Événements du moteur ----------------------------------------------------------------------------------

    override fun onReady() {
        if (_state.value.phase == PlayerPhase.RECONNECTING || _state.value.phase == PlayerPhase.LOADING ||
            _state.value.phase == PlayerPhase.PREPARING) {
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
        if (!playing) flushProgress() // pause (ou attente de données : dédoublonné si la position n'a pas bougé)
    }

    override fun onEnded() {
        val end = durationMs()
        lastPositionMs = end.coerceAtLeast(0)
        _state.update { it.copy(phase = PlayerPhase.ENDED) }
        flushProgress(end)
    }

    override fun onTracks(tracks: List<TrackInfo>) {
        this.tracks = tracks
        _state.update { it.copy(tracks = tracks) }
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
        val diagnosis = if (kind == FailureKind.STALLED) {
            val d = Diagnostics.failure(failure, tracks, episode?.container)
            d.copy(details = d.details + ("Mise en tampon" to "${stall.bufferingMs / 1000} s sans démarrer") +
                ("Position" to "${position / 1000} s, chargé jusqu'à ${engine.bufferedPositionMs / 1000} s"))
        } else {
            Diagnostics.failure(failure, tracks, episode?.container)
        }
        when (decision) {
            Recovery.Fail -> fail(diagnosis, stalled = kind == FailureKind.STALLED)
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

    private fun recover(delayMs: Long, fresh: Boolean, positionMs: Long, play: Boolean = engine.playWhenReady) {
        _state.update { it.copy(phase = PlayerPhase.RECONNECTING, canPlayWithoutSound = false) }
        stall.reset()
        recovery?.cancel()
        recovery = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            try {
                load(fresh, positionMs, play)
            } catch (ex: ApiException) {
                // Pas de nouvelle URL (réseau toujours coupé…) : même règle que pour une erreur du lecteur.
                if (ex.isNetwork && networkAttempts < MAX_URL_ATTEMPTS) {
                    networkAttempts++
                    recover(delayMs.coerceAtLeast(1_000) * 2, fresh, positionMs, play)
                } else {
                    fail(Diagnosis(ex.message, listOf("Étape" to "nouveau lien de lecture", "Erreur" to (ex.code ?: "HTTP ${ex.status}"))))
                }
            }
        }
    }

    private fun fail(d: Diagnosis, stalled: Boolean = false) {
        _state.update { it.copy(phase = PlayerPhase.ERROR, error = d, canPlayWithoutSound = stalled && !it.soundOff) }
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
        recover(0, fresh = true, lastPositionMs.coerceAtLeast(0), play = true)
    }

    /**
     * La lecture reprend après une pause : si l'URL a expiré entre-temps (pause de plusieurs heures), on en demande une
     * nouvelle avant que le serveur ne réponde 403. La connexion fermée après ~60 s, elle, est rouverte par ExoPlayer.
     */
    override fun onResumeRequested() {
        if (urlExpiresSoon()) recover(0, fresh = true, currentPosition())
    }

    /**
     * « Lire sans le son » : la piste audio est coupée pour cet épisode (pas mémorisé), puis rechargement à la même
     * position. Débloque les AVI dont Media3 horodate mal le son.
     */
    fun playWithoutSound() {
        engine.setAudioEnabled(false)
        _state.update { it.copy(soundOff = true) }
        networkAttempts = 0
        urlRefreshes = 0
        recover(0, fresh = urlExpiresSoon(), lastPositionMs.coerceAtLeast(0), play = true)
    }

    fun showDetails(d: Diagnosis?) = _state.update { it.copy(details = d) }

    /** Lecture / pause (surcouche, touche Lecture de la télécommande). */
    fun togglePlay() {
        if (engine.isPlaying || (engine.playWhenReady && _state.value.phase == PlayerPhase.PLAYING)) engine.pause() else engine.play()
    }

    /** Recul / avance (±10 s) ou barre de progression : bornés au fichier. */
    fun seekTo(positionMs: Long) {
        val d = durationMs()
        val target = if (d > 0) positionMs.coerceIn(0, d) else positionMs.coerceAtLeast(0)
        engine.seekTo(target)
        lastPositionMs = target
    }

    fun seekBy(deltaMs: Long) = seekTo(engine.positionMs + deltaMs)

    /** Panneau « Audio et sous-titres » : piste choisie (mémorisée pour les épisodes suivants), ou sous-titres désactivés. */
    fun selectTrack(type: TrackType, track: TrackInfo?) = engine.selectTrack(type, track)

    fun dismissWarnings() = _state.update { it.copy(warnings = emptyList()) }

    /** L'app passe en arrière-plan : position envoyée, puis pause (pas de lecture en fond). */
    fun onBackground() {
        lastPositionMs = currentPosition()
        flushProgress()
        engine.pause()
    }

    override fun onCleared() {
        flushProgress() // sortie du lecteur : envoyé par la portée de l'app, même ViewModel détruit
        engine.setListener(null)
        engine.release()
    }

    companion object {
        private const val TAG = "Player"
        private const val POSITION = "positionMs"
        private const val MAX_URL_ATTEMPTS = 5
    }
}
