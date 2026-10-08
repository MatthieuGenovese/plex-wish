package fr.plexwish.anime.ui.phone

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.KeyEvent as AndroidKeyEvent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import fr.plexwish.anime.feature.player.PlayerState
import fr.plexwish.anime.feature.player.PlayerViewModel
import fr.plexwish.anime.feature.player.TrackInfo
import fr.plexwish.anime.feature.player.TrackType
import fr.plexwish.anime.playback.ExoPlaybackEngine
import fr.plexwish.anime.ui.components.AppIconButton
import fr.plexwish.anime.ui.components.GhostButton
import fr.plexwish.anime.ui.components.LinkButton
import fr.plexwish.anime.ui.components.PrimaryButton
import fr.plexwish.anime.ui.components.focusRing
import fr.plexwish.anime.ui.theme.AppIcons
import fr.plexwish.anime.ui.theme.AppShapes
import fr.plexwish.anime.ui.theme.AppTheme
import fr.plexwish.anime.ui.theme.Dimens
import fr.plexwish.anime.ui.theme.OnImagePalette
import fr.plexwish.anime.ui.theme.PaletteTheme
import fr.plexwish.anime.ui.theme.PillShape
import kotlinx.coroutines.delay
import java.util.Locale

/** Position et état de lecture lus sur le moteur (l'écran les relit régulièrement). */
data class Playhead(val positionMs: Long = 0, val durationMs: Long = 0, val playing: Boolean = false)

/** Actions de la surcouche. */
data class PlayerActions(
    val onBack: () -> Unit = {},
    val togglePlay: () -> Unit = {},
    val seekBy: (Long) -> Unit = {},
    val seekTo: (Long) -> Unit = {},
    val openTracks: () -> Unit = {},
)

