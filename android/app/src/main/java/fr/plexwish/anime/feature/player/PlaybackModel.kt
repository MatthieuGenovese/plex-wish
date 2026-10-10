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
    /** Position dans les pistes du lecteur (groupe, piste) : sert à choisir la piste dans le panneau « Audio et sous-titres ». */
    val group: Int = -1,
    val index: Int = -1,
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

/**
 * Décodeurs FFmpeg logiciels (NextLib) : leur nom commence par « ffmpeg ». Les décodeurs du téléphone (matériel,
 * « c2.qti… », « c2.exynos… », ou logiciel Android « c2.android… ») sont le cas normal et ne sont pas signalés.
 */
fun isFfmpegDecoder(name: String?): Boolean = name?.startsWith("ffmpeg", ignoreCase = true) == true

/** Mention discrète dans le lecteur quand FFmpeg décode l'image et/ou le son ; {@code null} sinon. */
fun softwareDecodingLabel(decoders: Map<TrackType, String>): String? {
    val video = isFfmpegDecoder(decoders[TrackType.VIDEO])
    val audio = isFfmpegDecoder(decoders[TrackType.AUDIO])
    return when {
        video && audio -> "Décodage logiciel : image et son"
        video -> "Décodage logiciel : image"
        audio -> "Décodage logiciel : son"
        else -> null
    }
}

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

        /** Décodeur mis en service pour l'image ({@link TrackType#VIDEO}) ou le son ({@link TrackType#AUDIO}). */
        fun onDecoder(type: TrackType, name: String) {}
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

    /** En attente de données (mise en tampon). */
    val isBuffering: Boolean

    /** Position jusqu'où le média est chargé (ms) : si elle n'avance plus pendant la mise en tampon, la lecture est bloquée. */
    val bufferedPositionMs: Long

    /** Coupe (ou rétablit) le son : « Lire sans le son » quand l'audio bloque la lecture. Jamais mémorisé. */
    fun setAudioEnabled(enabled: Boolean)
    fun pause()
    fun release()

    /** Lecture demandée par l'utilisateur (après une pause). */
    fun play() {}

    /** Se placer à {@code positionMs} (barre de progression, ±10 s). */
    fun seekTo(positionMs: Long) {}

    /**
     * Choix de l'utilisateur dans « Audio et sous-titres » : cette piste (sa langue devient la préférence mémorisée),
     * ou, pour {@code null} en sous-titres, sous-titres désactivés.
     */
    fun selectTrack(type: TrackType, track: TrackInfo?) {}
}

/**
 * Lecture bloquée en mise en tampon : ExoPlayer attend des données qui ne débloquent jamais la lecture (cas réel :
 * AVI avec MP3 « octet par octet », dont Media3 calcule mal l'horodatage du son). Bloqué = en mise en tampon depuis
 * {@code windowMs} sans que la position chargée ait avancé de {@code minProgressMs} : une connexion lente, elle,
 * fait avancer la position chargée.
 */
class StallDetector(private val windowMs: Long = 20_000, private val minProgressMs: Long = 2_000) {
    private var since: Long = -1
    private var startBuffered: Long = 0

    /** Durée de la mise en tampon en cours (ms), 0 hors mise en tampon. */
    var bufferingMs: Long = 0
        private set

    /** Vrai quand la lecture est jugée bloquée. */
    fun update(now: Long, buffering: Boolean, bufferedPositionMs: Long): Boolean {
        if (!buffering) {
            since = -1
            bufferingMs = 0
            return false
        }
        if (since < 0 || bufferedPositionMs - startBuffered >= minProgressMs) {
            // Début de la mise en tampon, ou des données arrivent : on repart de maintenant.
            if (since < 0) bufferingMs = 0
            since = now
            startBuffered = bufferedPositionMs
        }
        bufferingMs = maxOf(bufferingMs, now - since)
        return now - since >= windowMs
    }

    fun reset() {
        since = -1
        bufferingMs = 0
    }
}
