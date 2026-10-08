package fr.plexwish.anime.ui.theme

import fr.plexwish.anime.data.auth.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Thème choisi (Système / Sombre / Clair), enregistré sur l'appareil. Sombre par défaut, comme le web. */
class ThemePrefs(private val store: KeyValueStore) {
    private val _mode = MutableStateFlow(store.get(KEY)?.let { v -> ThemeMode.entries.find { it.name == v } } ?: ThemeMode.DARK)
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    fun set(mode: ThemeMode) {
        store.put(KEY, mode.name)
        _mode.value = mode
    }

    private companion object {
        const val KEY = "theme"
    }
}
