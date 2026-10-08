package fr.plexwish.anime.feature

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.plexwish.anime.AnimeApp
import fr.plexwish.anime.AppContainer
import fr.plexwish.anime.feature.detail.AnimeDetailViewModel
import fr.plexwish.anime.feature.home.HomeViewModel
import fr.plexwish.anime.feature.library.LibraryViewModel
import fr.plexwish.anime.feature.login.LoginViewModel
import fr.plexwish.anime.feature.person.PersonViewModel
import fr.plexwish.anime.feature.player.PlayerViewModel

/**
 * Fabriques des ViewModels. Les ViewModels ne connaissent que le conteneur (dépôts, API) : ils servent tels
 * quels aux écrans téléphone et, plus tard, aux écrans TV.
 */
object ViewModels {
    private fun CreationExtras.container(): AppContainer =
        (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AnimeApp).container

    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer { LoginViewModel(container().auth, container().session, fr.plexwish.anime.BuildConfig.DEFAULT_SERVER_URL) }
        initializer { fr.plexwish.anime.feature.account.AccountViewModel(container().api) }
        initializer { HomeViewModel(container().api, container().session, container().progressBus) }
        initializer { LibraryViewModel(container().api, container().session, createSavedStateHandle()) }
        initializer { AnimeDetailViewModel(container().api, container().session, createSavedStateHandle(), container().progressBus) }
        initializer { PersonViewModel(container().api, container().session, createSavedStateHandle()) }
        initializer {
            val c = container()
            PlayerViewModel(c.api, c::newPlaybackEngine, createSavedStateHandle(), c.appScope, progressBus = c.progressBus)
        }
    }
}
