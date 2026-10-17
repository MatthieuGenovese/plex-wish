package fr.plexwish.anime.playback

import android.content.Context
import android.graphics.Color
import android.view.accessibility.CaptioningManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat

/**
 * Style des sous-titres texte (SRT, ASS, WebVTT). Par défaut : texte blanc à contour noir, **sans fond**, comme à la
 * télévision. Media3 dessinait sinon un bandeau noir derrière chaque ligne (son style par défaut quand les sous-titres
 * d'accessibilité d'Android sont désactivés, c'est-à-dire presque toujours). Une personne qui a réglé ses sous-titres
 * dans les paramètres d'accessibilité d'Android garde son style. Les couleurs propres à un fichier ASS restent appliquées.
 */
@OptIn(UnstableApi::class)
object SubtitleStyle {

    val OUTLINE = CaptionStyleCompat(
        Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null,
    )

    fun forDevice(context: Context): CaptionStyleCompat {
        val captioning = context.getSystemService(CaptioningManager::class.java)
        return choose(captioning?.isEnabled == true) { CaptionStyleCompat.createFromCaptionStyle(captioning!!.userStyle) }
    }

    /** {@code accessibilityEnabled} : sous-titres activés dans l'accessibilité d'Android (leur style a alors priorité). */
    internal fun choose(accessibilityEnabled: Boolean, userStyle: () -> CaptionStyleCompat): CaptionStyleCompat =
        if (accessibilityEnabled) userStyle() else OUTLINE
}
