package fr.plexwish.anime.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ARCHITECTURE §17 : seules les images de notre serveur sont chargées, jamais TMDB ni AniList. */
class ImageUrlsTest {
    private val server = "https://anime.mondomaine.fr/".toHttpUrl()

    @Test
    fun localPathsAreResolvedAgainstTheServer() {
        assertEquals("https://anime.mondomaine.fr/api/posters/0123456789abcdef0123456789abcdef",
            ImageUrls.resolve(server, "/api/posters/0123456789abcdef0123456789abcdef"))
        assertEquals("https://anime.mondomaine.fr/api/cast-images/abc", ImageUrls.resolve(server, "/api/cast-images/abc"))
        assertEquals("https://anime.mondomaine.fr/api/posters/x", ImageUrls.resolve(server, "https://anime.mondomaine.fr/api/posters/x"))
    }

    @Test
    fun remoteFallbacksAreNeverContacted() {
        assertNull(ImageUrls.resolve(server, "https://image.tmdb.org/t/p/w500/a.jpg"))
        assertNull(ImageUrls.resolve(server, "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx1.jpg"))
        assertNull(ImageUrls.resolve(server, "//s4.anilist.co/x.jpg"))
        assertNull(ImageUrls.resolve(server, "http://anime.mondomaine.fr/api/posters/x")) // autre schéma
        assertNull(ImageUrls.resolve(server, null))
        assertNull(ImageUrls.resolve(null, "/api/posters/x"))
    }
}
