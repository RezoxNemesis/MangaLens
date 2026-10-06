package com.mangalens.ui.video

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalVideoPlayerScreen(
    initialUri: Uri? = null,
    translationEnabled: Boolean = false,
    modifier: Modifier = Modifier,
    vm: LocalVideoPlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var controls by rememberSaveable { mutableStateOf(true) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var resizeMode by rememberSaveable { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var fullscreen by rememberSaveable { mutableStateOf(true) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    var groups by remember { mutableStateOf(emptyList<Tracks.Group>()) }
    var audioIndex by rememberSaveable { mutableIntStateOf(0) }
    var subtitle by rememberSaveable { mutableStateOf<Uri?>(null) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var subtitleBusy by rememberSaveable { mutableStateOf(false) }
    var subtitleStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var subtitleLanguage by rememberSaveable { mutableStateOf("hi") }
    var liveTranslationEnabled by rememberSaveable { mutableStateOf(translationEnabled) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val scope = rememberCoroutineScope()

    fun immersive(enabled: Boolean) {
        fullscreen = enabled
        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, !enabled)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                if (enabled) hide(WindowInsetsCompat.Type.systemBars())
                else show(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    fun interact() {
        controls = true
        lastInteraction = System.currentTimeMillis()
    }

    LaunchedEffect(Unit) {
        immersive(true)
        while (true) {
            kotlinx.coroutines.delay(250)
            if (controls && System.currentTimeMillis() - lastInteraction >= 3_000L) controls = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { immersive(false) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::open) }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> subtitle = uri; subtitleStatus = uri?.let { "Subtitle file selected." } }

    LaunchedEffect(initialUri) { initialUri?.let(vm::open) }
    LaunchedEffect(translationEnabled) { liveTranslationEnabled = translationEnabled }

    DisposableEffect(vm.player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            }
        }
        vm.player.addListener(listener)
        onDispose { vm.player.removeListener(listener) }
    }

    Box(
        modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { interact() },
                    onDoubleTap = { point ->
                        val delta = if (point.x < size.width / 2f) -10_000L else 10_000L
                        vm.player.seekTo((vm.player.currentPosition + delta).coerceAtLeast(0L))
                        interact()
                    }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, drag ->
                    change.consume()
                    if (change.position.x < size.width / 2f) {
                        activity?.let { a ->
                            val attrs = a.window.attributes
                            val current = if (attrs.screenBrightness >= 0f) attrs.screenBrightness else .5f
                            attrs.screenBrightness = (current - drag / 1200f).coerceIn(.05f, 1f)
                            a.window.attributes = attrs
                        }
                    } else {
                        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                        val next = (audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) - drag / 1200f * max).roundToInt().coerceIn(0, max)
                        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, next, 0)
                    }
                    interact()
                }
            }
            .transformable(rememberTransformableState { scale, _, _ ->
                zoom = (zoom * scale).coerceIn(1f, 3f)
                resizeMode = if (zoom > 1.05f) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else resizeMode
                interact()
            })
    ) {
        AndroidView(
            factory = { PlayerView(it).apply { playerView = this; vm.bind(this); useController = true; controllerAutoShow = false } },
            update = { view ->
                playerView = view
                view.setResizeMode(resizeMode)
                view.scaleX = zoom
                view.scaleY = zoom
            },
            modifier = Modifier.fillMaxSize()
        )

        LiveVideoOcrTranslationOverlay(
            enabled = liveTranslationEnabled,
            targetLanguage = subtitleLanguage,
            playerView = playerView,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (controls) 76.dp else 8.dp)
        )

        AnimatedVisibility(
            visible = controls,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(350)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                Modifier.fillMaxWidth().padding(12.dp),
                shape = MaterialTheme.shapes.large,
                color = Color.Black.copy(alpha = .62f),
                tonalElevation = 0.dp
            ) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { picker.launch(arrayOf("video/*")); interact() }) { Text("Open") }
                    TextButton(onClick = { showTools = true; interact() }) { Text("Tools") }
                    TextButton(onClick = { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; zoom = 1f; interact() }) { Text("Fit") }
                    TextButton(onClick = { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; zoom = 1.15f; interact() }) { Text("Crop") }
                    TextButton(onClick = { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL; zoom = 1f; interact() }) { Text("Stretch") }
                    TextButton(onClick = { immersive(!fullscreen); interact() }) { Text(if (fullscreen) "Exit" else "Full") }
                }
            }
        }

        if (showTools) {
            ModalBottomSheet(onDismissRequest = { showTools = false }) {
                Column(Modifier.fillMaxWidth().padding(20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Video controls", style = MaterialTheme.typography.headlineSmall)
                    Text("Fit, crop, stretch and pinch zoom are applied directly to the Media3 PlayerView.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Live OCR translation")
                            Text("Recognize visible captions and translate to " + subtitleLanguage.uppercase(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = liveTranslationEnabled, onCheckedChange = { liveTranslationEnabled = it })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; zoom = 1f }) { Text("Fit") }
                        Button(onClick = { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM }) { Text("Zoom") }
                        Button(onClick = { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL }) { Text("Fill") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picker.launch(arrayOf("video/*")); showTools = false }) { Text("Open") }
                        Button(onClick = {
                            if (groups.isNotEmpty()) {
                                val group = groups[audioIndex % groups.size]
                                vm.player.trackSelectionParameters = vm.player.trackSelectionParameters.buildUpon()
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, audioIndex % group.length)).build()
                                audioIndex++
                            }
                        }) { Text("Audio") }
                    }
                    Button(onClick = {
                        val current = vm.player.currentMediaItem?.localConfiguration?.uri ?: return@Button
                        val sub = subtitle
                        if (sub == null) subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "text/vtt"))
                        else {
                            vm.player.setMediaItem(androidx.media3.common.MediaItem.Builder().setUri(current).setSubtitleConfigurations(
                                listOf(androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(sub)
                                    .setMimeType(if (sub.toString().lowercase().endsWith(".srt")) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                                    .setLanguage("und").build())
                            ).build(), vm.player.currentPosition)
                            vm.player.prepare(); vm.player.playWhenReady = true
                            subtitleStatus = "Subtitles applied."
                        }
                    }, Modifier.fillMaxWidth()) { Text(if (subtitle == null) "Choose SRT / VTT" else "Apply subtitles") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Translate to " + subtitleLanguage.uppercase())
                        TextButton(onClick = { subtitleLanguage = when (subtitleLanguage) { "hi" -> "en"; "en" -> "ja"; "ja" -> "ko"; "ko" -> "zh"; else -> "hi" } }) { Text("Change") }
                    }
                    Button(enabled = subtitle != null && !subtitleBusy, onClick = {
                        val source = subtitle
                        if (source != null) {
                            subtitleBusy = true
                            subtitleStatus = "Translating subtitles…"
                            scope.launch {
                                runCatching { VideoSubtitleTranslator(context).translate(source, subtitleLanguage) }
                                    .onSuccess { subtitleStatus = "Translated subtitle saved to Downloads." }
                                    .onFailure { subtitleStatus = it.message ?: "Subtitle translation failed." }
                                subtitleBusy = false
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (subtitleBusy) "Translating…" else "Create translated subtitles") }
                    subtitleStatus?.let { Text(it, color = if (it.contains("failed", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                    Button(onClick = {
                        val uri = vm.player.currentMediaItem?.localConfiguration?.uri ?: return@Button
                        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, context.contentResolver.getType(uri) ?: "video/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Open video with…")) }
                    }, Modifier.fillMaxWidth()) { Text("Open externally") }
                }
            }
        }
    }
}