/**
 * Lecteur plein écran (paysage, barres système masquées), toujours sombre. Image et sous-titres : Media3 (styles du
 * fichier, taille selon les réglages d'accessibilité d'Android) ; surcouche en Compose : retour, titre, recul et
 * avance de 10 s, lecture / pause, barre de progression, panneau « Audio et sous-titres ». Un toucher (ou une touche
 * de la télécommande) affiche la surcouche, qui se masque pendant la lecture (délai allongé avec TalkBack). États :
 * chargement, reconnexion, « Préparation de l'épisode… » (indicateur sur l'image), erreur avec « Réessayer » et
 * « Détails », avertissement si le son manque.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
    // Arrière-plan : position envoyée, puis pause.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onBackground() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Position relue sur le moteur (4 fois par seconde : la barre avance sans à-coups).
    var head by remember { mutableStateOf(Playhead()) }
    LaunchedEffect(vm) {
        while (true) {
            val e = vm.engine
            head = Playhead(e.positionMs.coerceAtLeast(0), e.durationMs.coerceAtLeast(0), e.isPlaying)
            delay(250)
        }
    }

    var overlay by rememberSaveable { mutableStateOf(true) }
    var tracks by rememberSaveable { mutableStateOf(false) }
    var activity by remember { mutableIntStateOf(0) } // chaque action repousse le masquage
    val root = remember { FocusRequester() }
    val firstControl = remember { FocusRequester() }
    val timeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        4_000, containsIcons = true, containsText = true, containsControls = true) ?: 4_000
    val showControls = overlay || s.phase == PlayerPhase.ENDED
    LaunchedEffect(showControls, head.playing, tracks, activity) {
        if (showControls && head.playing && !tracks) {
            delay(timeout)
            overlay = false
            runCatching { root.requestFocus() }
        }
    }
    LaunchedEffect(showControls) { if (showControls) runCatching { firstControl.requestFocus() } else runCatching { root.requestFocus() } }

    PaletteTheme(OnImagePalette) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    activity++
                    when (e.key.nativeKeyCode) {
                        AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> { vm.togglePlay(); overlay = true; true }
                        AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { if (!head.playing) vm.togglePlay(); true }
                        AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { if (head.playing) vm.togglePlay(); true }
                        AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { vm.seekBy(10_000); true }
                        AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { vm.seekBy(-10_000); true }
                        AndroidKeyEvent.KEYCODE_BACK, AndroidKeyEvent.KEYCODE_ESCAPE -> false
                        else -> if (!showControls) { overlay = true; true } else false // 1re touche : affiche la surcouche
                    }
                }
                .focusRequester(root).focusable(),
        ) {
            val exo = (vm.engine as? ExoPlaybackEngine)?.player
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exo
                        useController = false // surcouche en Compose
                        keepScreenOn = true
                        // Taille et style des sous-titres : réglages d'accessibilité d'Android, styles du fichier conservés.
                        subtitleView?.setUserDefaultStyle()
                        subtitleView?.setUserDefaultTextSize()
                    }
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
            // Toucher l'image : affiche ou masque la surcouche (pas d'effet visuel, pas d'élément pour TalkBack).
            Box(Modifier.fillMaxSize().semantics { contentDescription = "Vidéo" }.clickable(MutableInteractionSource(), indication = null, onClickLabel = "Afficher ou masquer les commandes") {
                overlay = !overlay; activity++
            })

            when (s.phase) {
                PlayerPhase.LOADING -> BusyIndicator(null, Modifier.align(Alignment.Center))
                PlayerPhase.RECONNECTING -> BusyIndicator("Reconnexion…", Modifier.align(Alignment.Center))
                // Conversion pour Android sur le serveur : un indicateur et une ligne, directement sur l'image (pas de cadre).
                // Annuler = retour (bouton, geste, touche Retour de la télécommande).
                PlayerPhase.PREPARING -> BusyIndicator("Préparation de l'épisode…", Modifier.align(Alignment.Center))
                PlayerPhase.ERROR -> s.error?.let {
                    ErrorPanel(it, vm::retry, { vm.showDetails(it) }, onBack, Modifier.align(Alignment.Center),
                        onWithoutSound = if (s.canPlayWithoutSound) vm::playWithoutSound else null)
                }
                else -> {}
            }

            AnimatedVisibility(showControls && s.phase != PlayerPhase.ERROR, enter = fadeIn(), exit = fadeOut()) {
                PlayerOverlay(
                    s, head, firstControl,
                    PlayerActions(onBack, { vm.togglePlay(); activity++ }, { vm.seekBy(it); activity++ }, { vm.seekTo(it); activity++ },
                        { tracks = true }),
                )
            }
            // Pendant le chargement ou la préparation, retour toujours accessible.
            if (!showControls && s.phase != PlayerPhase.PLAYING && s.phase != PlayerPhase.ERROR) {
                AppIconButton(AppIcons.ArrowBack, "Retour", onBack, Modifier.safeDrawingPadding().padding(4.dp), tint = Color.White)
            }

            if (s.warnings.isNotEmpty() && s.phase != PlayerPhase.ERROR) {
                Surface(
                    color = Color(0xE6151821), shape = AppShapes.medium,
                    modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 64.dp, start = 16.dp, end = 16.dp)
                        .widthIn(max = 560.dp).semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        s.warnings.forEach { Text(it.message, color = Color.White, style = MaterialTheme.typography.bodyMedium) }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            LinkButton("Détails", { vm.showDetails(s.warnings.first()) })
                            LinkButton("OK", vm::dismissWarnings)
                        }
                    }
                }
            }

            if (tracks) TrackSheet(s, onSelect = { type, t -> vm.selectTrack(type, t) }, onDismiss = { tracks = false; activity++ })
            s.details?.let { DetailsDialog(it) { vm.showDetails(null) } }
        }
    }
}

/** « 4:05 », « 1:02:09 ». */
fun timeLabel(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Durée pour TalkBack : « 4 minutes 5 secondes ». */
private fun spokenTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return listOfNotNull(if (h > 0) "$h heure${if (h > 1) "s" else ""}" else null, "$m minute${if (m > 1) "s" else ""}",
        "$sec seconde${if (sec > 1) "s" else ""}").joinToString(" ")
}

