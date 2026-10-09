package com.mangalens.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.mangalens.core.model.ContentType
import com.mangalens.orez.OrezRoute
import com.mangalens.ui.ai.OrezAiScreen
import com.mangalens.ui.downloads.DownloadsScreen
import com.mangalens.ui.components.MangaLensBottomNav
import com.mangalens.ui.home.HomeScreen
import com.mangalens.ui.library.LibraryScreen
import com.mangalens.ui.reader.MangaContinuousReader
import com.mangalens.ui.settings.SettingsScreen
import com.mangalens.ui.theme.ThemeMode
import com.mangalens.ui.video.*
import com.mangalens.ui.web.AdBlockedWebScreen

@Composable
fun MangaLensNavGraph(
    navController: NavHostController,
    state: MangaLensUiState,
    onUrlChanged: (String) -> Unit,
    onPaste: () -> Unit,
    onModeSelected: (ContentType) -> Unit,
    onIngest: () -> Unit,
    onIngestAndTranslate: () -> Unit,
    onTranslatePage: (com.mangalens.core.reader.ChapterPage) -> Unit,
    onTranslateChapter: () -> Unit,
    onTargetLanguageChanged: (String) -> Unit,
    onTranslationStyleChanged: (String) -> Unit,
    onCustomTranslationStyleChanged: (String) -> Unit,
    onDownloadChapter: () -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onMangaTranslationChanged: (Boolean) -> Unit,
    onVideoTranslationChanged: (Boolean) -> Unit,
    onWebTranslationChanged: (Boolean) -> Unit,
    onAdBlockEnabledChanged: (Boolean) -> Unit,
    onResetAdBlockStats: () -> Unit,
    onDeleteSavedChapter: (String) -> Unit,
    onOpenSavedChapter: (String) -> Unit,
    onReadingPositionChanged: (String, Int, Int) -> Unit,
    onChapterDetails: (String, Boolean, com.mangalens.core.reader.ReadingStatus) -> Unit,
    onTranslationPaused: (Boolean) -> Unit,
    onTranslationCancelled: () -> Unit,
    onImportImages: (List<android.net.Uri>) -> Unit,
    onResolvedVideo: (String, Map<String, String>, String, String?, Map<String, String>) -> Unit,
    onIngestionCancelled: () -> Unit = {},
    onVideoRefreshed: (VideoPlaybackSelection, VideoPlaybackSelection, () -> Boolean) -> Boolean = { _, _, _ -> false },
    onVideoReady: (VideoReadyObservation) -> Unit = {},
    onChapterMetadata: (suspend (String, com.mangalens.core.reader.LibraryChapterMetadata) -> Unit)? = null,
    onRetryReaderPage: (com.mangalens.core.reader.ChapterPage) -> Unit = { onIngest() },
    readerMemory: com.mangalens.core.translation.ReaderMemoryController? = null,
    onResolvedCaptionedVideo: ((com.mangalens.ui.video.SniffedMedia, String) -> Unit)? = null
) {
    val context = LocalContext.current
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // The explicit system selection grants access to this file. Cancellation
        // keeps the user in Watch instead of opening an empty fullscreen player.
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Providers without persistent grants remain usable for this session.
            }
            navController.navigate("local_player?uri=" + android.net.Uri.encode(it.toString()))
        }
    }
    val motionDuration = if (com.mangalens.ui.theme.LocalAppearance.current.reducedMotion) 0 else 220
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route?.substringBefore('?')
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { if (currentRoute !in setOf("reader", "video", "local_player", "local_video", "web")) MangaLensBottomNav(currentRoute = currentRoute) { route ->
            navController.navigate(route) {
                popUpTo("home") { saveState = true }
                launchSingleTop = true
                // Home is the root destination. Restoring its saved child stack can
                // reopen Library after the Downloads -> Library handoff.
                restoreState = route != "home"
            }
        }}
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            enterTransition = {
                fadeIn(tween(motionDuration, easing = FastOutSlowInEasing)) +
                    slideInHorizontally(tween(motionDuration, easing = FastOutSlowInEasing)) { it / 10 } +
                    scaleIn(tween(motionDuration, easing = FastOutSlowInEasing), initialScale = .985f)
            },
            exitTransition = {
                fadeOut(tween(motionDuration)) +
                    slideOutHorizontally(tween(motionDuration, easing = FastOutSlowInEasing)) { -it / 14 } +
                    scaleOut(tween(motionDuration), targetScale = .992f)
            },
            popEnterTransition = {
                fadeIn(tween(motionDuration, easing = FastOutSlowInEasing)) +
                    slideInHorizontally(tween(motionDuration, easing = FastOutSlowInEasing)) { -it / 10 } +
                    scaleIn(tween(motionDuration, easing = FastOutSlowInEasing), initialScale = .985f)
            },
            popExitTransition = {
                fadeOut(tween(motionDuration)) +
                    slideOutHorizontally(tween(motionDuration, easing = FastOutSlowInEasing)) { it / 14 } +
                    scaleOut(tween(motionDuration), targetScale = .992f)
            }
        ) {
            composable("home") {
                HomeScreen(
                    state = state,
                    onUrlChanged = onUrlChanged,
                    onPaste = onPaste,
                    onModeSelected = onModeSelected,
                    onIngest = { selectedMode ->
                        onModeSelected(selectedMode)
                        onIngest()
                        when (selectedMode) {
                            ContentType.VIDEO_STREAM -> navController.navigate("video")
                            ContentType.IMAGE_CHAPTER -> navController.navigate("reader")
                            ContentType.GENERIC_WEB -> navController.navigate("web")
                        }
                    },
                    onOpenReader = { navController.navigate("reader") },
                    onOpenVideo = { navController.navigate("video") },
                    onOpenDownloads = { navController.navigate("downloads") },
                    onOpenChapter = { url -> onUrlChanged(url); onModeSelected(ContentType.IMAGE_CHAPTER); onIngest(); navController.navigate("reader") },
                    onImportImages = { uris -> onImportImages(uris); navController.navigate("reader") },
                    onOpenSavedChapter = { id -> onOpenSavedChapter(id); navController.navigate("reader") },
                    onOpenOrez = { navController.navigate("orez") },
                    onOpenLibrary = { navController.navigate("library") },
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenWeb = { navController.navigate("web") },
                    onOpenWatch = { navController.navigate("watch") }
                )
            }
            composable("library") {
                LibraryScreen(state = state, onChapterDetails = onChapterDetails, onChapterMetadata = onChapterMetadata, onOpenSeriesMemory = { chapter -> navController.navigate("series_memory?chapter=" + chapter.orEmpty()) }, onDeleteChapter = onDeleteSavedChapter, onOpenSavedChapter = { id -> onOpenSavedChapter(id); navController.navigate("reader") }, onOpenReader = { navController.navigate("reader") }, onOpenLocalVideo = { navController.navigate("local_video") })
            }
            composable("orez") {
                OrezAiScreen(hasActiveChapter = state.pages.isNotEmpty(),
                    activeChapterId = state.activeChapter?.id?.takeIf { id -> state.library.any { saved ->
                        saved.id == id && saved.pages.map { it.index to it.localPath } == state.pages.map { it.index to it.localPath }
                    } },
                    activeUrl = state.url.takeIf { it.isNotBlank() }, onTargetLanguage = onTargetLanguageChanged,
                    library = state.library, chapterText = state.overlays.values.flatten().joinToString("\n") { it.translatedText },
                    selectedMedia = state.capturedVideoSelection()?.toOrezSelection(),
                    onOpenSubtitleResult = { result ->
                        navController.navigate("orez_subtitle/${android.net.Uri.encode(result.planId)}/${result.stepIndex}/${result.taskId}/${result.generation}")
                    },
                    onImport = { onImportImages(it); navController.navigate("reader") }) { value, route ->
                    if (value.isNotBlank()) onUrlChanged(value)
                    when (route) {
                        OrezRoute.MANGA_READER -> { onModeSelected(ContentType.IMAGE_CHAPTER); onIngest(); navController.navigate("reader") }
                        OrezRoute.VIDEO_PLAYER -> { onModeSelected(ContentType.VIDEO_STREAM); onIngest(); navController.navigate("video") }
                        OrezRoute.WEB_VIEW -> { onModeSelected(ContentType.GENERIC_WEB); navController.navigate("web") }
                        OrezRoute.DOWNLOADS -> navController.navigate("downloads")
                        OrezRoute.LIBRARY -> navController.navigate("library")
                        OrezRoute.SETTINGS -> navController.navigate("settings")
                        OrezRoute.TRANSLATE_MANGA -> { onModeSelected(ContentType.IMAGE_CHAPTER); onIngestAndTranslate(); navController.navigate("reader") }
                        OrezRoute.TRANSLATE_VIDEO -> { onModeSelected(ContentType.VIDEO_STREAM); onVideoTranslationChanged(true); onIngest(); navController.navigate("video") }
                        OrezRoute.TRANSLATE_WEB -> { onModeSelected(ContentType.GENERIC_WEB); onWebTranslationChanged(true); navController.navigate("web") }
                        OrezRoute.TRANSLATE_ACTIVE_CHAPTER -> { onTranslateChapter(); navController.navigate("reader") }
                        OrezRoute.CHAT -> Unit
                    }
                }
            }
            composable("downloads") { DownloadsScreen(onBack = { navController.popBackStack() }, appState = state, onImport = { onImportImages(it); navController.navigate("reader") }, onOpenLibrary = { navController.navigate("library") }, onOpenTools = { navController.navigate("settings") }, onPlayVideo = { value -> onUrlChanged(value); onModeSelected(ContentType.VIDEO_STREAM); onIngest(); navController.navigate("video") }, onPauseTranslation = onTranslationPaused, onCancelTranslation = onTranslationCancelled) }
            composable("orez_subtitle/{planId}/{stepIndex}/{taskId}/{generation}", arguments = listOf(
                androidx.navigation.navArgument("stepIndex") { type = androidx.navigation.NavType.IntType }
            )) { entry ->
                com.mangalens.ui.ai.OrezSubtitleResultScreen(com.mangalens.orez.agent.OrezSubtitleResultReference(
                    entry.arguments?.getString("planId").orEmpty(), entry.arguments?.getInt("stepIndex") ?: -1,
                    entry.arguments?.getString("taskId").orEmpty(), entry.arguments?.getString("generation").orEmpty()
                ), onBack = { navController.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(
                    state = state,
                    onThemeModeChanged = onThemeModeChanged,
                    onMangaTranslationChanged = onMangaTranslationChanged,
                    onVideoTranslationChanged = onVideoTranslationChanged,
                    onWebTranslationChanged = onWebTranslationChanged,
                    onTranslationStyleChanged = onTranslationStyleChanged,
                    onCustomTranslationStyleChanged = onCustomTranslationStyleChanged,
                    onAdBlockEnabledChanged = onAdBlockEnabledChanged,
                    onResetAdBlockStats = onResetAdBlockStats
                )
            }
            composable("series_memory?chapter={chapter}", arguments = listOf(androidx.navigation.navArgument("chapter") { defaultValue = "" })) { entry ->
                val id = entry.arguments?.getString("chapter").orEmpty()
                com.mangalens.ui.library.SeriesMemoryScreen(state.library.firstOrNull { it.id == id }, state.targetLanguage,
                    onBack = { navController.popBackStack() })
            }
            composable("reader") {
                val memoryState = readerMemory?.state?.collectAsState()?.value
                val memoryScope = rememberCoroutineScope()
                DisposableEffect(readerMemory) { readerMemory?.enterReader(); onDispose { readerMemory?.leaveReader() } }
                val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                DisposableEffect(lifecycle, readerMemory) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) readerMemory?.refreshVisiblePage() }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                readerMemory?.let { controller -> com.mangalens.ui.reader.ReaderMemoryPanel(controller, onRetranslate = { index -> state.pages.firstOrNull { it.index == index }?.let(onTranslatePage) }) }
                MangaContinuousReader(
                    title = state.activeChapter?.title ?: "Chapter", chapterId = state.activeChapter?.id ?: "",
                    initialPosition = state.activeChapter?.position ?: 0, initialOffset = state.activeChapter?.scrollOffset ?: 0,
                    onPositionChanged = onReadingPositionChanged, loading = state.loading, pages = state.pages,
                    translated = state.translationEnabled, translating = state.translating,
                    error = state.translationMessage ?: state.error, overlays = state.overlays,
                    translatedBackgrounds = state.translatedBackgrounds, promoPages = state.promoPages,
                    translationDone = state.translationDone, translationTotal = state.translationTotal,
                    translationPaused = state.translationPaused, onTranslationPaused = onTranslationPaused,
                    onTranslationCancelled = onTranslationCancelled,
                    targetLanguage = state.targetLanguage, onTargetLanguageChanged = onTargetLanguageChanged,
                    translationStyle = state.translationStyle, onTranslationStyleChanged = onTranslationStyleChanged,
                    onBack = { navController.popBackStack() }, onTranslate = onTranslateChapter,
                    onDownload = onDownloadChapter, onMenu = { navController.navigate("settings") },
                    onRetry = { if (state.translationError || state.translationMessage != null) onTranslateChapter() else onIngest() },
                    onRetryPage = onRetryReaderPage,
                    onVisiblePage = { index -> readerMemory?.onVisiblePage(index) },
                    onCorrectPage = readerMemory?.let { controller -> { index -> memoryScope.launch { controller.openPage(index) }; Unit } },
                    personalOverlays = memoryState?.personalOverlays.orEmpty(),
                    onOpenWeb = { navController.navigate("web") }, onLongPressPage = onTranslatePage,
                    modifier = Modifier.fillMaxSize()
                )
            }
            composable("video") {
                fun openVideoSource(source: String) {
                    if (!com.mangalens.core.router.UrlEngineRouter.isSafeWebUrl(source)) return
                    onIngestionCancelled()
                    onUrlChanged(source)
                    onModeSelected(ContentType.GENERIC_WEB)
                    navController.navigate("web")
                }
                androidx.activity.compose.BackHandler(enabled = state.loading) {
                    onIngestionCancelled()
                    navController.popBackStack()
                }
                state.videoUrl?.let {
                    NativeVideoPlayer(
                        it,
                        translationEnabled = state.videoTranslationEnabled,
                        modifier = Modifier.fillMaxSize(),
                        onBack = { onIngestionCancelled(); navController.popBackStack() },
                        onOpenWeb = { source -> openVideoSource(source) },
                        sourcePageUrl = state.videoPageUrl,
                        requestHeaders = state.videoHeaders,
                        audioUrl = state.videoAudioUrl,
                        audioHeaders = state.videoAudioHeaders,
                        resolutionId = state.videoResolutionId,
                        providerCaptions = state.videoProviderCaptions,
                        onSourceRefreshed = onVideoRefreshed,
                        onReadySource = onVideoReady
                    )
                } ?: Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    if (state.loading) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Resolving playable video…")
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onIngestionCancelled) { Text("Cancel detection") }
                        TextButton(onClick = { openVideoSource(state.videoPageUrl ?: state.url) }) { Text("Open source page") }
                        TextButton(onClick = { onIngestionCancelled(); navController.popBackStack() }) { Text("Back") }
                    } else {
                        Text(state.error ?: "No playable stream has been detected yet.")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { openVideoSource(state.videoPageUrl ?: state.url) }) { Text("Open source page") }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            onUrlChanged(state.videoPageUrl ?: state.url)
                            onModeSelected(ContentType.VIDEO_STREAM)
                            onIngest()
                        }) { Text("Retry detection") }
                        TextButton(onClick = { onIngestionCancelled(); navController.popBackStack() }) { Text("Back") }
                    }
                }
            }
            composable("watch") {
                LocalVideoGalleryScreen(
                    onOpenPlayer = { uri -> navController.navigate("local_player?uri=" + android.net.Uri.encode(uri.toString())) },
                    onOpenSystem = { videoPicker.launch(arrayOf("video/*")) },
                    onOpenExternal = { uri ->
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/*")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { navController.context.startActivity(android.content.Intent.createChooser(intent, "Open video with…")) }
                    },
                    modifier = Modifier.fillMaxSize().statusBarsPadding()
                )
            }
            composable("local_video") {
                LocalVideoGalleryScreen(
                    onOpenPlayer = { uri -> navController.navigate("local_player?uri=" + android.net.Uri.encode(uri.toString())) },
                    onOpenSystem = { videoPicker.launch(arrayOf("video/*")) },
                    onOpenExternal = { uri ->
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/*")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { navController.context.startActivity(android.content.Intent.createChooser(intent, "Open video with…")) }
                    },
                    modifier = Modifier.fillMaxSize().statusBarsPadding()
                )
            }
            composable(
                "local_player?uri={uri}",
                arguments = listOf(androidx.navigation.navArgument("uri") { type = androidx.navigation.NavType.StringType; defaultValue = "" })
            ) { entry ->
                val raw = entry.arguments?.getString("uri").orEmpty()
                LocalVideoPlayerScreen(initialUri = raw.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse), translationEnabled = state.videoTranslationEnabled, modifier = Modifier.fillMaxSize())
            }
            composable("web") {
                AdBlockedWebScreen(
                    state.url,
                    onPageChanged = onUrlChanged,
                    onClose = { navController.popBackStack() },
                    translationEnabled = state.webTranslationEnabled,
                    adBlockEnabled = state.adBlockEnabled,
                    modifier = Modifier.fillMaxSize(),
                    targetLanguage = state.targetLanguage,
                    onOpenManga = { value ->
                        if (value.isNotBlank()) onUrlChanged(value)
                        onModeSelected(ContentType.IMAGE_CHAPTER)
                        onIngest()
                        navController.navigate("reader")
                    },
                    onOpenVideo = { media, sourcePage ->
                        if (onResolvedCaptionedVideo != null) onResolvedCaptionedVideo(media, sourcePage)
                        else onResolvedVideo(media.url, media.headers, sourcePage, media.audioUrl, media.audioHeaders)
                        navController.navigate("video")
                    }
                )
            }
        }
    }
}

