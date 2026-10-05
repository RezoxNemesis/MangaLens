package com.mangalens.ui.video

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import android.webkit.CookieManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mangalens.download.MediaDownloadManager
import com.mangalens.download.MediaLinkResolver
import com.mangalens.download.YtDlpSiteMediaExtractor
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
    onOpenWeb: () -> Unit = {},
    sourcePageUrl: String? = null,
    requestHeaders: Map<String, String> = emptyMap(),
    audioUrl: String? = null,
    audioHeaders: Map<String, String> = emptyMap()
) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var controlsLocked by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var hudVisible by remember { mutableStateOf(true) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var downloadQuality by remember { mutableStateOf(com.mangalens.download.DownloadQuality.BEST) }
    var downloadStatus by remember { mutableStateOf<String?>(null) }
    var liveTranslationEnabled by remember { mutableStateOf(false) }
    var showSpeechSettings by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val targetLanguage = context.getSharedPreferences("mangalens_preferences", Context.MODE_PRIVATE).getString("translation_target", "hi") ?: "hi"
    val playerVm: LocalVideoPlayerViewModel = viewModel()
    val player = playerVm.player
    val speechState by playerVm.speech.state.collectAsState()
    var playing by remember { mutableStateOf(player.isPlaying) }
    var playbackHeight by remember { mutableIntStateOf(player.videoSize.height) }
    val scope = rememberCoroutineScope()
    val resolver = remember(context) {
        MediaLinkResolver(siteExtractor = YtDlpSiteMediaExtractor(context.applicationContext, allowSeparateStreams = true))
    }
    var activeUrl by remember(url) { mutableStateOf(url) }
    var activeHeaders by remember(requestHeaders) { mutableStateOf(requestHeaders) }
    var activeAudioUrl by remember(audioUrl) { mutableStateOf(audioUrl) }
    var activeAudioHeaders by remember(audioHeaders) { mutableStateOf(audioHeaders) }
    var refreshingStream by remember { mutableStateOf(false) }
    var refreshAttempts by remember { mutableIntStateOf(0) }
    val fullSubtitleGenerator = remember(playerVm.speech, scope) {
        FullVideoSubtitleGenerator(context.applicationContext, playerVm.speech, scope)
    }
    val subtitleSource = remember(activeUrl, activeHeaders, activeAudioUrl, activeAudioHeaders, sourcePageUrl) {
        SubtitleMediaSource(
            uri = activeAudioUrl ?: activeUrl,
            headers = if (activeAudioUrl != null) activeAudioHeaders else activeHeaders,
            cacheKey = sourcePageUrl ?: url,
            label = "Online video"
        )
    }

    suspend fun refreshFromSource(): Boolean {
        val page = sourcePageUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return false
        if (refreshingStream) return false
        refreshingStream = true
        return try {
            val resolved = runCatching { resolver.resolveCancellable(page, downloadQuality) }.getOrNull() ?: return false
            val pageRef = resolved.sourcePageUrl ?: page
            val videoCookie = runCatching { CookieManager.getInstance().getCookie(resolved.url) }.getOrNull().orEmpty()
            activeUrl = resolved.url
            activeHeaders = buildMap {
                putAll(resolved.headers)
                if (videoCookie.isNotBlank() && keys.none { it.equals("Cookie", true) }) put("Cookie", videoCookie)
                if (keys.none { it.equals("Referer", true) }) put("Referer", pageRef)
                if (keys.none { it.equals("User-Agent", true) }) put("User-Agent", MediaRequestContext.USER_AGENT)
                if (keys.none { it.equals("Accept", true) }) put("Accept", "*/*")
            }
            activeAudioUrl = resolved.audioUrl
            activeAudioHeaders = resolved.audioUrl?.let { audioStream ->
                val audioCookie = runCatching { CookieManager.getInstance().getCookie(audioStream) }.getOrNull().orEmpty()
                buildMap {
                    putAll(resolved.audioHeaders)
                    if (audioCookie.isNotBlank() && keys.none { it.equals("Cookie", true) }) put("Cookie", audioCookie)
                    if (keys.none { it.equals("Referer", true) }) put("Referer", pageRef)
                    if (keys.none { it.equals("User-Agent", true) }) put("User-Agent", MediaRequestContext.USER_AGENT)
                    if (keys.none { it.equals("Accept", true) }) put("Accept", "*/*")
                }
            }.orEmpty()
            playbackError = null
            true
        } finally {
            refreshingStream = false
        }
    }

    LaunchedEffect(activeUrl, sourcePageUrl, activeHeaders, activeAudioUrl, activeAudioHeaders) {
        playbackError = null
        playerVm.openHttp(
            activeUrl,
            referer = sourcePageUrl,
            headers = activeHeaders,
            audioUrl = activeAudioUrl,
            audioHeaders = activeAudioHeaders
        )
    }
    LaunchedEffect(translationEnabled) { if (!translationEnabled) liveTranslationEnabled = false }

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
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { playbackHeight = videoSize.height }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val refreshable = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
                if (refreshable && !sourcePageUrl.isNullOrBlank() && refreshAttempts < 2) {
                    refreshAttempts++
                    playbackError = "Refreshing the playable stream…"
                    scope.launch {
                        if (!refreshFromSource()) {
                            playbackError = "The stream expired or was rejected. MangaLens re-extraction could not refresh it automatically."
                        }
                    }
                } else {
                    playbackError = "Playback failed (${error.errorCodeName}). Retry or refresh the source stream."
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) {
                    playbackError = null
                    refreshAttempts = 0
                }
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

        // Visual OCR and decoded-audio captions deliberately occupy separate lanes.
        // This avoids the two translation systems covering each other during playback.
        LiveVideoOcrTranslationOverlay(
            enabled = liveTranslationEnabled,
            targetLanguage = targetLanguage,
            playerView = playerView,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = if (hudVisible) 76.dp else 20.dp)
        )

        LiveAudioSubtitleOverlay(
            playerVm.speech,
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp)
                .padding(bottom = if (hudVisible) 154.dp else 26.dp)
        )
        if (showSpeechSettings) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showSpeechSettings = false }) {
                Surface(shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                        SubtitleToolsPanel(playerVm.speech, fullSubtitleGenerator, subtitleSource)
                        TextButton(onClick = { showSpeechSettings = false }) { Text("Close") }
                    }
                }
            }
        }
        if (playbackError != null) Surface(Modifier.align(Alignment.Center).padding(24.dp)) { Column(Modifier.padding(16.dp)) { Text(playbackError!!); TextButton(onClick = { scope.launch { if (!refreshFromSource()) { playbackError = null; player.prepare(); player.play() } } }) { Text("Retry / refresh stream") }; TextButton(onClick = onOpenWeb) { Text("Open source page") } } }
        if (refreshingStream) {
            LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth(.34f).padding(top = 64.dp))
        }
        if (speechState.ready && hudVisible) {
            VideoStatusChip(
                if (speechState.generated) "Generated English subtitles • offline"
                else if (speechState.enabled) speechState.status
                else "Whisper ready • offline",
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 96.dp),
                active = speechState.enabled || speechState.generated
            )
        }
        downloadStatus?.let { Text(it, modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp), color = MaterialTheme.colorScheme.onSurface) }
        if (controlsLocked) TextButton(onClick = { controlsLocked = false; hudVisible = true }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) { Text("Unlock controls") }
        if (hudVisible && !controlsLocked) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onBack) { Text("Back") }
                    Text("Stream", style = MaterialTheme.typography.titleMedium)
                    Text(playbackHeight.takeIf { it > 0 }?.let { "${it}p" } ?: "Auto", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { player.seekBack() }) { Text("◄◄ -10s") }
                        FilledTonalButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                            Text(if (playing) "⏸️" else "▶️")
                        }
                        TextButton(onClick = { player.seekForward() }) { Text("10s ►►") }
                    }
                    CinematicVideoDock(Modifier.fillMaxWidth()) {
                        VideoDockAction(
                            label = "English Audio CC",
                            status = when {
                                speechState.generated -> "Generated track active"
                                speechState.enabled -> speechState.status.take(32)
                                speechState.ready -> "Whisper ready • offline"
                                else -> "Model required"
                            },
                            selected = speechState.enabled || speechState.generated,
                            onClick = { showSpeechSettings = true }
                        )
                        VideoDockAction(
                            label = "Generate Full Subtitles",
                            status = "English SRT",
                            onClick = { showSpeechSettings = true }
                        )
                        VideoDockAction(
                            label = "Live OCR",
                            status = if (liveTranslationEnabled) "On" else "Off",
                            selected = liveTranslationEnabled,
                            onClick = { liveTranslationEnabled = !liveTranslationEnabled }
                        )
                        VideoDockAction(
                            label = "Download ${downloadQuality.label}",
                            status = "Quality ceiling",
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        MediaDownloadManager(context).enqueue(
                                            sourcePageUrl ?: activeUrl,
                                            title = null,
                                            quality = downloadQuality,
                                            sourcePageUrl = sourcePageUrl,
                                            headers = activeHeaders
                                        )
                                    }.onSuccess {
                                        downloadStatus = "Download queued at " + downloadQuality.label
                                    }.onFailure {
                                        downloadStatus = it.message ?: "Download failed"
                                    }
                                }
                            }
                        )
                        VideoDockAction(
                            label = "Quality",
                            status = downloadQuality.label,
                            onClick = {
                                downloadQuality = when (downloadQuality) {
                                    com.mangalens.download.DownloadQuality.BEST -> com.mangalens.download.DownloadQuality.P2160
                                    com.mangalens.download.DownloadQuality.P2160 -> com.mangalens.download.DownloadQuality.P1440
                                    com.mangalens.download.DownloadQuality.P1440 -> com.mangalens.download.DownloadQuality.P1080
                                    com.mangalens.download.DownloadQuality.P1080 -> com.mangalens.download.DownloadQuality.P720
                                    com.mangalens.download.DownloadQuality.P720 -> com.mangalens.download.DownloadQuality.P480
                                    com.mangalens.download.DownloadQuality.P480 -> com.mangalens.download.DownloadQuality.BEST
                                }
                            }
                        )
                        VideoDockAction(
                            label = "Lock",
                            status = "Controls",
                            onClick = { controlsLocked = true; hudVisible = false }
                        )
                        VideoDockAction(
                            label = "Fit",
                            status = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Crop"
                                else -> "Fit"
                            },
                            onClick = {
                                resizeMode = when (resizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            }
                        )
                        VideoDockAction(
                            label = "Fullscreen",
                            status = playbackHeight.takeIf { it > 0 }?.let { "${it}p" } ?: "Auto",
                            onClick = { hudVisible = false }
                        )
                    }
                }
            }
        }
    }
}
