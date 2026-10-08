package fr.plexwish.anime.ui

import fr.plexwish.anime.data.api.AnimeSummary
import fr.plexwish.anime.data.api.ContinueWatching
import fr.plexwish.anime.data.api.GenreCount
import java.time.Instant

/** Données de démonstration pour les captures (titres longs, accents, macrons, comme le catalogue de démo). */
object Fixtures {
    val titles = listOf(
        "Même si l'été ne revient pas, nous garderons les fenêtres ouvertes", "Frieren", "Hoshi to Sōshi", "Kage ga Kitan",
        "Cœur d'acier, âme de papier : la légende du forgeron œnologue", "Iron Lullaby (1986)", "Kumo no Senki", "Hōseki!! Gakuen?",
        "Jūnin wa Odyssey × Ishi", "Akuma ga Δ", "Ryūsei no Kiroku", "Le Dernier Wagon",
    )

    fun anime(i: Int, fresh: Boolean = false) = AnimeSummary(
        id = 1000L + i, title = titles[i % titles.size], year = 1990 + (i * 7) % 35, posterUrl = null,
        episodeCount = listOf(12L, 24L, 5L, 1L, 68L)[i % 5],
        lastAddedAt = if (fresh) Instant.now().minusSeconds(3600).toString() else "2026-01-01T00:00:00Z",
    )

    fun animes(n: Int, from: Int = 0) = (from until from + n).map { anime(it) }

    fun continueWatching(i: Int, next: Boolean = false) = ContinueWatching(
        episodeId = 5000L + i, episodeNumber = 3 + i, episodeTitle = if (i == 0) "La tempête" else null, seasonId = 10, seasonNumber = 1,
        seasonLabel = "Saison 1", animeId = 1000L + i, animeTitle = titles[i % titles.size],
        positionSeconds = if (next) 0 else 540 + i * 60, durationSeconds = 1440, kind = if (next) "NEXT" else "RESUME",
    )

    val genres = listOf(GenreCount("Comedy", "Comédie", 40), GenreCount("Romance", "Romance", 22), GenreCount("Mecha", "Mecha", 9))
}
