package fr.plexwish.anime.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Adresse du serveur saisie par l'utilisateur (« anime.mondomaine.fr », « https://anime.mondomaine.fr/ »…),
 * ramenée à une origine : schéma + hôte (+ port), sans chemin. HTTPS exigé, sauf dans les builds debug
 * ({@code allowHttp}) pour les essais en local.
 */
object ServerUrl {

    sealed interface Result {
        data class Ok(val url: HttpUrl) : Result
        data class Invalid(val message: String) : Result
    }

    fun normalize(input: String, allowHttp: Boolean): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Invalid("Saisissez l'adresse du serveur.")
        if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:/*$").matches(trimmed)) {
            return Result.Invalid("Adresse invalide. Exemple : https://anime.mondomaine.fr")
        }
        val raw = trimmed.trimEnd('/')
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val url = withScheme.toHttpUrlOrNull()
            ?: return Result.Invalid("Adresse invalide. Exemple : https://anime.mondomaine.fr")
        if (url.scheme == "http" && !allowHttp) {
            return Result.Invalid("Adresse en https:// obligatoire (le http en clair n'est permis que dans la version de test).")
        }
        if (url.encodedPath != "/" || url.query != null || url.fragment != null) {
            return Result.Invalid("Saisissez seulement l'adresse du serveur, sans chemin (ex. https://anime.mondomaine.fr).")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return Result.Invalid("L'adresse ne doit pas contenir d'identifiant.")
        }
        return Result.Ok(url.newBuilder().encodedPath("/").build())
    }

    /** Forme affichée et mémorisée : « https://anime.mondomaine.fr » (port seulement s'il n'est pas standard). */
    fun display(url: HttpUrl): String = url.toString().trimEnd('/')
}
