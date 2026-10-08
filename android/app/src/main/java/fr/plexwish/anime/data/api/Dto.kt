package fr.plexwish.anime.data.api

import kotlinx.serialization.Serializable

// Réponses de l'API REST (les mêmes que pour le web). Champs inconnus ignorés (Json.ignoreUnknownKeys).

@Serializable
data class UserDto(val id: Long, val username: String, val role: String)

@Serializable
data class AppTokens(
    val accessToken: String,
    val expiresIn: Int,
    val refreshToken: String,
    val user: UserDto,
) {
    override fun toString() = "AppTokens(user=${user.username}, tokens=***)"
}

@Serializable
data class ApiErrorBody(val status: Int = 0, val error: String? = null, val message: String? = null)

@Serializable
data class StatusDto(val status: String? = null)

@Serializable
data class AnimeSummary(
    val id: Long,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val episodeCount: Long = 0,
    /** Dernier fichier ajouté (ISO 8601) : badge « Nouveau » pendant 14 jours. */
    val lastAddedAt: String? = null,
)

@Serializable
data class Page<T>(val total: Long, val page: Int, val size: Int, val items: List<T>)

@Serializable
data class ContinueWatching(
    val episodeId: Long,
    val episodeNumber: Int,
    val episodeTitle: String? = null,
    val seasonId: Long,
    val seasonNumber: Int,
    val seasonLabel: String,
    val animeId: Long,
    val animeTitle: String,
    val positionSeconds: Int,
    val durationSeconds: Int,
    val posterUrl: String? = null,
    /** « RESUME » (épisode commencé) ou « NEXT » (épisode suivant du dernier terminé), S1. */
    val kind: String = "RESUME",
) {
    val isNext get() = kind == "NEXT"
}

/** Genre présent dans la bibliothèque (libellé français, S3). */
@Serializable
data class GenreCount(val genre: String, val label: String, val animeCount: Long = 0)

@Serializable
data class GenreDto(val genre: String, val label: String)

/**
 * Bouton principal de la fiche (S5) : {@code kind} RESUME (reprendre à {@code positionSeconds}), NEXT (épisode
 * suivant), START (rien regardé), REWATCH (tout vu).
 */
@Serializable
data class ResumeDto(
    val kind: String,
    val episodeId: Long,
    val seasonId: Long,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String? = null,
    val positionSeconds: Int = 0,
    val durationSeconds: Int = 0,
)

@Serializable
data class SeasonDto(val id: Long, val seasonNumber: Int, val label: String, val episodeCount: Long = 0)

@Serializable
data class AnimeDetail(
    val id: Long,
    val title: String,
    val alternativeTitle: String? = null,
    val synopsis: String? = null,
    /** Langue du synopsis (« fr » si TMDB, « en » si AniList). */
    val synopsisLanguage: String? = null,
    val posterUrl: String? = null,
    val posterLargeUrl: String? = null,
    val year: Int? = null,
    val metadataSource: String? = null,
    val metadataUrl: String? = null,
    val seasons: List<SeasonDto> = emptyList(),
    val synopsisSource: String? = null,
    val frenchTitle: String? = null,
    val tmdbUrl: String? = null,
    val resume: ResumeDto? = null,
    val genres: List<GenreDto> = emptyList(),
)

@Serializable
data class EpisodeSummary(val id: Long, val episodeNumber: Int, val title: String? = null, val durationSeconds: Int? = null)

@Serializable
data class ProgressDto(
    val episodeId: Long,
    val positionSeconds: Int,
    val durationSeconds: Int,
    val completed: Boolean,
)

/** Personnage : nom seulement (pas d'image, seuls les comédiens ont une photo). */
@Serializable
data class CastCharacter(val name: String, val nativeName: String? = null)

@Serializable
data class CastPersonRef(val id: String, val name: String, val nativeName: String? = null, val imageUrl: String? = null)

@Serializable
data class CastEntry(val character: CastCharacter, val role: String, val language: String = "ja", val person: CastPersonRef? = null)

@Serializable
data class AnimeCast(val source: String? = null, val sourceUrl: String? = null, val items: List<CastEntry> = emptyList())

@Serializable
data class PersonRole(
    val animeId: Long,
    val animeTitle: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val character: CastCharacter,
    val role: String,
)

@Serializable
data class PersonDetail(
    val id: String,
    val name: String,
    val nativeName: String? = null,
    val imageUrl: String? = null,
    val sourceUrl: String? = null,
    val roles: List<PersonRole> = emptyList(),
)

@Serializable
data class EpisodeDetail(
    val id: Long,
    val animeId: Long,
    val animeTitle: String,
    val seasonId: Long,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String? = null,
    val durationSeconds: Int? = null,
    /** Extension du fichier (« mkv », « avi »…) : sert au diagnostic quand la lecture échoue. */
    val container: String? = null,
    val fileSize: Long = 0,
)

/** URL de lecture signée (relative au serveur). Secrète : jamais dans un log ni un message. */
@Serializable
data class StreamUrlDto(val url: String, val expiresAt: String? = null, val mimeType: String? = null, val fileSize: Long = 0) {
    override fun toString() = "StreamUrlDto(url=***, expiresAt=$expiresAt)"
}

/** Réponse 202 de stream-url : épisode en cours de conversion pour Android sur le serveur. */
@Serializable
data class PreparingDto(
    val state: String = "PREPARING",
    val position: Int = 0,
    val progress: Double? = null,
    val estimatedSeconds: Long = 0,
    val retryAfterSeconds: Int = 5,
    val message: String? = null,
)

/** Changement de mot de passe (app) : la session de ce téléphone ({@code refreshToken}) est gardée. Jamais journalisé. */
@Serializable
data class PasswordChange(val currentPassword: String, val newPassword: String, val refreshToken: String) {
    override fun toString() = "PasswordChange(***)"
}

@Serializable
data class PasswordChanged(val closedSessions: Int = 0)
