package com.mangalens

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.mangalens.core.verification.VerificationDialog
import com.mangalens.core.verification.VerificationState
import com.mangalens.ui.MangaLensNavGraph
import com.mangalens.ui.MangaLensViewModel
import com.mangalens.ui.theme.MangaLensTheme

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MainActivity : ComponentActivity(), com.mangalens.ui.video.PlaybackWindowHost {
    override val playbackWindow by lazy { com.mangalens.ui.video.PlaybackWindowController(this) }
    private var pendingVideoEntry by mutableStateOf<String?>(null)
    private fun readVideoIntent(value: android.content.Intent?) {
        if (value?.action != android.content.Intent.ACTION_VIEW) return
        val raw = value.dataString
        if (com.mangalens.ui.video.parseVideoPlaybackEntry(raw) != null) pendingVideoEntry = raw
    }
    override fun onStart() { super.onStart(); playbackWindow.onStart() }
    override fun onStop() { playbackWindow.onStop(); super.onStop() }
    override fun onDestroy() { playbackWindow.close(); super.onDestroy() }
    override fun onUserLeaveHint() { playbackWindow.onUserLeaveHint(); super.onUserLeaveHint() }
    override fun onPictureInPictureModeChanged(inPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig)
        playbackWindow.onPictureInPictureModeChanged(inPictureInPictureMode)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingVideoEntry?.let { outState.putString("pending_video_entry", it) }
        playbackWindow.save(outState); super.onSaveInstanceState(outState)
    }
    private data class SharedContent(val url: String?, val files: List<android.net.Uri>, val video: Boolean)
    private var sharedContent by mutableStateOf<SharedContent?>(null)
    @Suppress("DEPRECATION")
    private fun readShareIntent(value: android.content.Intent?) {
        if (value?.action !in setOf(android.content.Intent.ACTION_SEND, android.content.Intent.ACTION_SEND_MULTIPLE)) return
        value ?: return
        val text = value.getStringExtra(android.content.Intent.EXTRA_TEXT).orEmpty().take(16384)
        val url = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE).find(text)?.value
            ?.trimEnd('.', ',', ')', ']')?.takeIf(com.mangalens.core.router.UrlEngineRouter::isSafeWebUrl)
        val files = if (value.action == android.content.Intent.ACTION_SEND_MULTIPLE)
            value.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM).orEmpty()
        else listOfNotNull(value.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM))
        sharedContent = SharedContent(url, files.filter { it.scheme == "content" }.distinct().take(100), value.type?.startsWith("video/") == true)
    }
    private var widgetRequest by mutableStateOf<Pair<String, Long>?>(null)
    private fun readWidgetIntent(value: android.content.Intent?) {
        if (value?.action != "com.mangalens.WIDGET" || value.data?.host != "widget") return
        val route = value.data?.lastPathSegment ?: return
        if (route in setOf("translate", "orez", "library", "downloads")) widgetRequest = route to android.os.SystemClock.elapsedRealtimeNanos()
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); setIntent(intent); readWidgetIntent(intent); readShareIntent(intent); readVideoIntent(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        playbackWindow.restore(savedInstanceState)
        if (savedInstanceState != null) pendingVideoEntry = savedInstanceState.getString("pending_video_entry")
            ?.takeIf { com.mangalens.ui.video.parseVideoPlaybackEntry(it) != null }
        if (savedInstanceState == null) { readWidgetIntent(intent); readShareIntent(intent); readVideoIntent(intent) }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                7401
            )
        }

        setContent {
            val viewModel: MangaLensViewModel = viewModel()
            val state by viewModel.state.collectAsState()
            MangaLensTheme(themeMode = state.themeMode) {
                val navController = rememberNavController()
                LaunchedEffect(sharedContent) {
                    val content = sharedContent ?: return@LaunchedEffect
                    sharedContent = null
                    when {
                        content.files.isNotEmpty() && content.video -> navController.navigate("local_player?uri=" + android.net.Uri.encode(content.files.first().toString()))
                        content.files.isNotEmpty() -> { viewModel.importLocalImages(content.files); navController.navigate("reader") }
                        content.url != null -> {
                            val mode = com.mangalens.core.router.UrlEngineRouter().classifyUrl(content.url)
                            viewModel.setUrl(content.url)
                            viewModel.setMode(mode)
                            if (mode != com.mangalens.core.model.ContentType.GENERIC_WEB) viewModel.ingest()
                            navController.navigate(when(mode) {
                                com.mangalens.core.model.ContentType.IMAGE_CHAPTER -> "reader"
                                com.mangalens.core.model.ContentType.VIDEO_STREAM -> "video"
                                com.mangalens.core.model.ContentType.GENERIC_WEB -> "web"
                            })
                        }
                    }
                }
                val widgetImporter = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    if (uris.isNotEmpty()) { viewModel.importLocalImages(uris); navController.navigate("reader") }
                }
                LaunchedEffect(widgetRequest) {
                    val request = widgetRequest ?: return@LaunchedEffect
                    widgetRequest = null
                    if (request.first == "translate") widgetImporter.launch(com.mangalens.core.reader.DocumentImporter.MIME_TYPES)
                    else navController.navigate(request.first) { launchSingleTop = true }
                }
                LaunchedEffect(pendingVideoEntry) {
                    val raw = pendingVideoEntry ?: return@LaunchedEffect
                    when (val entry = com.mangalens.ui.video.parseVideoPlaybackEntry(raw)) {
                        is com.mangalens.ui.video.VideoPlaybackEntry.Url -> {
                            viewModel.setUrl(entry.url)
                            viewModel.setMode(com.mangalens.core.model.ContentType.VIDEO_STREAM)
                            viewModel.ingest()
                            navController.navigate("video") { launchSingleTop = true }
                        }
                        is com.mangalens.ui.video.VideoPlaybackEntry.Session -> {
                            val source = com.mangalens.ui.video.PlaybackSessions.find(entry.sessionId)?.sourceSnapshot()
                            when {
                                source == null -> android.widget.Toast.makeText(this@MainActivity, "Playback ended. Open a video again.", android.widget.Toast.LENGTH_LONG).show()
                                source.online -> {
                                    viewModel.acceptResolvedVideo(source.uri, source.headers, source.referer ?: source.uri, source.audioUrl, source.audioHeaders)
                                    navController.navigate("video") { launchSingleTop = true }
                                }
                                else -> navController.navigate("local_player?uri=" + android.net.Uri.encode(source.uri)) { launchSingleTop = true }
                            }
                        }
                        null -> Unit
                    }
                    pendingVideoEntry = null
                }
                val verification by viewModel.captchaBridge.state.collectAsState()
                MangaLensNavGraph(
                    navController = navController,
                    state = state,
                    onUrlChanged = viewModel::setUrl,
                    onPaste = {
                        runCatching {
                            val clipboard =
                                getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = clipboard?.primaryClip
                            if (
                                clip != null &&
                                clip.itemCount > 0 &&
                                (
                                    clip.description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true ||
                                    clip.description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) == true
                                )
                            ) {
                                val text = clip.getItemAt(0).coerceToText(this).toString().trim()
                                if (text.isNotBlank()) viewModel.setUrl(text)
                            }
                        }
                    },
                    onModeSelected = viewModel::setMode,
                    onIngest = viewModel::ingest,
                    onIngestAndTranslate = viewModel::ingestAndTranslate,
                    onTranslatePage = viewModel::translatePage,
                    onRetryReaderPage = viewModel::retryReaderPage,
                    readerMemory = viewModel.readerMemory,
                    onTranslateChapter = viewModel::translateChapter,
                    onTargetLanguageChanged = viewModel::setTargetLanguage,
                    onTranslationStyleChanged = viewModel::setTranslationStyle,
                    onCustomTranslationStyleChanged = viewModel::setCustomTranslationStyle,
                    onDownloadChapter = viewModel::downloadChapter,
                    onThemeModeChanged = viewModel::setThemeMode,
                    onMangaTranslationChanged = viewModel::setMangaTranslationEnabled,
                    onVideoTranslationChanged = viewModel::setVideoTranslationEnabled,
                    onWebTranslationChanged = viewModel::setWebTranslationEnabled,
                    onAdBlockEnabledChanged = viewModel::setAdBlockEnabled,
                    onResetAdBlockStats = viewModel::resetAdBlockStats,
                    onDeleteSavedChapter = viewModel::deleteSavedChapter,
                    onOpenSavedChapter = viewModel::openSavedChapter,
                    onReadingPositionChanged = viewModel::saveReadingPosition,
                    onChapterDetails = viewModel::setChapterDetails,
                    onChapterMetadata = viewModel::updateChapterMetadata,
                    onTranslationPaused = viewModel::pauseTranslation,
                    onTranslationCancelled = viewModel::cancelTranslation,
                    onImportImages = viewModel::importLocalImages,
                    onResolvedVideo = viewModel::acceptResolvedVideo,
                    onResolvedCaptionedVideo = viewModel::acceptResolvedVideo,
                    onIngestionCancelled = viewModel::cancelIngestion,
                    onVideoRefreshed = viewModel::acceptVideoRefresh,
                    onVideoReady = viewModel::acceptVideoReady
                )
                val verificationRequest = verification.request
                if (
                    verification.state == VerificationState.VERIFICATION_REQUIRED &&
                    verificationRequest != null
                ) {
                    VerificationDialog(
                        request = verificationRequest,
                        onVerified = { cookie, _ -> viewModel.ingestWithCookie(cookie) },
                        onDismiss = { viewModel.captchaBridge.reset() }
                    )
                }
            }
        }
    }
}
