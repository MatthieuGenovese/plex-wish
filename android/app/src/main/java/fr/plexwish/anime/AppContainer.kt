package fr.plexwish.anime

import android.content.Context
import android.os.Build
import coil.ImageLoader
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.auth.AuthInterceptor
import fr.plexwish.anime.data.auth.AuthRepository
import fr.plexwish.anime.data.auth.KeyValueStore
import fr.plexwish.anime.data.auth.KeystoreTokenCipher
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.data.auth.TokenAuthenticator
import fr.plexwish.anime.data.auth.TokenRefresher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Injection légère : les objets partagés de l'app, créés une fois (pas de framework d'injection pour si peu).
 * Les écrans (téléphone aujourd'hui, TV en phase 8) ne dépendent que des ViewModels et de ce conteneur.
 */
class AppContainer(context: Context) {

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
        .okHttpClient(baseClient)
        .crossfade(true)
        .build()
}
