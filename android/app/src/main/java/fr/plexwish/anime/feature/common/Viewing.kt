package fr.plexwish.anime.feature.common

import java.time.Instant

/** Calculs d'affichage liés au visionnage (sans état, les mêmes que le web : web/src/app/shared/viewing.ts). */
object Viewing {
    /** Un ajout est « nouveau » pendant 14 jours. */
    const val NEW_DAYS = 14L

    fun isNew(lastAddedAt: String?, now: Long = System.currentTimeMillis()): Boolean {
        val t = lastAddedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return false
        return now - t < NEW_DAYS * 86_400_000L && t <= now + 60_000
    }

    /** Minutes restantes (arrondi au supérieur, 1 au moins) ; null si la durée est inconnue. */
    fun remainingMinutes(positionSeconds: Int, durationSeconds: Int): Int? {
        if (durationSeconds <= 0) return null
        val left = durationSeconds - minOf(positionSeconds, durationSeconds)
        return maxOf(1, (left + 59) / 60)
    }

    /** Part regardée (0..1). */
    fun fraction(positionSeconds: Int, durationSeconds: Int): Float =
        if (durationSeconds > 0) (positionSeconds.toFloat() / durationSeconds).coerceIn(0f, 1f) else 0f

    /** « S1 · É4 », « Spéciaux · É2 » (court, pour les cartes). */
    fun shortEpisode(seasonNumber: Int, episodeNumber: Int) =
        "${if (seasonNumber == 0) "Spéciaux" else "S$seasonNumber"} · É$episodeNumber"

    /** « Saison 1 · Épisode 4 », « Spéciaux · Épisode 2 ». */
    fun longEpisode(seasonNumber: Int, episodeNumber: Int) =
        "${if (seasonNumber == 0) "Spéciaux" else "Saison $seasonNumber"} · Épisode $episodeNumber"

    /** Durée d'un épisode en minutes arrondies (« 24 min ») ; null si inconnue. */
    fun minutesLabel(seconds: Int?): String? = if (seconds != null && seconds > 0) "${maxOf(1, (seconds + 30) / 60)} min" else null

    /** Pluriel simple : « 1 épisode », « 12 épisodes ». */
    fun count(n: Long, word: String) = "$n $word${if (n > 1) "s" else ""}"
}
