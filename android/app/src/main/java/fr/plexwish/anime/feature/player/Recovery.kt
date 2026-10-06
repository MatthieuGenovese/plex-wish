package fr.plexwish.anime.feature.player

import fr.plexwish.anime.data.api.ProgressDto

/** Que faire après une erreur de lecture. */
sealed interface Recovery {
    /** Redemander une URL signée puis recharger à la même position (403 : lien expiré, pause très longue). */
    data object RefreshUrl : Recovery

    /** Coupure réseau : recharger après {@code delayMs}, avec une nouvelle URL si l'actuelle expire bientôt. */
    data class Retry(val delayMs: Long, val refreshUrl: Boolean) : Recovery

    data object Fail : Recovery
}

/**
 * Reprise automatique. Une pause de plus de ~60 s ou une coupure de quelques secondes fait tomber la connexion :
 * ExoPlayer la rouvre seul quand il le peut ; s'il abandonne, on recharge à la dernière position connue (seek pendant
 * la pause compris), avec une nouvelle URL signée si le serveur a répondu 403 ou si l'URL va expirer. Les compteurs
 * repartent de zéro dès que la lecture reprend.
 */
class RecoveryPolicy(
    private val networkDelaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000),
    private val maxUrlRefreshes: Int = 2,
) {
    fun decide(kind: FailureKind, networkAttempts: Int, urlRefreshes: Int, urlExpiresSoon: Boolean): Recovery = when (kind) {
        FailureKind.FORBIDDEN -> if (urlRefreshes < maxUrlRefreshes) Recovery.RefreshUrl else Recovery.Fail
        // Copie de conversion effacée du cache entre-temps : un nouveau lien la fait refaire (le fichier d'origine
        // absent, lui, donne une erreur au moment de redemander le lien).
        FailureKind.NOT_FOUND -> if (urlRefreshes < 1) Recovery.RefreshUrl else Recovery.Fail
        FailureKind.NETWORK, FailureKind.SERVER ->
            if (networkAttempts < networkDelaysMs.size) Recovery.Retry(networkDelaysMs[networkAttempts], urlExpiresSoon) else Recovery.Fail
        else -> Recovery.Fail
    }

    companion object {
        /** Une URL qui expire dans moins de 2 minutes est renouvelée avant de reprendre. */
        const val EXPIRY_MARGIN_MS = 2 * 60_000L
    }
}

/** Position de départ : la position enregistrée, sauf épisode terminé (≥ 90 %) ou à peine commencé (< 5 s). */
object ResumePoint {
    fun startMs(progress: ProgressDto?): Long {
        if (progress == null || progress.completed || progress.positionSeconds < 5) return 0
        if (progress.durationSeconds > 0 && progress.positionSeconds >= progress.durationSeconds * 0.9) return 0
        return progress.positionSeconds * 1000L
    }
}
