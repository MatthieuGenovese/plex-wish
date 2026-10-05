package fr.plexwish.anime.feature.player

/** Pistes et erreurs du lecteur, sans type Media3 : la logique (diagnostic, reprise) se teste sur la JVM. */
enum class TrackType { VIDEO, AUDIO, TEXT, OTHER }

data class TrackInfo(
    val type: TrackType,
    /** Type MIME de l'échantillon (« video/hevc », « text/x-ssa »…). */
    val mimeType: String?,
    /** Chaîne de codec (« hvc1.2.4.L120.B0 »…), peut être vide. */
    val codecs: String? = null,
    val language: String? = null,
    val label: String? = null,
    /** Le téléphone sait le décoder (ou le dépasse « peut-être » : compté comme lisible). */
    val supported: Boolean = true,
    val selected: Boolean = false,
)

/**
 * Erreur du lecteur, déjà nettoyée : {@code cause} ne contient ni URL complète ni jeton (SafeLog.describe).
 * {@code formatMime} / {@code formatCodecs} : piste en cause pour une erreur de décodeur.
 */
data class PlaybackFailure(
    val errorCode: Int,
    val errorCodeName: String,
    val httpStatus: Int? = null,
    val cause: String = "",
    val formatMime: String? = null,
    val formatCodecs: String? = null,
)

/** Moteur de lecture (ExoPlayer dans l'app, faux moteur dans les tests). */
interface PlaybackEngine {
    interface Listener {
        fun onReady() {}
        fun onPlayingChanged(playing: Boolean) {}

        /** L'utilisateur relance la lecture (après une pause). */
        fun onResumeRequested() {}
        fun onEnded() {}
        fun onError(failure: PlaybackFailure) {}
        fun onTracks(tracks: List<TrackInfo>) {}
    }

    fun setListener(listener: Listener?)

    /** Charge l'URL (signée) et se place à {@code startMs}. L'URL n'est jamais journalisée. */
    fun load(url: String, startMs: Long, playWhenReady: Boolean)

    /** Position courante (ms), y compris après un seek pendant la pause ou après une erreur. */
    val positionMs: Long

    /** Durée (ms), 0 ou moins si inconnue. */
    val durationMs: Long
    val isPlaying: Boolean
    val playWhenReady: Boolean
    fun pause()
    fun release()
}