/**
 * Surcouche du lecteur : barre du haut (retour, titres), commandes au centre, barre de progression et « Audio et
 * sous-titres » en bas. Ordre de focus : retour, recul, lecture, avance, barre, audio et sous-titres ; la lecture
 * prend le focus à l'apparition (télécommande).
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerOverlay(s: PlayerState, head: Playhead, firstControl: FocusRequester, a: PlayerActions) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = head.durationMs
    val shown = dragging?.let { (it * duration).toLong() } ?: head.positionMs
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.7f), 0.3f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f)))) {
        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconButton(AppIcons.ArrowBack, "Retour", a.onBack, tint = Color.White)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() })
                s.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (s.soundOff) Text("Lecture sans le son (fichier à convertir sur le serveur)", color = AppTheme.palette.warn,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }

        if (s.phase == PlayerPhase.PLAYING || s.phase == PlayerPhase.ENDED) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundControl(AppIcons.Replay10, "Reculer de 10 secondes", { a.seekBy(-10_000) }, 56.dp)
                RoundControl(if (head.playing) AppIcons.PauseFill else AppIcons.PlayArrowFill, if (head.playing) "Pause" else "Lecture",
                    a.togglePlay, 76.dp, Modifier.focusRequester(firstControl), filled = true)
                RoundControl(AppIcons.Forward10, "Avancer de 10 secondes", { a.seekBy(10_000) }, 56.dp)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = Dimens.s4, vertical = Dimens.s2)) {
            if (duration > 0) {
                val colors = SliderDefaults.colors(thumbColor = AppTheme.palette.accent, activeTrackColor = AppTheme.palette.accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f))
                Slider(
                    value = (shown.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { a.seekTo((it * duration).toLong()) }; dragging = null },
                    colors = colors,
                    // Barre d'un seul tenant, pastille ronde (pas d'espace ni de point de fin, comme la barre des épisodes).
                    thumb = { Box(Modifier.size(18.dp).background(AppTheme.palette.accent, CircleShape)) },
                    track = { st ->
                        SliderDefaults.Track(sliderState = st, colors = colors, drawStopIndicator = null, thumbTrackGapSize = 0.dp,
                            modifier = Modifier.height(4.dp))
                    },
                    modifier = Modifier.fillMaxWidth().focusRing(PillShape).semantics {
                        contentDescription = "Position dans l'épisode"
                        stateDescription = "${spokenTime(shown)} sur ${spokenTime(duration)}"
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (duration > 0) "${timeLabel(shown)} / ${timeLabel(duration)}" else "", style = MaterialTheme.typography.labelMedium,
                    color = Color.White, modifier = Modifier.weight(1f).semantics { contentDescription = "" })
                if (s.audioTracks.size > 1 || s.textTracks.isNotEmpty()) {
                    GhostButton("Audio et sous-titres", a.openTracks, icon = AppIcons.Subtitles, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun RoundControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier, filled: Boolean = false,
) {
    val p = AppTheme.palette
    IconButton(
        onClick = onClick,
        modifier = modifier.focusRing(CircleShape, grow = true).size(size)
            .background(if (filled) p.accent else Color.Black.copy(alpha = 0.35f), CircleShape),
    ) {
        Icon(icon, contentDescription = label, tint = if (filled) p.onAccent else Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

/** Nom d'une piste : son libellé, sinon sa langue en français, sinon « Piste n ». */
fun trackName(t: TrackInfo, n: Int): String {
    val language = t.language?.takeIf { it.isNotBlank() && it != "und" }?.let { THREE_LETTERS[it.lowercase()] ?: it }?.let { code ->
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.FRENCH).takeIf { it.isNotBlank() && it != code }
            ?.replaceFirstChar { it.titlecase(Locale.FRENCH) } ?: code
    }
    return listOfNotNull(t.label?.takeIf { it.isNotBlank() }, language).distinct().joinToString(" · ").ifEmpty { "Piste $n" }
}

