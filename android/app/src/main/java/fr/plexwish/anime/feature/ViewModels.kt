package fr.plexwish.anime.feature

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.plexwish.anime.AnimeApp
import fr.plexwish.anime.AppContainer
import fr.plexwish.anime.feature.login.LoginViewModel

/** Fabriques des ViewModels (écrans téléphone aujourd'hui, TV plus tard). */
object ViewModels {
    private fun CreationExtras.container(): AppContainer =
        (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AnimeApp).container

    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer { LoginViewModel(container().auth, container().session) }
    }
}
