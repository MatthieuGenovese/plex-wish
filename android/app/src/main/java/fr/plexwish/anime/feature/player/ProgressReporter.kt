package fr.plexwish.anime.feature.player

import fr.plexwish.anime.data.api.ApiException
import fr.plexwish.anime.data.log.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Envoi de la progression (PUT /api/episodes/{id}/progress) : toutes les ~10 s pendant la lecture, et tout de suite à
 * la pause, au passage en arrière-plan, à la fin et à la sortie. Le serveur marque l'épisode terminé au-delà de 90 %.
 * Rien n'est envoyé pour une position incohérente (durée inconnue, position négative ou au-delà de la durée, moins
 * d'une seconde). Un échec (réseau) ne bloque jamais la lecture : la position est renvoyée à la prochaine occasion.
 * Les envois passent un par un, dans l'ordre (un ancien ne peut pas écraser un plus récent).
 */
class ProgressReporter(
    private val send: suspend (positionSeconds: Int, durationSeconds: Int) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = 10_000,
    /** Appelé après chaque envoi réussi (la fiche et l'accueil se mettent à jour). */
    private val onSaved: (Sample) -> Unit = {},
) {
    data class Sample(val positionSeconds: Int, val durationSeconds: Int)

    private val mutex = Mutex()
    @Volatile
    private var lastSent: Sample? = null
    @Volatile
    private var lastAttemptAt = Long.MIN_VALUE / 2

    /** Pendant la lecture (appelé chaque seconde) : envoi toutes les {@code intervalMs}. */
    fun tick(positionMs: Long, durationMs: Long) {
        if (clock() - lastAttemptAt >= intervalMs) report(positionMs, durationMs)
    }

    /** Pause, arrière-plan, fin, sortie : envoi immédiat (sauf si cette position est déjà enregistrée). */
    fun flush(positionMs: Long, durationMs: Long) = report(positionMs, durationMs)

    private fun report(positionMs: Long, durationMs: Long) {
        val s = sample(positionMs, durationMs) ?: return
        if (s == lastSent) return
        lastAttemptAt = clock()
        scope.launch {
            mutex.withLock {
                if (s == lastSent) return@withLock
                try {
                    send(s.positionSeconds, s.durationSeconds)
                    lastSent = s
                    onSaved(s)
                } catch (e: ApiException) {
                    // Réseau ou serveur indisponible : la position sera renvoyée à la prochaine occasion.
                    SafeLog.i(TAG, "Progression non envoyée (${e.code ?: "HTTP " + e.status}), nouvel essai plus tard")
                }
            }
        }
    }

    companion object {
        private const val TAG = "Progress"
        /** Tolérance : la position peut dépasser un peu la durée annoncée (fin de fichier). */
        private const val SLACK_MS = 2_000

        /** Position et durée cohérentes → secondes à envoyer ; sinon null (rien n'est envoyé). */
        fun sample(positionMs: Long, durationMs: Long): Sample? {
            if (durationMs <= 0 || durationMs > 86_400_000L) return null
            if (positionMs < 1_000 || positionMs > durationMs + SLACK_MS) return null
            val duration = ((durationMs + 500) / 1000).toInt().coerceAtLeast(1)
            return Sample((positionMs / 1000).toInt().coerceAtMost(duration), duration)
        }
    }
}
