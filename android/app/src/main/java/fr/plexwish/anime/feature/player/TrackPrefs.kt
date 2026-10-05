package fr.plexwish.anime.feature.player

import fr.plexwish.anime.data.auth.KeyValueStore

/**
 * Pistes préférées, mémorisées sur le téléphone : audio japonais et sous-titres français par défaut.
 * {@code text = null} : sous-titres désactivés par l'utilisateur.
 */
data class TrackPrefs(val audio: String = "ja", val text: String? = "fr")

class TrackPrefsStore(private val store: KeyValueStore) {

    fun load(): TrackPrefs = TrackPrefs(
        audio = store.get(AUDIO)?.takeIf { it.isNotBlank() } ?: "ja",
        text = when (val t = store.get(TEXT)) {
            null, "" -> "fr"
            OFF -> null
            else -> t
        },
    )

    fun save(p: TrackPrefs) {
        store.put(AUDIO, p.audio)
        store.put(TEXT, p.text ?: OFF)
    }

    /**
     * Choix fait par l'utilisateur dans le lecteur → nouvelles préférences. Une langue inconnue (« und », vide) ne
     * remplace pas la préférence : choisir l'unique piste sans langue d'un fichier ne doit pas l'effacer.
     */
    fun update(audioLanguage: String?, textLanguage: String?, textDisabled: Boolean): TrackPrefs {
        val old = load()
        val new = TrackPrefs(
            audio = audioLanguage.known() ?: old.audio,
            text = if (textDisabled) null else textLanguage.known() ?: old.text ?: "fr",
        )
        if (new != old) save(new)
        return new
    }

    private fun String?.known() = this?.lowercase()?.takeIf { it.isNotBlank() && it != "und" && it != "unknown" }?.substringBefore('-')

    private companion object {
        const val AUDIO = "player.audio"
        const val TEXT = "player.text"
        const val OFF = "off"
    }
}
