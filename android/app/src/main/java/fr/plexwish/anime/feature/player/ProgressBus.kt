package fr.plexwish.anime.feature.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Une position vient d'être enregistrée par le serveur (animé connu dès que l'épisode est chargé). */
data class ProgressSaved(val animeId: Long?, val episodeId: Long, val positionSeconds: Int, val durationSeconds: Int)

/**
 * Prévient la fiche et l'accueil qu'une position a été enregistrée. La dernière position part à la sortie du lecteur,
 * souvent APRÈS que la fiche est revenue à l'écran : relire la progression au retour (même avec un délai) pouvait
 * donc rapporter l'ancienne valeur. Ici, la fiche relit quand l'envoi a réellement abouti.
 */
class ProgressBus {
    private val _events = MutableSharedFlow<ProgressSaved>(extraBufferCapacity = 32)
    val events: SharedFlow<ProgressSaved> = _events.asSharedFlow()

    fun saved(event: ProgressSaved) {
        _events.tryEmit(event)
    }
}
