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
)
