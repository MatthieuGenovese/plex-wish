package fr.plexwish.anime.ui.phone

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
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
import fr.plexwish.anime.data.ServerUrl
import fr.plexwish.anime.feature.ViewModels
import fr.plexwish.anime.ui.components.AppIconButton
import fr.plexwish.anime.ui.components.focusRing
import fr.plexwish.anime.ui.theme.AppIcons
import fr.plexwish.anime.ui.theme.AppTheme
import fr.plexwish.anime.ui.theme.PillShape
import kotlinx.coroutines.launch

/** Routes de l'app téléphone. Un jeu d'écrans TV (phase 8) aura son propre NavHost sur les mêmes ViewModels. */
object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library?genre={genre}"
    const val ACCOUNT = "account"
    const val ABOUT = "about"
    const val ANIME = "anime/{id}"
    const val PERSON = "person/{id}"
    const val PLAYER = "play/{id}"
    fun library(genre: String? = null) = if (genre == null) "library" else "library?genre=$genre"
    fun anime(id: Long) = "anime/$id"
    fun person(id: String) = "person/$id"
    fun player(episodeId: Long) = "play/$episodeId"
}

/** Onglets de la barre du bas (comme le web sur téléphone). */
private data class Tab(val route: String, val target: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, Routes.HOME, "Accueil", AppIcons.Home, AppIcons.HomeFill),
    Tab(Routes.SEARCH, Routes.SEARCH, "Rechercher", AppIcons.Search, AppIcons.Search),
    Tab(Routes.LIBRARY, Routes.library(), "Bibliothèque", AppIcons.VideoLibrary, AppIcons.VideoLibraryFill),
    Tab(Routes.ACCOUNT, Routes.ACCOUNT, "Compte", AppIcons.AccountCircle, AppIcons.AccountCircleFill),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneApp(container: AppContainer) {
    val nav = rememberNavController()
    val loggedIn by container.session.loggedIn.collectAsStateWithLifecycle()
    val theme by container.themePrefs.mode.collectAsStateWithLifecycle()
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

    val tabRoute = TABS.any { it.route == route }
    val backTitle = when (route) {
        Routes.ANIME, Routes.PERSON -> ""
        Routes.ABOUT -> "À propos"
        else -> null
    }

    Scaffold(
        topBar = {
            if (backTitle != null) {
                TopAppBar(
                    title = { Text(backTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { AppIconButton(AppIcons.ArrowBack, "Retour", { nav.popBackStack() }) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        bottomBar = { if (tabRoute) BottomBar(nav, route) },
        contentWindowInsets = if (route == Routes.PLAYER) WindowInsets(0) else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
    ) { padding ->
        NavHost(nav, startDestination = start) {
            composable(Routes.LOGIN) { LoginScreen(viewModel(factory = ViewModels.Factory)) }
            composable(Routes.HOME) {
                HomeScreen(viewModel(factory = ViewModels.Factory),
                    onContinue = { nav.navigate(Routes.player(it.episodeId)) },
                    onAnime = { nav.navigate(Routes.anime(it)) }, padding = padding)
            }
            composable(Routes.SEARCH) {
                LibraryScreen(viewModel(factory = ViewModels.Factory), onAnime = { nav.navigate(Routes.anime(it)) }, padding = padding,
                    searchMode = true)
            }
            composable(Routes.LIBRARY, arguments = listOf(navArgument("genre") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                LibraryScreen(viewModel(factory = ViewModels.Factory), onAnime = { nav.navigate(Routes.anime(it)) }, padding = padding)
            }
            composable(Routes.ACCOUNT) {
                AccountScreen(viewModel(factory = ViewModels.Factory), username = container.session.username,
                    server = container.session.serverUrl?.let(ServerUrl::display), theme = theme, onTheme = container.themePrefs::set,
                    onAbout = { nav.navigate(Routes.ABOUT) }, onLogout = { scope.launch { container.auth.logout() } }, padding = padding)
            }
            composable(Routes.ABOUT) { AboutScreen(padding) }
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
    val p = AppTheme.palette
    NavigationBar(containerColor = p.surface1) {
        TABS.forEach { tab ->
            val selected = route == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    nav.navigate(tab.target) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                label = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = p.accent, selectedTextColor = p.text, indicatorColor = p.accentSoft,
                    unselectedIconColor = p.text2, unselectedTextColor = p.text2,
                ),
                modifier = Modifier.focusRing(PillShape),
            )
        }
    }
}

@Suppress("unused")
private val NoPadding = PaddingValues()
