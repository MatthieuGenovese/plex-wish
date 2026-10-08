package fr.plexwish.anime.ui.phone

import fr.plexwish.anime.ui.theme.AppIcons

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fr.plexwish.anime.AppContainer
import fr.plexwish.anime.feature.ViewModels
import fr.plexwish.anime.ui.components.AboutDialog
import kotlinx.coroutines.launch

/** Routes de l'app téléphone. Un jeu d'écrans TV (phase 8) aura son propre NavHost sur les mêmes ViewModels. */
object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val LIBRARY = "library"
    const val ANIME = "anime/{id}"
    const val PERSON = "person/{id}"
    const val PLAYER = "play/{id}"
    fun anime(id: Long) = "anime/$id"
    fun person(id: String) = "person/$id"
    fun player(episodeId: Long) = "play/$episodeId"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneApp(container: AppContainer) {
    val nav = rememberNavController()
    val loggedIn by container.session.loggedIn.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val scope = rememberCoroutineScope()
    // Fixée au premier affichage : ensuite, connexion et déconnexion naviguent (pile vidée), sans reconstruire le graphe.
    val start = remember { if (loggedIn) Routes.HOME else Routes.LOGIN }

    // Déconnexion (bouton, ou session refusée par le serveur) : retour à la connexion, pile vidée.
    LaunchedEffect(loggedIn) {
        val target = if (loggedIn) Routes.HOME else Routes.LOGIN
        if (route != null && (route == Routes.LOGIN) != !loggedIn) {
            nav.navigate(target) { popUpTo(nav.graph.id) { inclusive = true } }
        }
    }

    Scaffold(
        topBar = {
            if (route != null && route != Routes.LOGIN && route != Routes.PLAYER) {
                TopAppBar(
                    title = {
                        Text(when (route) {
                            Routes.LIBRARY -> "Bibliothèque"
                            Routes.HOME -> fr.plexwish.anime.BuildConfig.APP_NAME
                            Routes.PERSON -> "Comédien"
                            else -> ""
                        })
                    },
                    navigationIcon = {
                        if (route == Routes.ANIME || route == Routes.PERSON) IconButton(onClick = { nav.popBackStack() }) {
                            Icon(AppIcons.ArrowBack, contentDescription = "Retour")
                        }
                    },
                    actions = { AccountMenu(container.session.username) { scope.launch { container.auth.logout() } } },
                )
            }
        },
        bottomBar = {
            if (route == Routes.HOME || route == Routes.LIBRARY) BottomBar(nav, route)
        },
    ) { padding ->
        NavHost(nav, startDestination = start) {
            composable(Routes.LOGIN) { LoginScreen(viewModel(factory = ViewModels.Factory)) }
            composable(Routes.HOME) {
                HomeScreen(viewModel(factory = ViewModels.Factory),
                    onContinue = { nav.navigate(Routes.player(it.episodeId)) },
                    onAnime = { nav.navigate(Routes.anime(it)) }, padding = padding)
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(viewModel(factory = ViewModels.Factory), onAnime = { nav.navigate(Routes.anime(it)) }, padding = padding)
            }
            composable(Routes.ANIME, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                AnimeDetailScreen(viewModel(factory = ViewModels.Factory), onPerson = { nav.navigate(Routes.person(it)) }, padding = padding,
                    onEpisode = { nav.navigate(Routes.player(it.id)) })
            }
            composable(Routes.PLAYER, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                PlayerScreen(viewModel(factory = ViewModels.Factory), onBack = { nav.popBackStack() })
            }
            composable(Routes.PERSON, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                PersonScreen(viewModel(factory = ViewModels.Factory), onAnime = { nav.navigate(Routes.anime(it)) }, padding = padding)
            }
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController, route: String?) {
    NavigationBar {
        listOf(Triple(Routes.HOME, "Accueil", AppIcons.Home), Triple(Routes.LIBRARY, "Bibliothèque", AppIcons.VideoLibrary))
            .forEach { (r, label, icon) ->
                NavigationBarItem(
                    selected = route == r,
                    onClick = {
                        nav.navigate(r) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(label) },
                )
            }
    }
}

@Composable
private fun AccountMenu(username: String?, onLogout: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    if (about) AboutDialog { about = false }
    IconButton(onClick = { open = true }) { Icon(AppIcons.AccountCircle, contentDescription = "Compte") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        username?.let {
            DropdownMenuItem(text = { Text("Connecté : $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = {}, enabled = false)
        }
        DropdownMenuItem(text = { Text("À propos") }, onClick = { open = false; about = true })
        DropdownMenuItem(text = { Text("Se déconnecter") }, onClick = { open = false; onLogout() })
    }
}

