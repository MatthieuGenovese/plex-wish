package fr.plexwish.anime.feature

import fr.plexwish.anime.data.ImageUrls
import fr.plexwish.anime.data.auth.SessionStore

/** URL d'image affichable (notre serveur uniquement), voir {@link ImageUrls}. */
fun SessionStore.image(url: String?): String? = ImageUrls.resolve(serverUrl, url)
