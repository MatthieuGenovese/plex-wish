package fr.plexwish.anime.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.plexwish.anime.AppContainer
import fr.plexwish.anime.feature.ViewModels
import kotlinx.coroutines.launch

/** Connexion seule (l'accueil et la bibliothèque arrivent au commit suivant). */
@Composable
fun PhoneApp(container: AppContainer) {
    val loggedIn by container.session.loggedIn.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (!loggedIn) {
        LoginScreen(viewModel(factory = ViewModels.Factory))
    } else {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Connecté : ${container.session.username}")
            Button(onClick = { scope.launch { container.auth.logout() } }) { Text("Se déconnecter") }
        }
    }
}