/** Codes de langue à trois lettres fréquents dans les MKV (Media3 les ramène d'habitude à deux lettres). */
private val THREE_LETTERS = mapOf(
    "jpn" to "ja", "fra" to "fr", "fre" to "fr", "eng" to "en", "ger" to "de", "deu" to "de", "spa" to "es", "ita" to "it",
    "por" to "pt", "chi" to "zh", "zho" to "zh", "kor" to "ko", "rus" to "ru", "ara" to "ar",
)

/** Panneau « Audio et sous-titres » (feuille du bas). Le choix est mémorisé pour les épisodes suivants. */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackSheet(s: PlayerState, onSelect: (TrackType, TrackInfo?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AppTheme.palette.surface1) {
        TrackPanel(s.audioTracks, s.textTracks, onSelect = { type, t -> onSelect(type, t); onDismiss() })
    }
}

@Composable
fun TrackPanel(audio: List<TrackInfo>, text: List<TrackInfo>, onSelect: (TrackType, TrackInfo?) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s4).padding(bottom = Dimens.s5),
        verticalArrangement = Arrangement.spacedBy(Dimens.s2)) {
        Text("Audio et sous-titres", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text("Votre choix est gardé pour les épisodes suivants.", style = MaterialTheme.typography.bodySmall, color = AppTheme.palette.text2)
        if (audio.isNotEmpty()) {
            Text("Audio", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Dimens.s2).semantics { heading() })
            Column(Modifier.selectableGroup()) {
                audio.forEachIndexed { i, t -> TrackOption(trackName(t, i + 1), t.selected) { onSelect(TrackType.AUDIO, t) } }
            }
        }
        Text("Sous-titres", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Dimens.s2).semantics { heading() })
        Column(Modifier.selectableGroup()) {
            TrackOption("Désactivés", text.none { it.selected }) { onSelect(TrackType.TEXT, null) }
            text.forEachIndexed { i, t -> TrackOption(trackName(t, i + 1), t.selected) { onSelect(TrackType.TEXT, t) } }
        }
    }
}

@Composable
private fun TrackOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = AppTheme.palette
    Row(
        Modifier.fillMaxWidth().focusRing(AppShapes.medium)
            .background(if (selected) p.accentSoft else Color.Transparent, AppShapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Dimens.s3, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s3),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) Icon(AppIcons.Check, contentDescription = null, tint = p.accent)
    }
}

/** Indicateur de chargement, avec une ligne de texte éventuelle, posé sur l'image (ombre portée pour rester lisible). */
@Composable
private fun BusyIndicator(text: String?, modifier: Modifier) {
    Column(
        modifier.padding(24.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = Color.White)
        text?.let {
            Text(
                it, color = Color.White, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium.copy(shadow = Shadow(Color.Black, Offset(0f, 1f), 6f)),
            )
        }
    }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
fun ErrorPanel(
    d: Diagnosis, onRetry: () -> Unit, onDetails: () -> Unit, onBack: () -> Unit, modifier: Modifier,
    onWithoutSound: (() -> Unit)? = null,
) {
    val p = AppTheme.palette
    Column(
        modifier.safeDrawingPadding().padding(24.dp).widthIn(max = 560.dp)
            .background(p.surface1.copy(alpha = 0.96f), AppShapes.large).padding(20.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Error, contentDescription = null, tint = p.err)
            Text("Lecture impossible", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.semantics { heading() })
        }
        Text(d.message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Réessayer", onRetry, icon = AppIcons.Refresh)
            onWithoutSound?.let { GhostButton("Lire sans le son", it, color = Color.White) }
            GhostButton("Détails", onDetails, color = Color.White)
            LinkButton("Retour", onBack)
        }
    }
}

/** Écran « Détails » : cause technique (codec, conteneur, code d'erreur, HTTP), jamais d'URL ni de jeton. Texte sélectionnable. */
@Composable
private fun DetailsDialog(d: Diagnosis, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { LinkButton("Fermer", onDismiss) },
        title = { Text("Détails") },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(d.message)
                    d.details.forEach { (k, v) -> Text("$k : $v", style = MaterialTheme.typography.bodySmall) }
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
