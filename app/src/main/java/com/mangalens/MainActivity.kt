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
import androidx.compose.runtime.SideEffect
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.withResumed
import androidx.navigation.compose.rememberNavController
import com.mangalens.core.verification.VerificationDialog
import com.mangalens.core.verification.VerificationState
import com.mangalens.ui.MangaLensNavGraph
import com.mangalens.ui.MangaLensViewModel
import com.mangalens.ui.theme.MangaLensTheme
import com.mangalens.diagnostics.StartupScalarTrace
import com.mangalens.diagnostics.StartupStage

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MainActivity : ComponentActivity(), com.mangalens.ui.video.PlaybackWindowHost {
    override val playbackWindow by lazy { com.mangalens.ui.video.PlaybackWindowController(this) }
    private var startupObservationView: android.view.View? = null
    private var startupDrawListener: android.view.ViewTreeObserver.OnDrawListener? = null
    private var startupLayoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
    private var pendingAppEntry by mutableStateOf<String?>(null)
    private fun readAppEntryIntent(value: android.content.Intent?) {
        if (value?.action != android.content.Intent.ACTION_VIEW) return
        val raw = value.dataString
        if (com.mangalens.core.router.MangaLensAppEntryPolicy.parse(raw) != null) {
            pendingAppEntry = raw
            pendingVideoEntry = null
            widgetRequest = null
        }
    }
    private var pendingVideoEntry by mutableStateOf<String?>(null)
    private fun readVideoIntent(value: android.content.Intent?) {
        if (value?.action != android.content.Intent.ACTION_VIEW) return
        val raw = value.dataString
        if (com.mangalens.ui.video.parseVideoPlaybackEntry(raw) != null) { pendingVideoEntry = raw; pendingAppEntry = null; widgetRequest = null }
    }
    override fun onStart() { super.onStart(); playbackWindow.onStart() }
    override fun onStop() { playbackWindow.onStop(); super.onStop() }
    override fun onDestroy() { removeStartupObservers(); playbackWindow.close(); super.onDestroy() }
    override fun onUserLeaveHint() { playbackWindow.onUserLeaveHint(); super.onUserLeaveHint() }
    override fun onPictureInPictureModeChanged(inPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig)
        playbackWindow.onPictureInPictureModeChanged(inPictureInPictureMode)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingVideoEntry?.let { outState.putString("pending_video_entry", it) }
        pendingAppEntry?.let { outState.putString("pending_app_entry", it) }
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
        if (route in setOf("translate", "orez", "library", "downloads", "local_video")) {
            pendingAppEntry = null; pendingVideoEntry = null
            widgetRequest = route to android.os.SystemClock.elapsedRealtimeNanos()
        }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        StartupScalarTrace.enableForQa(intent.getBooleanExtra(StartupScalarTrace.OPT_IN_EXTRA, false))
        super.onNewIntent(intent); setIntent(intent); readWidgetIntent(intent); readShareIntent(intent); readVideoIntent(intent); readAppEntryIntent(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        StartupScalarTrace.enableForQa(intent?.getBooleanExtra(StartupScalarTrace.OPT_IN_EXTRA, false) == true)
        StartupScalarTrace.measure(StartupStage.ACTIVITY_CREATE) {
            super.onCreate(savedInstanceState)
            StartupScalarTrace.measure(StartupStage.ACTIVITY_WINDOW_SETUP) {
                enableEdgeToEdge()
                playbackWindow.restore(savedInstanceState)
            }
            if (savedInstanceState != null) pendingVideoEntry = savedInstanceState.getString("pending_video_entry")
                ?.takeIf { com.mangalens.ui.video.parseVideoPlaybackEntry(it) != null }
            if (savedInstanceState != null) pendingAppEntry = savedInstanceState.getString("pending_app_entry")
                ?.takeIf { com.mangalens.core.router.MangaLensAppEntryPolicy.parse(it) != null }
            if (savedInstanceState == null) { readWidgetIntent(intent); readShareIntent(intent); readVideoIntent(intent); readAppEntryIntent(intent) }

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
                val startupVmLookup = StartupScalarTrace.begin(StartupStage.VM_LOOKUP)
                val viewModel: MangaLensViewModel = viewModel()
                StartupScalarTrace.end(startupVmLookup)
                SideEffect { StartupScalarTrace.markOnce(StartupStage.CONTENT_COMPOSITION_COMMITTED) }
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
                    LaunchedEffect(pendingAppEntry) {
                        val raw = pendingAppEntry ?: return@LaunchedEffect
                        lifecycle.withResumed {
                            if (pendingAppEntry == raw) {
                                val entry = com.mangalens.core.router.MangaLensAppEntryPolicy.parse(raw)
                                pendingAppEntry = null
                                when (entry) {
                                    is com.mangalens.core.router.MangaLensAppEntry.Reader -> navController.navigate("reader_link?chapter=" + entry.chapterId)
                                    is com.mangalens.core.router.MangaLensAppEntry.RecentVideo -> navController.navigate("recent_video?key=" + entry.key)
                                    is com.mangalens.core.router.MangaLensAppEntry.Downloads -> navController.navigate("downloads?focus=" + entry.id)
                                    is com.mangalens.core.router.MangaLensAppEntry.Orez -> navController.navigate("orez?request=" + android.net.Uri.encode(entry.request))
                                    null -> Unit
                                }
                            }
                        }
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
                        onOpenSavedTextResult = viewModel::openSavedTextResult,
                        onSavedTextReaderLeft = viewModel::leaveSavedTextReader,
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
                        onOpenSavedChapterEntry = viewModel::openSavedChapterEntry,
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
            StartupScalarTrace.markOnce(StartupStage.CONTENT_INSTALLED)
            installStartupObservers()
        }
    }

    /** Opt-in observations only: these do not alter layout or report a displayed frame. */
    private fun installStartupObservers() {
        if (!StartupScalarTrace.isEnabled || startupObservationView != null) return
        val view = window.decorView
        startupObservationView = view
        startupLayoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            if (view.width > 0 && view.height > 0) {
                StartupScalarTrace.markOnce(StartupStage.WINDOW_FIRST_NONZERO_LAYOUT)
                startupLayoutListener?.let { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(it) }
                startupLayoutListener = null
            }
        }.also { view.viewTreeObserver.addOnGlobalLayoutListener(it) }
        startupDrawListener = object : android.view.ViewTreeObserver.OnDrawListener {
            private var observed = false
            override fun onDraw() {
                if (observed) return
                observed = true
                StartupScalarTrace.markOnce(StartupStage.WINDOW_DRAW_ENTERED)
                // Android forbids removing an OnDrawListener from inside its draw callback.
                view.post {
                    if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(this)
                    if (startupDrawListener === this) startupDrawListener = null
                }
            }
        }.also { view.viewTreeObserver.addOnDrawListener(it) }
    }

    private fun removeStartupObservers() {
        startupObservationView?.viewTreeObserver?.takeIf { it.isAlive }?.let { observer ->
            startupDrawListener?.let(observer::removeOnDrawListener)
            startupLayoutListener?.let(observer::removeOnGlobalLayoutListener)
        }
        startupObservationView = null
        startupDrawListener = null
        startupLayoutListener = null
    }
}
