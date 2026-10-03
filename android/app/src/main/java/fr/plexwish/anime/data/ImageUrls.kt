package fr.plexwish.anime.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * URL d'image renvoyée par l'API (ARCHITECTURE §17) : un chemin local (« /api/posters/… », « /api/cast-images/… »)
 * servi par notre serveur, ou, en repli, l'adresse d'origine chez TMDB / AniList. L'app ne charge que les images
 * de **notre** serveur : jamais d'appel à TMDB ni AniList (le visuel de remplacement s'affiche à la place).
 */
object ImageUrls {

    fun resolve(server: HttpUrl?, url: String?): String? {
        if (server == null || url.isNullOrBlank()) return null
        if (url.startsWith("/") && !url.startsWith("//")) {
            return server.resolve(url)?.toString()
        }
        val absolute = url.toHttpUrlOrNull() ?: return null
        return if (absolute.scheme == server.scheme && absolute.host == server.host && absolute.port == server.port) {
            absolute.toString()
        } else {
            null
        }
    }
}
