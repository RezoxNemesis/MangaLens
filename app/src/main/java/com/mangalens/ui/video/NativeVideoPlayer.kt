package com.mangalens.ui.video

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mangalens.download.MediaDownloadManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NativeVideoPlayer(
    url: String,
    translationEnabled: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenWeb: () -> Unit = {}
) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var controlsLocked by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var hudVisible by remember { mutableStateOf(true) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var downloadQuality by remember { mutableStateOf(com.mangalens.download.DownloadQuality.P1080) }
    var downloadStatus by remember { mutableStateOf<String?>(null) }
    var liveTranslationEnabled by remember { mutableStateOf(translationEnabled) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val targetLanguage = context.getSharedPreferences("mangalens_preferences", Context.MODE_PRIVATE).getString("translation_target", "hi") ?: "hi"
    if (VideoSourcePolicy.isSourcePage(url)) {
        com.mangalens.ui.web.AdBlockedWebScreen(url, translationEnabled = false,
            modifier = modifier, onClose = onBack)
        return
    }
    val playerVm: LocalVideoPlayerViewModel = viewModel()
    val player = playerVm.player
    val scope = rememberCoroutineScope()
    LaunchedEffect(url) { playbackError = null; playerVm.openHttp(url) }
    LaunchedEffect(translationEnabled) { liveTranslationEnabled = translationEnabled }

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            if (window != null) WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(hudVisible) {
        if (hudVisible) {
            delay(2000)
            hudVisible = false
        }
    }

    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playbackError = when (error.errorCode) {
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server rejected this stream. Reopen the source page to refresh its link or sign-in session."
                    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "This link is not supported playable media. Open the source page and choose a direct MP4, HLS or DASH stream."
                    else -> "Playback failed (${error.errorCodeName}). Check the connection or reopen the source link."
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) playbackError = null
            }
        }
        val owner = context as? androidx.lifecycle.LifecycleOwner
        val lifecycleListener = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause()
        }
        owner?.lifecycle?.addObserver(lifecycleListener)
        player.addListener(listener)
        onDispose { owner?.lifecycle?.removeObserver(lifecycleListener); player.removeListener(listener); player.pause(); playerView?.player = null }
    }

    Box(
        modifier = modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (!controlsLocked) hudVisible = !hudVisible },
                    onDoubleTap = { offset ->
                        if (controlsLocked) return@detectTapGestures
                        if (offset.x < size.width / 2f) player.seekBack() else player.seekForward()
                        hudVisible = true
                    }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, amount ->
                    if (controlsLocked) return@detectVerticalDragGestures
                    change.consume()
                    val fraction = (amount / 900f).coerceIn(-0.08f, 0.08f)
                    if (change.position.x < size.width / 2f) {
                        val activity = context as? Activity
                        if (activity != null) {
                            val lp = activity.window.attributes
                            val current = if (lp.screenBrightness >= 0f) lp.screenBrightness else 0.5f
                            lp.screenBrightness = (current - fraction).coerceIn(0.05f, 1f)
                            activity.window.attributes = lp
                        }
                    } else {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        audio.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            (current - fraction * max).toInt().coerceIn(0, max),
                            0
                        )
                    }
                    hudVisible = true
                }
            }
            .transformable(
                rememberTransformableState { zoom, _, _ ->
                    if (controlsLocked) return@rememberTransformableState
                    resizeMode = when {
                        zoom > 1.03f -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        zoom < 0.97f -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        else -> resizeMode
                    }
                    hudVisible = true
                }
            )
    ) {
        AndroidView(
            factory = {
                (android.view.LayoutInflater.from(it).inflate(com.mangalens.R.layout.ocr_player_view, null) as PlayerView).apply {
                    this.player = player
                    playerView = this
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = false
                    this.resizeMode = resizeMode
                }
            },
            update = { it.resizeMode = resizeMode; playerView = it },
            modifier = Modifier.fillMaxSize()
        )

        LiveVideoOcrTranslationOverlay(
            enabled = liveTranslationEnabled,
            targetLanguage = targetLanguage,
            playerView = playerView,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (hudVisible) 132.dp else 8.dp)
        )

        if (playbackError != null) Surface(Modifier.align(Alignment.Center).padding(24.dp)) { Column(Modifier.padding(16.dp)) { Text(playbackError!!); TextButton(onClick = { playbackError = null; player.prepare(); player.play() }) { Text("Retry") }; TextButton(onClick = onOpenWeb) { Text("Open source page") } } }
        downloadStatus?.let { Text(it, modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp), color = MaterialTheme.colorScheme.onSurface) }
        if (controlsLocked) TextButton(onClick = { controlsLocked = false; hudVisible = true }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) { Text("Unlock controls") }
        if (hudVisible && !controlsLocked) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onBack) { Text("Back") }
                    Text("Stream", style = MaterialTheme.typography.titleMedium)
                    Text(player.videoSize.height.takeIf { it > 0 }?.let { "${it}p" } ?: "Auto", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { player.seekBack() }) { Text("◄◄ -10s") }
                        FilledTonalButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                            Text(if (player.isPlaying) "⏸️" else "▶️")
                        }
                        TextButton(onClick = { player.seekForward() }) { Text("10s ►►") }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { downloadQuality = when (downloadQuality) { com.mangalens.download.DownloadQuality.P480 -> com.mangalens.download.DownloadQuality.P720; com.mangalens.download.DownloadQuality.P720 -> com.mangalens.download.DownloadQuality.P1080; com.mangalens.download.DownloadQuality.P1080 -> com.mangalens.download.DownloadQuality.P1440; com.mangalens.download.DownloadQuality.P1440 -> com.mangalens.download.DownloadQuality.P2160; com.mangalens.download.DownloadQuality.P2160 -> com.mangalens.download.DownloadQuality.P480 } }) { Text("Download quality") }
                        TextButton(onClick = { controlsLocked = true; hudVisible = false }) { Text("Lock controls") }
                        TextButton(onClick = { liveTranslationEnabled = !liveTranslationEnabled }) { Text(if (liveTranslationEnabled) "Live OCR on" else "Live OCR off") }
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { MediaDownloadManager(context).enqueue(url, "MangaLens video", quality = downloadQuality) }
                                    .onSuccess { downloadStatus = "Download queued at " + downloadQuality.label }
                                    .onFailure { downloadStatus = it.message ?: "Download failed" }
                            }
                        }) { Text("Download " + downloadQuality.label) }
                        TextButton(onClick = {
                            resizeMode = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        }) { Text("⛶ Fullscreen") }
                    }
                }
            }
        }
    }
}
