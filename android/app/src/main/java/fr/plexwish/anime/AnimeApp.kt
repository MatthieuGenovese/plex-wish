package fr.plexwish.anime

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory

class AnimeApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader
}
