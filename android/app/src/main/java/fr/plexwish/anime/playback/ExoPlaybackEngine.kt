package fr.plexwish.anime.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import fr.plexwish.anime.data.log.SafeLog
import fr.plexwish.anime.feature.player.PlaybackEngine
import fr.plexwish.anime.feature.player.PlaybackFailure
import fr.plexwish.anime.feature.player.TrackInfo
import fr.plexwish.anime.feature.player.TrackPrefs
import fr.plexwish.anime.feature.player.TrackPrefsStore
import fr.plexwish.anime.feature.player.TrackType
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import okhttp3.OkHttpClient

/**
 * ExoPlayer derrière {@link PlaybackEngine}. Flux lu par OkHttp **sans** l'intercepteur d'authentification : l'URL
 * signée suffit, aucun jeton n'est envoyé. Pistes : audio japonais et sous-titres français par défaut (préférences
 * mémorisées quand l'utilisateur change de piste dans le lecteur).
 */
@OptIn(UnstableApi::class)
class ExoPlaybackEngine(
    context: Context,
    streamClient: OkHttpClient,
    private val prefs: TrackPrefsStore,
) : PlaybackEngine {

    private var listener: PlaybackEngine.Listener? = null
    /** Vrai pendant que l'app applique elle-même les préférences (à ne pas prendre pour un choix de l'utilisateur). */
    private var applying = false
    private var userChangedTracks = false
    /** Vrai pendant {@link load} : le « play » qu'on y règle n'est pas une reprise demandée par l'utilisateur. */
    private var loading = false

    private val selector = DefaultTrackSelector(context)

    val player: ExoPlayer = ExoPlayer.Builder(context, renderersFactory(context))
        .setTrackSelector(selector)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(OkHttpDataSource.Factory(streamClient).setUserAgent("AnimeServer-Android"))
                .setLoadErrorHandlingPolicy(NoRetryOnClientError()),
        )
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    init {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(t: AnalyticsListener.EventTime, name: String, initializedMs: Long, durationMs: Long) {
                listener?.onDecoder(TrackType.VIDEO, name)
            }

            override fun onAudioDecoderInitialized(t: AnalyticsListener.EventTime, name: String, initializedMs: Long, durationMs: Long) {
                listener?.onDecoder(TrackType.AUDIO, name)
            }
        })
        applyPrefs(prefs.load())
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> listener?.onReady()
                    Player.STATE_ENDED -> listener?.onEnded()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener?.onPlayingChanged(isPlaying)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && !loading && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) listener?.onResumeRequested()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (fallBackToFfmpeg(error)) return
                listener?.onError(failureOf(error))
            }

            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                if (!applying) userChangedTracks = true
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (userChangedTracks) {
                    userChangedTracks = false
                    rememberChoice(tracks)
                }
                listener?.onTracks(tracksOf(tracks))
            }
        })
    }

    private fun applyPrefs(p: TrackPrefs) {
        applying = true
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(p.audio)
            .setPreferredTextLanguage(p.text)
            // Fansubs sans langue renseignée : on les affiche quand même s'il n'y a pas mieux.
            .setSelectUndeterminedTextLanguage(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, p.text == null)
            .build()
        applying = false
    }

    /** L'utilisateur a changé de piste (boutons du lecteur) : sa langue devient la préférence. */
    private fun rememberChoice(tracks: Tracks) {
        val textDisabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
        prefs.update(selectedLanguage(tracks, C.TRACK_TYPE_AUDIO), selectedLanguage(tracks, C.TRACK_TYPE_TEXT), textDisabled)
    }

    private fun selectedLanguage(tracks: Tracks, type: Int): String? = tracks.groups.filter { it.type == type }
        .firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }

    private fun tracksOf(tracks: Tracks): List<TrackInfo> = tracks.groups.withIndex().flatMap { (gi, g) ->
        val type = when (g.type) {
            C.TRACK_TYPE_VIDEO -> TrackType.VIDEO
            C.TRACK_TYPE_AUDIO -> TrackType.AUDIO
            C.TRACK_TYPE_TEXT -> TrackType.TEXT
            else -> TrackType.OTHER
        }
        (0 until g.length).map { i ->
            val f: Format = g.getTrackFormat(i)
            TrackInfo(type, f.sampleMimeType, f.codecs, f.language, f.label,
                supported = g.isTrackSupported(i, /* allowExceedsCapabilities = */ true), selected = g.isTrackSelected(i),
                group = gi, index = i)
        }
    }

    /** Renderers du téléphone écartés après un échec de décodage : pour la vie du lecteur, donc l'épisode en cours. */
    private val disabledRenderers = mutableSetOf<Int>()

    /**
     * Filet de sécurité : le décodeur du téléphone a accepté la piste puis échoue (il se dit capable à tort). On écarte
     * ce renderer et on relance à la même position : FFmpeg, ajouté après lui, prend la piste. Une seule fois par
     * renderer ; si FFmpeg échoue aussi, l'erreur remonte normalement.
     */
    private fun fallBackToFfmpeg(e: PlaybackException): Boolean {
        val x = e as? ExoPlaybackException ?: return false
        if (x.type != ExoPlaybackException.TYPE_RENDERER || e.errorCode !in DECODER_ERRORS) return false
        val index = x.rendererIndex
        if (index !in 0 until player.rendererCount || index in disabledRenderers) return false
        val type = player.getRendererType(index)
        if (type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_AUDIO) return false
        val failed = player.getRenderer(index)
        if (failed.name.startsWith("Ffmpeg")) return false
        val hasFfmpeg = (0 until player.rendererCount).any { player.getRendererType(it) == type && player.getRenderer(it).name.startsWith("Ffmpeg") }
        if (!hasFfmpeg) return false
        disabledRenderers += index
        SafeLog.w(TAG, "Décodeur du téléphone en échec (${e.errorCodeName}, ${failed.name}) : repli sur FFmpeg")
        applying = true // pas un choix de piste à mémoriser
        selector.parameters = selector.buildUponParameters().setRendererDisabled(index, true).build()
        applying = false
        player.prepare()
        return true
    }

    /** Erreur → modèle sans secret : code, statut HTTP, causes nettoyées, piste en cause pour un décodeur. */
    private fun failureOf(e: PlaybackException): PlaybackFailure {
        var http: Int? = null
        var t: Throwable? = e
        while (t != null && http == null) {
            if (t is HttpDataSource.InvalidResponseCodeException) http = t.responseCode
            t = t.cause.takeIf { it !== t }
        }
        val format = (e as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat
        return PlaybackFailure(
            errorCode = e.errorCode, errorCodeName = e.errorCodeName, httpStatus = http,
            cause = SafeLog.describe(e.cause), formatMime = format?.sampleMimeType, formatCodecs = format?.codecs,
        )
    }

    override fun setListener(listener: PlaybackEngine.Listener?) {
        this.listener = listener
    }

    override fun load(url: String, startMs: Long, playWhenReady: Boolean) {
        loading = true
        try {
            player.setMediaItem(MediaItem.fromUri(url), startMs)
            player.playWhenReady = playWhenReady
            player.prepare()
        } finally {
            loading = false
        }
    }

    override val positionMs: Long get() = player.currentPosition
    override val durationMs: Long get() = player.duration.takeIf { it != C.TIME_UNSET } ?: -1
    override val isPlaying: Boolean get() = player.isPlaying
    override val isBuffering: Boolean get() = player.playbackState == Player.STATE_BUFFERING
    override val bufferedPositionMs: Long get() = player.bufferedPosition

    override fun setAudioEnabled(enabled: Boolean) {
        applying = true // pas un choix de piste à mémoriser
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !enabled).build()
        applying = false
    }
    override val playWhenReady: Boolean get() = player.playWhenReady
    override fun pause() = player.pause()
    override fun play() = player.play()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))

    /** Choix fait dans le panneau : hors de {@code applying}, il est donc mémorisé comme préférence (rememberChoice). */
    override fun selectTrack(type: TrackType, track: TrackInfo?) {
        val c = when (type) {
            TrackType.AUDIO -> C.TRACK_TYPE_AUDIO
            TrackType.TEXT -> C.TRACK_TYPE_TEXT
            else -> return
        }
        val b = player.trackSelectionParameters.buildUpon()
        if (track == null) {
            b.setTrackTypeDisabled(c, true)
        } else {
            val group = player.currentTracks.groups.getOrNull(track.group) ?: return
            if (track.index !in 0 until group.length) return
            b.setTrackTypeDisabled(c, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.index))
        }
        player.trackSelectionParameters = b.build()
    }
    override fun release() = player.release()

    companion object {
        private const val TAG = "Lecteur"

        /** Erreurs de décodeur qui justifient un repli sur FFmpeg (initialisation, format, capacités, décodage). */
        private val DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )

        /**
         * Décodeurs du téléphone d'abord, FFmpeg (NextLib) en repli : mode ON, pas PREFER. Les renderers FFmpeg sont
         * ajoutés APRÈS ceux de MediaCodec ; le sélecteur de pistes prend le renderer le mieux noté pour chaque piste,
         * et le premier en cas d'égalité. Un H.264 8 bits (MediaCodec : « pris en charge ») reste donc sur le matériel ;
         * un H.264 10 bits (MediaCodec : « dépasse les capacités ») ou un son DTS/TrueHD (aucun décodeur du téléphone)
         * passe à FFmpeg. Ordre vérifié par RenderersOrderTest.
         */
        fun renderersFactory(context: Context): RenderersFactory = NextRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    }

    /**
     * 4xx (403 lien expiré, 404 fichier absent…) : inutile d'insister, l'erreur remonte tout de suite et le
     * ViewModel redemande une URL. Coupures réseau et 5xx : nouveaux essais d'ExoPlayer comme d'habitude.
     */
    private class NoRetryOnClientError : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val e = info.exception
            if (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in 400..499) return C.TIME_UNSET
            return super.getRetryDelayMsFor(info)
        }
    }
}
