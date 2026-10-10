package com.mangalens.ui.video

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mangalens.download.MediaDownloadManager
import com.mangalens.download.MediaLinkResolver
import com.mangalens.download.YtDlpSiteMediaExtractor
import com.mangalens.download.MediaResolutionTimeoutException
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeVideoPlayer(
    url: String,
    translationEnabled: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenWeb: (String) -> Unit = {},
    sourcePageUrl: String? = null,
    requestHeaders: Map<String, String> = emptyMap(),
    audioUrl: String? = null,
    audioHeaders: Map<String, String> = emptyMap(),
    resolutionId: String? = null,
    onSourceRefreshed: (VideoPlaybackSelection, VideoPlaybackSelection, () -> Boolean) -> Boolean = { _, _, commit -> commit() },
    onReadySource: (VideoReadyObservation) -> Unit = {},
    providerCaptions: com.mangalens.download.ProviderCaptionInventory? = null,
    videoMimeType: String? = null, audioMimeType: String? = null
) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var controlsLocked by remember { mutableStateOf(false) }
    var playbackError by remember(url, sourcePageUrl) { mutableStateOf<String?>(null) }
    var hudVisible by remember { mutableStateOf(true) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var downloadQuality by remember { mutableStateOf(com.mangalens.download.DownloadQuality.BEST) }
    var downloadStatus by remember { mutableStateOf<String?>(null) }
    var liveTranslationEnabled by remember { mutableStateOf(false) }
    var showSpeechSettings by remember { mutableStateOf(false) }
    var showPlaybackSettings by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val targetLanguage = context.getSharedPreferences("mangalens_preferences", Context.MODE_PRIVATE).getString("translation_target", "hi") ?: "hi"
    val playerVm: LocalVideoPlayerViewModel = viewModel()
    val player = playerVm.player
    val playbackLifecycle by playerVm.lifecycle.collectAsState()
    LaunchedEffect(playbackLifecycle.nativeCaption) { if (playbackLifecycle.nativeCaption != null) liveTranslationEnabled = false }
    val speechState by playerVm.speech.state.collectAsState()
    var playing by remember { mutableStateOf(player.isPlaying) }
    var playbackHeight by remember { mutableIntStateOf(player.videoSize.height) }
    val scope = rememberCoroutineScope()
    val resolver = remember(context) {
        MediaLinkResolver(
            siteExtractor = YtDlpSiteMediaExtractor(context.applicationContext, allowSeparateStreams = true),
            timeoutMs = 45_000L
        )
    }
    var activeUrl by remember(url, sourcePageUrl) { mutableStateOf(url) }
    var activeHeaders by remember(url, sourcePageUrl, requestHeaders) { mutableStateOf(requestHeaders) }
    var activeAudioUrl by remember(url, sourcePageUrl, audioUrl) { mutableStateOf(audioUrl) }
    var activeAudioHeaders by remember(url, sourcePageUrl, audioHeaders) { mutableStateOf(audioHeaders) }
    var activeVideoMime by remember(url, sourcePageUrl, videoMimeType) { mutableStateOf(videoMimeType) }
    var activeAudioMime by remember(url, sourcePageUrl, audioUrl, audioMimeType) { mutableStateOf(audioMimeType.takeIf { audioUrl != null }) }
    var activeResolutionId by remember(url, sourcePageUrl, resolutionId) { mutableStateOf(resolutionId) }
    var activeProviderCaptions by remember(url, sourcePageUrl, resolutionId, providerCaptions) { mutableStateOf(providerCaptions?.captureSnapshot()) }
    var readyBinding by remember(player) { mutableStateOf<Pair<VideoPlaybackSelection, Long>?>(null) }
    var refreshingStream by remember(url, sourcePageUrl) { mutableStateOf(false) }
    var refreshAttempts by remember(url, sourcePageUrl) { mutableIntStateOf(0) }
    var refreshJob by remember(url, sourcePageUrl) { mutableStateOf<Job?>(null) }
    val refreshRequests = remember(url, sourcePageUrl) { PlaybackRefreshRequests() }
    val sourceForOpen = remember(url, sourcePageUrl) { playbackSourcePage(sourcePageUrl, url) }
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnOpenWeb by rememberUpdatedState(onOpenWeb)
    val currentOnSourceRefreshed by rememberUpdatedState(onSourceRefreshed)
    val currentOnReadySource by rememberUpdatedState(onReadySource)
    val presentationLease = PlayerPresentationLifecycle(playerVm, playerView)
    val fullSubtitleGenerator = remember(playerVm.speech, scope) {
        FullVideoSubtitleGenerator(context.applicationContext, playerVm.speech, scope,
            attachmentAllowed = playerVm::canAttachGenerated, attachManually = playerVm::selectGeneratedCaption)
    }
    val subtitleSource = remember(activeUrl, activeHeaders, activeAudioUrl, activeAudioHeaders, sourcePageUrl, activeResolutionId, activeProviderCaptions) {
        SubtitleMediaSource(
            uri = activeAudioUrl ?: activeUrl,
            headers = if (activeAudioUrl != null) activeAudioHeaders else activeHeaders,
            cacheKey = sourcePageUrl ?: url,
            label = "Online video", providerCaptions = activeProviderCaptions, sourceResolutionId = activeResolutionId.takeIf { activeProviderCaptions != null }
        )
    }
    LaunchedEffect(subtitleSource) { fullSubtitleGenerator.bind(subtitleSource) }

    fun activeSelection(): VideoPlaybackSelection? = activeResolutionId?.let { id ->
        VideoPlaybackSelection(id, activeUrl, activeHeaders.toMap(), sourcePageUrl, activeAudioUrl, activeAudioHeaders.toMap(),
            providerCaptions = activeProviderCaptions?.captureSnapshot(), videoMimeType = activeVideoMime, audioMimeType = activeAudioMime)
    }

    fun publishReadySource() {
        val bound = readyBinding ?: return
        VideoPlaybackPublication.readyObservation(bound.first, bound.second, playerVm.sourceRevision,
            player.currentMediaItem?.localConfiguration?.uri?.toString(),
            player.playbackState == androidx.media3.common.Player.STATE_READY, player.duration,
            player.isCurrentMediaItemLive, player.isCurrentMediaItemDynamic)?.let(currentOnReadySource)
    }

    fun cancelRefresh(message: String? = null) {
        refreshRequests.cancel()
        refreshJob?.cancel()
        refreshJob = null
        refreshingStream = false
        message?.let { playbackError = it }
    }

    fun goBack() {
        cancelRefresh()
        currentOnBack()
    }

    fun openSource() {
        val page = sourceForOpen ?: return
        cancelRefresh()
        player.pause()
        currentOnOpenWeb(page)
    }

    fun refreshFromSource() {
        val page = sourcePageUrl?.takeIf { playbackSourcePage(it, "") == it }
        if (page == null) {
            playbackError = null
            player.prepare()
            player.play()
            return
        }
        if (refreshingStream) return
        val request = refreshRequests.begin()
        val refreshFromRevision = playerVm.sourceRevision
        val refreshFromSelection = activeSelection()?.captured()
        refreshingStream = true
        playbackError = null
        val playbackQuality = if (refreshAttempts <= 1)
            com.mangalens.download.DownloadQuality.BEST
        else com.mangalens.download.DownloadQuality.P1080
        refreshJob = scope.launch {
            try {
                val resolved = resolver.resolveCancellable(page, playbackQuality)?.takeIf(::isPlayableRefresh)
                currentCoroutineContext().ensureActive()
                if (!refreshRequests.isCurrent(request)) return@launch
                if (resolved == null) {
                    playbackError = "The source did not expose an accessible playable stream. Retry or open the source page."
                    return@launch
                }
                val pageRef = resolved.sourcePageUrl ?: page
                val videoCookie = runCatching { CookieManager.getInstance().getCookie(resolved.url) }.getOrNull().orEmpty()
                val resolvedHeaders = buildMap {
                    putAll(resolved.headers)
                    if (videoCookie.isNotBlank() && keys.none { it.equals("Cookie", true) }) put("Cookie", videoCookie)
                    if (keys.none { it.equals("Referer", true) }) put("Referer", pageRef)
                    if (keys.none { it.equals("User-Agent", true) }) put("User-Agent", MediaRequestContext.USER_AGENT)
                    if (keys.none { it.equals("Accept", true) }) put("Accept", "*/*")
                }
                val resolvedAudioHeaders = resolved.audioUrl?.let { audioStream ->
                    val audioCookie = runCatching { CookieManager.getInstance().getCookie(audioStream) }.getOrNull().orEmpty()
                    buildMap {
                        putAll(resolved.audioHeaders)
                        if (audioCookie.isNotBlank() && keys.none { it.equals("Cookie", true) }) put("Cookie", audioCookie)
                        if (keys.none { it.equals("Referer", true) }) put("Referer", pageRef)
                        if (keys.none { it.equals("User-Agent", true) }) put("User-Agent", MediaRequestContext.USER_AGENT)
                        if (keys.none { it.equals("Accept", true) }) put("Accept", "*/*")
                    }
                }.orEmpty()
                val expectedRoot = refreshFromSelection
                val replacement = VideoPlaybackPublication.capture(resolved.url, resolvedHeaders, sourcePageUrl,
                    resolved.audioUrl, resolvedAudioHeaders, providerCaptions = resolved.providerCaptions,
                    videoMimeType = resolved.mimeType, audioMimeType = resolved.audioMimeType)
                refreshRequests.publish(
                    request,
                    PlaybackStreamIdentity(activeUrl, activeHeaders, activeAudioUrl, activeAudioHeaders, activeVideoMime, activeAudioMime),
                    PlaybackStreamIdentity(replacement.videoUrl, replacement.videoHeaders, replacement.audioUrl, replacement.audioHeaders, replacement.videoMimeType, replacement.audioMimeType),
                    apply = {
                        activeUrl = resolved.url
                        activeHeaders = replacement.videoHeaders
                        activeAudioUrl = replacement.audioUrl
                        activeAudioHeaders = replacement.audioHeaders
                        activeVideoMime = replacement.videoMimeType
                        activeAudioMime = replacement.audioMimeType
                        activeResolutionId = if (expectedRoot == null) null else replacement.resolutionId
                        activeProviderCaptions = replacement.providerCaptions?.captureSnapshot()?.takeIf { activeResolutionId != null }
                        playbackError = null
                    },
                    restartUnchanged = { player.prepare(); player.play() },
                    beforeApply = {
                        val commit = {
                            playerVm.openHttp(replacement.videoUrl, referer = replacement.pageUrl, headers = replacement.videoHeaders,
                                audioUrl = replacement.audioUrl, audioHeaders = replacement.audioHeaders,
                                refreshFromRevision = refreshFromRevision,
                                sourceResolutionId = replacement.resolutionId.takeIf { expectedRoot != null },
                                providerCaptions = replacement.providerCaptions.takeIf { expectedRoot != null },
                                videoMimeType = replacement.videoMimeType, audioMimeType = replacement.audioMimeType).also { accepted ->
                                if (accepted) readyBinding = if (expectedRoot == null) null else replacement to playerVm.sourceRevision
                            }
                        }
                        if (expectedRoot == null) commit() else currentOnSourceRefreshed(expectedRoot, replacement, commit)
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (refreshRequests.isCurrent(request)) {
                    playbackError = if (failure is MediaResolutionTimeoutException) failure.message
                        else com.mangalens.download.MediaSourceFailure.from(failure).message
                }
            } finally {
                if (refreshRequests.finish(request)) {
                    refreshingStream = false
                    refreshJob = null
                }
            }
        }
    }

    BackHandler { goBack() }
    DisposableEffect(refreshRequests) {
        onDispose {
            refreshRequests.cancel()
            refreshJob?.cancel()
        }
    }

    LaunchedEffect(activeUrl, sourcePageUrl, activeHeaders, activeAudioUrl, activeAudioHeaders, activeResolutionId, activeProviderCaptions, activeVideoMime, activeAudioMime) {
        playbackError = null
        val accepted = playerVm.openHttp(
            activeUrl,
            referer = sourcePageUrl,
            headers = activeHeaders,
            audioUrl = activeAudioUrl,
            audioHeaders = activeAudioHeaders,
            sourceResolutionId = activeResolutionId,
            providerCaptions = activeProviderCaptions, videoMimeType = activeVideoMime, audioMimeType = activeAudioMime
        )
        readyBinding = if (accepted) activeSelection()?.let { it to playerVm.sourceRevision } else null
        if (accepted) publishReadySource()
    }
    LaunchedEffect(translationEnabled) { if (!translationEnabled) liveTranslationEnabled = false }

    LaunchedEffect(hudVisible) {
        if (hudVisible) {
            delay(2000)
            hudVisible = false
        }
    }

    DisposableEffect(player, url, sourcePageUrl, requestHeaders, audioUrl, audioHeaders, resolutionId, refreshRequests, videoMimeType, audioMimeType) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { playbackHeight = videoSize.height }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val refreshable = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
                if (refreshable && !sourcePageUrl.isNullOrBlank() && refreshAttempts < 2 && !refreshingStream) {
                    refreshAttempts++
                    playbackError = "Refreshing the playable stream…"
                    refreshFromSource()
                } else {
                    playbackError = "Playback failed (${error.errorCodeName}). Retry or refresh the source stream."
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY && !refreshingStream) {
                    playbackError = null
                    refreshAttempts = 0
                }
                if (state == androidx.media3.common.Player.STATE_READY) publishReadySource()
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { publishReadySource() }
        }
        player.addListener(listener)
        publishReadySource()
        onDispose { player.removeListener(listener) }
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
                    this.player = player.takeIf { playerVm.session.policy.ownsPresentation(presentationLease) }
                    playerView = this
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = false
                    this.resizeMode = resizeMode
                }
            },
            update = { it.player = player.takeIf { playerVm.session.policy.ownsPresentation(presentationLease) }; it.resizeMode = resizeMode; playerView = it },
            modifier = Modifier.fillMaxSize()
        )

        // Visual OCR and decoded-audio captions deliberately occupy separate lanes.
        // This avoids the two translation systems covering each other during playback.
        LiveVideoOcrTranslationOverlay(
            enabled = liveTranslationEnabled,
            targetLanguage = targetLanguage,
            playerView = playerView,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = if (hudVisible && !playbackLifecycle.inPictureInPicture) 76.dp else 8.dp)
        )

        LiveAudioSubtitleOverlay(
            playerVm.speech,
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp)
                .padding(bottom = if (playbackLifecycle.inPictureInPicture) 8.dp else if (hudVisible) 154.dp else 26.dp)
        )
        if (showPlaybackSettings && !playbackLifecycle.inPictureInPicture) {
            ModalBottomSheet(onDismissRequest = { showPlaybackSettings = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(20.dp).padding(bottom = 24.dp)
                ) {
                    PlayerLifecycleControlsPanel(playerVm)
                    HorizontalDivider()
                    PlaybackTrackControlsPanel(player)
                }
            }
        }
        if (showSpeechSettings && !playbackLifecycle.inPictureInPicture) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showSpeechSettings = false }) {
                Surface(shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                        SubtitleToolsPanel(playerVm.speech, fullSubtitleGenerator, subtitleSource)
                        HorizontalDivider()
                        ImportedCaptionControls(playerVm)
                        TextButton(onClick = { showSpeechSettings = false }) { Text("Close") }
                    }
                }
            }
        }
        if (refreshingStream && !playbackLifecycle.inPictureInPicture) {
            Surface(
                Modifier.align(Alignment.Center).padding(24.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Refreshing playable stream…", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (refreshAttempts <= 1) "Trying the best accessible source"
                        else "Trying a compatibility fallback up to 1080p",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { cancelRefresh("Stream refresh cancelled. Retry or open the source page.") }) { Text("Cancel") }
                    TextButton(onClick = { goBack() }) { Text("Back") }
                    TextButton(onClick = { openSource() }, enabled = sourceForOpen != null) { Text("Open source page") }
                }
            }
        } else if (playbackError != null && !playbackLifecycle.inPictureInPicture) {
            Surface(Modifier.align(Alignment.Center).padding(24.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(playbackError!!)
                    TextButton(onClick = { refreshFromSource() }) { Text("Retry / refresh stream") }
                    TextButton(onClick = { goBack() }) { Text("Back") }
                    TextButton(onClick = { openSource() }, enabled = sourceForOpen != null) { Text("Open source page") }
                }
            }
        }
        if (refreshingStream && !playbackLifecycle.inPictureInPicture) {
            LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth(.34f).padding(top = 64.dp))
        }
        if (speechState.ready && hudVisible && !playbackLifecycle.inPictureInPicture) {
            VideoStatusChip(
                if (speechState.generated) "Generated English subtitles • offline"
                else if (speechState.enabled) speechState.status
                else "Whisper ready • offline",
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 96.dp),
                active = speechState.enabled || speechState.generated
            )
        }
        if (!playbackLifecycle.inPictureInPicture) downloadStatus?.let { Text(it, modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp), color = MaterialTheme.colorScheme.onSurface) }
        if (controlsLocked && !playbackLifecycle.inPictureInPicture) TextButton(onClick = { controlsLocked = false; hudVisible = true }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) { Text("Unlock controls") }
        if (hudVisible && !controlsLocked && !playbackLifecycle.inPictureInPicture) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { goBack() }) { Text("Back") }
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
                            label = "Playback",
                            status = "Quality • tracks • speed",
                            onClick = { showPlaybackSettings = true }
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
                            label = "Download quality",
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
                            onClick = { (context as? PlaybackWindowHost)?.playbackWindow?.setFullscreen(true); hudVisible = false }
                        )
                    }
                }
            }
        }
    }
}
