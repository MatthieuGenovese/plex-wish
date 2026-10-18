package fr.plexwish.anime.data.api

import fr.plexwish.anime.data.auth.SessionStore
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Appels à l'API REST (la même que le web), avec le client authentifié (access token ajouté, rafraîchi sur 401).
 * Aucune logique métier ici : l'app affiche ce que renvoie le serveur.
 */
class AnimeApi(private val session: SessionStore, private val client: OkHttpClient) {

    private fun url(path: String, vararg query: Pair<String, Any?>): HttpUrl {
        val server = session.serverUrl ?: throw ApiException(401, "NO_SERVER", "Session expirée, reconnectez-vous.")
        val b = server.newBuilder().encodedPath(path)
        query.forEach { (k, v) -> if (v != null && v.toString().isNotEmpty()) b.addQueryParameter(k, v.toString()) }
        return b.build()
    }

    suspend inline fun <reified T> get(url: HttpUrl): T = try {
        call(Request.Builder().url(url).get().build()).decode<T>()
    } catch (e: IOException) {
        throw ApiException.fromNetwork(e)
    }

    @PublishedApi
    internal suspend fun call(request: Request) = client.newCall(request).await()

    suspend fun continueWatching(limit: Int = 20): List<ContinueWatching> =
        get(url("/api/me/continue-watching", "limit" to limit))

    /**
     * Bibliothèque. {@code sort} : « title », « recent » (derniers ajouts) ou « year » (S6) ; {@code page} à partir de 0.
     * Filtres (S2, S3) : {@code watch} « unseen » / « inProgress » / « seen », {@code genre} (valeur anglaise d'AniList),
     * années {@code yearFrom}–{@code yearTo} (bornes comprises).
     */
    suspend fun animes(
        sort: String, query: String?, page: Int, size: Int,
        watch: String? = null, genre: String? = null, yearFrom: Int? = null, yearTo: Int? = null,
    ): Page<AnimeSummary> = get(url("/api/anime", "sort" to sort, "q" to query?.trim(), "page" to page, "size" to size,
        "watch" to watch, "genre" to genre, "yearFrom" to yearFrom, "yearTo" to yearTo))

    /** Genres présents dans la bibliothèque, libellés français (S3). */
    suspend fun genres(): List<GenreCount> = get(url("/api/genres"))

    suspend fun anime(id: Long): AnimeDetail = get(url("/api/anime/$id"))

    suspend fun episodes(seasonId: Long): List<EpisodeSummary> = get(url("/api/seasons/$seasonId/episodes"))

    /** Progression de l'utilisateur pour un animé (épisodes vus, en cours). */
    suspend fun progress(animeId: Long): List<ProgressDto> = get(url("/api/me/progress", "animeId" to animeId))

    suspend fun cast(animeId: Long): AnimeCast = get(url("/api/anime/$animeId/cast"))

    /** {@code id} : identifiant AniList du comédien (chiffres seulement, il vient du serveur). */
    suspend fun person(id: String): PersonDetail {
        if (!id.matches(Regex("[0-9]{1,10}"))) throw ApiException(404, "PERSON_NOT_FOUND", "Comédien introuvable.")
        return get(url("/api/people/$id"))
    }

    /** Enregistre la position (le serveur marque l'épisode terminé au-delà de 90 %). */
    suspend fun saveProgress(episodeId: Long, positionSeconds: Int, durationSeconds: Int) {
        val body = """{"positionSeconds":$positionSeconds,"durationSeconds":$durationSeconds}"""
            .toRequestBody("application/json".toMediaType())
        try {
            call(Request.Builder().url(url("/api/episodes/$episodeId/progress")).put(body).build()).requireSuccess()
        } catch (e: IOException) {
            throw ApiException.fromNetwork(e)
        }
    }

    suspend fun episode(id: Long): EpisodeDetail = get(url("/api/episodes/$id"))

