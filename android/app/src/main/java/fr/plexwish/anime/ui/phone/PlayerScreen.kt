package fr.plexwish.anime.ui.phone

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import fr.plexwish.anime.feature.player.Diagnosis
import fr.plexwish.anime.feature.player.PlayerPhase
import fr.plexwish.anime.feature.player.PlayerViewModel
import fr.plexwish.anime.playback.ExoPlaybackEngine

/**
 * Lecteur plein écran (paysage, barres système masquées). Commandes de Media3 (lecture, avance, pistes audio et
 * sous-titres : boutons « Paramètres » et « Sous-titres », lus par TalkBack) ; par-dessus : titre et retour, état de
 * reconnexion, erreurs avec « Réessayer » et « Détails », avertissements (son, sous-titres).
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var controls by remember { mutableStateOf(true) }

    // Plein écran paysage pendant la lecture, rétabli en sortant.
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val old = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val insets = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            if (old != null) activity.requestedOrientation = old
        }
    }
    // Arrière-plan : pause (et, au bloc progression, envoi de la position).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onBackground() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val exo = (vm.engine as? ExoPlaybackEngine)?.player
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exo
                    keepScreenOn = true
                    setShowSubtitleButton(true)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    // Taille et style des sous-titres : réglages d'accessibilité d'Android, styles du fichier conservés.
                    subtitleView?.setUserDefaultStyle()
                    subtitleView?.setUserDefaultTextSize()
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controls = v == View.VISIBLE })
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        if (controls || s.phase == PlayerPhase.ERROR) {
            Row(
                Modifier.fillMaxWidth().safeDrawingPadding().background(Color(0x99000000)).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(s.title, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1)
                    s.subtitle?.let { Text(it, color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                }
            }
        }

        when (s.phase) {
            PlayerPhase.LOADING, PlayerPhase.RECONNECTING -> Column(
                Modifier.align(Alignment.Center).semantics { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                if (s.phase == PlayerPhase.RECONNECTING) Text("Reconnexion…", color = Color.White, modifier = Modifier.padding(top = 8.dp))
            }
            PlayerPhase.ERROR -> s.error?.let { ErrorPanel(it, vm::retry, { vm.showDetails(it) }, onBack, Modifier.align(Alignment.Center)) }
            else -> {}
        }

        if (s.warnings.isNotEmpty() && s.phase != PlayerPhase.ERROR) {
            Surface(
                color = Color(0xE6212531), shape = MaterialTheme.shapes.medium,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 72.dp)
                    .widthIn(max = 560.dp).semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Column(Modifier.padding(12.dp)) {
                    s.warnings.forEach { Text(it.message, color = Color.White, style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { vm.showDetails(s.warnings.first()) }) { Text("Détails") }
                        TextButton(onClick = vm::dismissWarnings) { Text("OK") }
                    }
                }
            }
        }

        s.details?.let { DetailsDialog(it) { vm.showDetails(null) } }
    }
}

@Composable
private fun ErrorPanel(d: Diagnosis, onRetry: () -> Unit, onDetails: () -> Unit, onBack: () -> Unit, modifier: Modifier) {
    Surface(color = Color(0xF0171A22), shape = MaterialTheme.shapes.large, modifier = modifier.padding(24.dp).widthIn(max = 520.dp)) {
        Column(Modifier.padding(20.dp).semantics { liveRegion = LiveRegionMode.Assertive }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Lecture impossible", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(d.message, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) { Text("Réessayer") }
                OutlinedButton(onClick = onDetails) { Text("Détails") }
                TextButton(onClick = onBack) { Text("Retour") }
            }
        }
    }
}

/** Écran « Détails » : cause technique (codec, conteneur, code d'erreur, HTTP), jamais d'URL ni de jeton. Texte sélectionnable. */
@Composable
private fun DetailsDialog(d: Diagnosis, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        title = { Text("Détails") },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(d.message)
                    d.details.forEach { (k, v) ->
                        Text("$k : $v", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
