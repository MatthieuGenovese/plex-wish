package fr.plexwish.anime

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory

class AnimeApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Journaux nettoyés (jamais d'URL signée ni de jeton), Media3 compris.
        fr.plexwish.anime.data.log.LogcatSink.install(BuildConfig.DEBUG)
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader
}
