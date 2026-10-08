package fr.plexwish.anime

import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.auth.AuthInterceptor
import fr.plexwish.anime.data.auth.AuthRepository
import fr.plexwish.anime.data.auth.KeyValueStore
import fr.plexwish.anime.data.auth.KeystoreTokenCipher
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.data.auth.TokenAuthenticator
import fr.plexwish.anime.data.auth.TokenRefresher
import fr.plexwish.anime.feature.player.PlaybackEngine
import fr.plexwish.anime.feature.player.TrackPrefsStore
import fr.plexwish.anime.playback.ExoPlaybackEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Injection légère : les objets partagés de l'app, créés une fois (pas de framework d'injection pour si peu).
 * Les écrans (téléphone aujourd'hui, TV en phase 8) ne dépendent que des ViewModels et de ce conteneur.
 */
class AppContainer(private val context: Context) {

    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    val session = SessionStore(object : KeyValueStore {
        override fun get(key: String) = prefs.getString(key, null)
        override fun put(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }, KeystoreTokenCipher())

    /** Client de base : délais raisonnables, aucune journalisation (jamais de jeton dans les logs). */
    val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val refresher = TokenRefresher(session, baseClient)

    /** Client authentifié pour l'API. */
    val apiClient: OkHttpClient = baseClient.newBuilder()
        .addInterceptor(AuthInterceptor(session))
        .authenticator(TokenAuthenticator(refresher))
        .build()

    val api = AnimeApi(session, apiClient)

    val auth = AuthRepository(session, baseClient, BuildConfig.ALLOW_HTTP, "${Build.MANUFACTURER} ${Build.MODEL}")

    /** Images : servies par notre serveur, sans authentification (identifiants aléatoires, ARCHITECTURE §17). */
    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { baseClient })) }
        .crossfade(true)
        .build()

    private val playerPrefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)

    /** Pistes préférées (audio japonais, sous-titres français par défaut). */
    val trackPrefs = TrackPrefsStore(object : KeyValueStore {
        override fun get(key: String) = playerPrefs.getString(key, null)
        override fun put(key: String, value: String?) {
            playerPrefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    })

    /** Positions enregistrées : la fiche et l'accueil se mettent à jour dès que l'envoi a abouti. */
    val progressBus = fr.plexwish.anime.feature.player.ProgressBus()

    /** Travaux qui doivent survivre à un écran (envoi de la progression à la sortie du lecteur). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Flux vidéo : client de base, sans intercepteur d'authentification (l'URL signée suffit). */
    fun newPlaybackEngine(): PlaybackEngine = ExoPlaybackEngine(context.applicationContext, baseClient, trackPrefs)
}
