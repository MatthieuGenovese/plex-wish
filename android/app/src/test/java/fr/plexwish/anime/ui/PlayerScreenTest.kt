package fr.plexwish.anime.ui

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.plexwish.anime.feature.player.Diagnosis
import fr.plexwish.anime.feature.player.PlayerPhase
import fr.plexwish.anime.feature.player.PlayerState
import fr.plexwish.anime.feature.player.TrackInfo
import fr.plexwish.anime.feature.player.TrackType
import fr.plexwish.anime.ui.phone.ErrorPanel
import fr.plexwish.anime.ui.phone.LeavingScreen
import fr.plexwish.anime.ui.phone.awaitRotationBack
import fr.plexwish.anime.ui.phone.PlayerActions
import fr.plexwish.anime.ui.phone.PlayerOverlay
import fr.plexwish.anime.ui.phone.Playhead
import fr.plexwish.anime.ui.phone.TrackPanel
import fr.plexwish.anime.ui.theme.OnImagePalette
import fr.plexwish.anime.ui.theme.PaletteTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.content.res.Configuration
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/** Lecteur (P3.6), en paysage : surcouche, panneau « Audio et sous-titres », erreur. Toujours sombre. */
@Config(qualifiers = "w832dp-h384dp-land-xxhdpi")
class PlayerScreenTest : ScreenTest() {

    private val tracks = listOf(
        TrackInfo(TrackType.VIDEO, "video/hevc", group = 0, index = 0),
        TrackInfo(TrackType.AUDIO, "audio/opus", language = "ja", selected = true, group = 1, index = 0),
        TrackInfo(TrackType.AUDIO, "audio/aac", language = "fr", group = 2, index = 0),
        TrackInfo(TrackType.TEXT, "text/x-ssa", language = "fr", label = "Dialogues", selected = true, group = 3, index = 0),
        TrackInfo(TrackType.TEXT, "text/x-ssa", language = "fr", label = "Panneaux", group = 4, index = 0),
    )
    private val state = PlayerState(phase = PlayerPhase.PLAYING, title = "Épisode 3 · La tempête", subtitle = "Sousou no Frieren · Saison 1", tracks = tracks)
    private val variants = listOf(ScreenTest.ALL[0], ScreenTest.ALL[2])

    @Test
    fun overlay() {
        var toggled = 0
        var seek = 0L
        var opened = false
        shoot("p36-lecteur", variants) {
            PaletteTheme(OnImagePalette) {
                Box(Modifier.fillMaxSize().background(Color(0xFF203040))) {
                    PlayerOverlay(state, Playhead(612_000, 1_440_000, playing = true), FocusRequester(),
                        PlayerActions(togglePlay = { toggled++ }, seekBy = { seek = it }, openTracks = { opened = true }))
                }
            }
        }
        assertAccessible()
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Avancer de 10 secondes").performClick()
        compose.onNodeWithText("Audio et sous-titres").performClick()
        assertEquals(1, toggled)
        assertEquals(10_000L, seek)
        assertEquals(true, opened)
    }

    @Test
    fun trackPanel() {
        var chosen: Pair<TrackType, TrackInfo?>? = null
        shoot("p36-pistes", variants) {
            PaletteTheme(OnImagePalette) {
                TrackPanel(tracks.filter { it.type == TrackType.AUDIO }, tracks.filter { it.type == TrackType.TEXT }) { t, i -> chosen = t to i }
            }
        }
        assertAccessible()
        compose.onNodeWithText("Désactivés").performClick()
        assertEquals(TrackType.TEXT to null, chosen)
    }

    @Test
    fun error() {
        shoot("p36-erreur", variants) {
            PaletteTheme(OnImagePalette) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    ErrorPanel(Diagnosis("Connexion au serveur perdue. Vérifiez le réseau, puis réessayez.", emptyList()), {}, {}, {},
                        Modifier.align(Alignment.Center), onWithoutSound = {})
                }
            }
        }
        assertAccessible()
    }

    /** Sortie du lecteur : écran noir et indicateur, sans image figée de la vidéo. */
    @Test
    fun leaving() {
        shoot("p36-sortie", listOf(ScreenTest.ALL[0])) { LeavingScreen() }
        compose.onNodeWithContentDescription("Fermeture du lecteur").assertIsDisplayed()
    }

    /** Horloge d'images à 60 Hz sur le temps virtuel du test. */
    private object FrameClock : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R { delay(16); return onFrame(0L) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun leavingWaitsForTheRotationBack() = runTest(FrameClock) {
        val landscape = Configuration.ORIENTATION_LANDSCAPE
        val portrait = Configuration.ORIENTATION_PORTRAIT
        // Déjà en portrait, ou écran d'avant lui aussi en paysage : pas d'attente.
        awaitRotationBack(portrait, rotates = true) { portrait }
        awaitRotationBack(landscape, rotates = false) { landscape }
        assertEquals(0L, currentTime)

        // Rotation au bout de 300 ms : la fiche s'affiche deux images plus tard.
        val orientation = mutableIntStateOf(landscape)
        val start = currentTime
        val wait = async { awaitRotationBack(landscape, rotates = true) { orientation.intValue } }
        launch {
            delay(300)
            Snapshot.withMutableSnapshot { orientation.intValue = portrait }
            Snapshot.sendApplyNotifications()
        }
        wait.await()
        assertEquals(332L, currentTime - start)

        // Téléphone à plat : la rotation ne vient pas, on sort quand même au bout du délai.
        val flat = currentTime
        awaitRotationBack(landscape, rotates = true) { landscape }
        assertEquals(1_232L, currentTime - flat)
    }
}
