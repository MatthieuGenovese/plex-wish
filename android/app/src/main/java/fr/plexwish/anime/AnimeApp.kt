package fr.plexwish.anime

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader

class AnimeApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Journaux nettoyés (jamais d'URL signée ni de jeton), Media3 compris.
        fr.plexwish.anime.data.log.LogcatSink.install(BuildConfig.DEBUG)
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}
