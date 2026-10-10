package com.mangalens.ui.video

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
    val playbackLifecycle by vm.lifecycle.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val speechState by vm.speech.state.collectAsState()
    var locked by rememberSaveable { mutableStateOf(false) }
    var controls by rememberSaveable { mutableStateOf(true) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var resizeMode by rememberSaveable { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    val fallbackWindow = remember { kotlinx.coroutines.flow.MutableStateFlow(PlaybackWindowState()) }
    val windowState by ((activity as? PlaybackWindowHost)?.playbackWindow?.state ?: fallbackWindow).collectAsState()
    val fullscreen = windowState.fullscreen
    var showTools by rememberSaveable { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var subtitleLanguage by rememberSaveable { mutableStateOf("hi") }
    var liveTranslationEnabled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(playbackLifecycle.nativeCaption) { if (playbackLifecycle.nativeCaption != null) liveTranslationEnabled = false }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var activeVideoUri by rememberSaveable { mutableStateOf(initialUri?.toString()) }
    val scope = rememberCoroutineScope()
    val fullSubtitleGenerator = remember(vm.speech, scope) {
        FullVideoSubtitleGenerator(context.applicationContext, vm.speech, scope,
            attachmentAllowed = vm::canAttachGenerated, attachManually = vm::selectGeneratedCaption)
    }
    val subtitleMediaSource = activeVideoUri?.let {
        SubtitleMediaSource(uri = it, cacheKey = it, label = "Local video")
    }
    LaunchedEffect(subtitleMediaSource) { subtitleMediaSource?.let(fullSubtitleGenerator::bind) }

    val presentationLease = PlayerPresentationLifecycle(vm, playerView)

    fun immersive(enabled: Boolean) {
        (activity as? PlaybackWindowHost)?.playbackWindow?.setFullscreen(enabled)
    }

    fun interact() {
        if (locked) return
        controls = true
        lastInteraction = System.currentTimeMillis()
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(250)
            if (controls && System.currentTimeMillis() - lastInteraction >= 3_000L) controls = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                vm.session.reportStatus("This provider allows this visit only. Select the video again if its saved access expires.")
            }
            activeVideoUri = it.toString()
            fullSubtitleGenerator.bind(SubtitleMediaSource(uri = it.toString(), cacheKey = it.toString(), label = "Local video"))
            vm.open(it)
        }
    }

    LaunchedEffect(initialUri) {
        initialUri?.let {
            activeVideoUri = it.toString()
            fullSubtitleGenerator.bind(SubtitleMediaSource(uri = it.toString(), cacheKey = it.toString(), label = "Local video"))
            vm.open(it)
        }
    }
    LaunchedEffect(translationEnabled) { if (!translationEnabled) liveTranslationEnabled = false }

    Box(
        modifier.fillMaxSize().background(Color.Black)
            .pointerInput(locked) {
                detectTapGestures(
                    onTap = { interact() },
                    onDoubleTap = doubleTap@ { point ->
                        if (locked) return@doubleTap
                        val delta = if (point.x < size.width / 2f) -10_000L else 10_000L
                        vm.player.seekTo((vm.player.currentPosition + delta).coerceAtLeast(0L))
                        interact()
                    }
                )
            }
            .pointerInput(locked) {
                detectVerticalDragGestures { change, drag ->
                    change.consume()
                    if (locked) return@detectVerticalDragGestures
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
                if (locked) return@rememberTransformableState
                zoom = (zoom * scale).coerceIn(1f, 3f)
                resizeMode = if (zoom > 1.05f) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else resizeMode
                interact()
            })
    ) {
        AndroidView(
            factory = { (android.view.LayoutInflater.from(it).inflate(com.mangalens.R.layout.ocr_player_view, null) as PlayerView).apply { playerView = this; vm.bind(this, presentationLease); useController = true; controllerAutoShow = false } },
            update = { view ->
                playerView = view
                view.player = vm.player.takeIf { vm.session.policy.ownsPresentation(presentationLease) }
                view.useController = !locked && !playbackLifecycle.inPictureInPicture
                if (playbackLifecycle.inPictureInPicture) view.hideController()
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
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (controls && !playbackLifecycle.inPictureInPicture) 76.dp else 8.dp)
        )

        LiveAudioSubtitleOverlay(vm.speech, modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp).padding(bottom = if (playbackLifecycle.inPictureInPicture) 8.dp else if (controls) 90.dp else 24.dp))

        AnimatedVisibility(
            visible = controls && !locked && !playbackLifecycle.inPictureInPicture,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(350)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            CinematicVideoDock(Modifier.fillMaxWidth().padding(12.dp)) {
                VideoDockAction(
                    label = "Open",
                    status = "Local video",
                    onClick = { picker.launch(arrayOf("video/*")); interact() }
                )
                VideoDockAction(
                    label = "Tools",
                    status = "Player settings",
                    onClick = { showTools = true; interact() }
                )
                VideoDockAction(
                    label = "English Audio CC",
                    status = when {
                        speechState.generated -> "Generated track active"
                        speechState.enabled -> speechState.status.take(32)
                        speechState.ready -> "Whisper ready • offline"
                        else -> "Model required"
                    },
                    selected = speechState.enabled || speechState.generated,
                    onClick = { showTools = true; interact() }
                )
                VideoDockAction(
                    label = "Live OCR",
                    status = if (liveTranslationEnabled) "On" else "Off",
                    selected = liveTranslationEnabled,
                    onClick = { liveTranslationEnabled = !liveTranslationEnabled; interact() }
                )
                VideoDockAction(
                    label = "Generate Full Subtitles",
                    status = "English SRT",
                    onClick = { showTools = true; interact() }
                )
                VideoDockAction(
                    label = "Fit",
                    status = when (resizeMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Crop"
                        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
                        else -> "Fit"
                    },
                    onClick = {
                        resizeMode = when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        zoom = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) 1.15f else 1f
                        interact()
                    }
                )
                VideoDockAction(
                    label = if (fullscreen) "Exit" else "Fullscreen",
                    status = "Immersive",
                    onClick = { immersive(!fullscreen); interact() }
                )
            }
        }

        if (speechState.ready && controls && !playbackLifecycle.inPictureInPicture) {
            VideoStatusChip(
                if (speechState.generated) "Generated English subtitles • offline"
                else if (speechState.enabled) speechState.status
                else "Whisper ready • offline",
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 92.dp),
                active = speechState.enabled || speechState.generated
            )
        }

        if (locked && !playbackLifecycle.inPictureInPicture) {
            TextButton(onClick = { locked = false; interact() }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) { Text("Unlock") }
        }
        if (showTools && !playbackLifecycle.inPictureInPicture) {
            ModalBottomSheet(onDismissRequest = { showTools = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PlayerLifecycleControlsPanel(vm)
                    HorizontalDivider()
                    PlaybackTrackControlsPanel(vm.player)
                    HorizontalDivider()
                    SubtitleToolsPanel(vm.speech, fullSubtitleGenerator, subtitleMediaSource)
                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ locked = true; controls = false; showTools = false }) { Text("Lock controls") }
                    }
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
                    }
                    ImportedCaptionControls(vm)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Live OCR to " + subtitleLanguage.uppercase())
                        TextButton(onClick = { subtitleLanguage = when (subtitleLanguage) { "hi" -> "hi-latn"; "hi-latn" -> "en"; "en" -> "ja"; "ja" -> "ko"; "ko" -> "zh"; else -> "hi" } }) { Text("Change") }
                    }
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