    /**
     * Change le mot de passe (S4, ARCHITECTURE §24.5) : la session de ce téléphone est gardée, les autres sont fermées
     * (nombre renvoyé). Erreurs du serveur (mot de passe actuel faux, trop court, identique…) en {@link ApiException}.
     */
    suspend fun changePassword(current: String, new: String): Int {
        val refresh = session.refreshToken() ?: throw ApiException(401, "NO_SESSION", "Session expirée, reconnectez-vous.")
        val body = AppJson.encodeToString(PasswordChange(current, new, refresh)).toRequestBody("application/json".toMediaType())
        return try {
            call(Request.Builder().url(url("/api/auth/app/password")).post(body).build()).decode<PasswordChanged>().closedSessions
        } catch (e: IOException) {
            throw ApiException.fromNetwork(e)
        }
    }

    /**
     * Lien de lecture : URL signée prête (déjà rendue absolue, même serveur), ou « préparation en cours » (HTTP 202 :
     * fichier converti pour Android sur le serveur, à redemander après {@code retryAfterSeconds}). Secret : jamais
     * journalisé. Les erreurs (conversion impossible, cache plein…) arrivent en {@link ApiException}.
     */
    suspend fun stream(episodeId: Long): StreamAnswer {
        val response = try {
            // hls=1 : l'app sait lire le HLS ; un fichier à remuxer est alors lu à la volée, sans préparation (serveur 1.4.0).
            call(Request.Builder().url(url("/api/episodes/$episodeId/stream-url", "hls" to 1)).get().build())
        } catch (e: IOException) {
            throw ApiException.fromNetwork(e)
        }
        if (response.code == 202) {
            val p = response.decode<PreparingDto>()
            return StreamAnswer.Preparing(p.position, p.progress, p.estimatedSeconds, p.retryAfterSeconds.coerceIn(2, 30))
        }
        val dto = response.decode<StreamUrlDto>()
        val server = session.serverUrl ?: throw ApiException(401, "NO_SERVER", "Session expirée, reconnectez-vous.")
        val absolute = server.resolve(dto.url) ?: throw ApiException(0, null, "Réponse inattendue du serveur.")
        if (absolute.host != server.host || absolute.port != server.port) {
            throw ApiException(0, null, "Réponse inattendue du serveur.") // jamais une URL vers un autre hôte
        }
        val expires = dto.expiresAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        // Sous-titres externes : même serveur obligatoire, sinon la piste est simplement ignorée.
        val subtitles = dto.subtitles.mapNotNull { s ->
            server.resolve(s.url)?.takeIf { it.host == server.host && it.port == server.port }
                ?.let { ExternalSubtitle(it.toString(), s.label, s.language, s.forced, s.isDefault, s.mimeType) }
        }
        return StreamAnswer.Ready(SignedStream(absolute.toString(), expires, subtitles, hls = dto.kind == "HLS"))
    }
}

/** Réponse à « je veux lire cet épisode ». */
sealed interface StreamAnswer {
    class Ready(val stream: SignedStream) : StreamAnswer {
        override fun toString() = "Ready(***)"
    }

    /** {@code position} : 0 = en préparation (ou la prochaine), n = n épisodes à préparer avant ; {@code progress} 0..1. */
    data class Preparing(val position: Int, val progress: Double?, val estimatedSeconds: Long, val retryAfterSeconds: Int) : StreamAnswer
}

/**
 * URL signée absolue et son expiration (ms epoch, null si inconnue). {@code hls} : playlist HLS d'une lecture à la
 * volée (fichier remuxé par le serveur pendant la lecture), sinon le fichier lui-même. {@code toString} masqué.
 */
class SignedStream(val url: String, val expiresAtMs: Long?, val subtitles: List<ExternalSubtitle> = emptyList(), val hls: Boolean = false) {
    override fun toString() = "SignedStream(url=***, expiresAtMs=$expiresAtMs, subtitles=${subtitles.size}, hls=$hls)"
}

/**
 * Sous-titre à joindre à la vidéo (.ass externe, ou piste texte du fichier extraite par le serveur pour une lecture à
 * la volée) ; {@code mimeType} : {@code text/x-ssa} ou {@code text/vtt} (null : SSA). {@code url} absolue et signée :
 * jamais journalisée.
 */
class ExternalSubtitle(val url: String, val label: String?, val language: String?, val forced: Boolean, val isDefault: Boolean,
                       val mimeType: String? = null) {
    override fun toString() = "ExternalSubtitle(url=***, label=$label, language=$language, forced=$forced, isDefault=$isDefault)"
}
