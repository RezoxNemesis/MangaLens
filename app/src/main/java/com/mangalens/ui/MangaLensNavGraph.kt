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
import kotlinx.coroutines.ensureActive
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
    onResolvedCaptionedVideo: ((com.mangalens.ui.video.SniffedMedia, String) -> Unit)? = null,
    onOpenSavedTextResult: ((com.mangalens.core.translation.PreparedSavedTextOpen) -> Boolean)? = null,
    onSavedTextReaderLeft: (String) -> Unit = {},
    onOpenSavedChapterEntry: (String) -> Boolean = { false }
) {
    val latestSemanticLibrary by rememberUpdatedState(state.library)
    val context = LocalContext.current
    val recentScope = rememberCoroutineScope()
    var recentVideoOpening by remember { mutableStateOf(false) }
    var recentVideoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var recentVideoError by remember { mutableStateOf<String?>(null) }
    var recentVideoRequest by remember { mutableLongStateOf(0L) }
    var recentVideoOwner by remember { mutableStateOf<androidx.navigation.NavBackStackEntry?>(null) }
    fun openRecentVideo(key: String, isCurrent: () -> Boolean = { true }) {
        val requestedEntry = navController.currentBackStackEntry ?: return
        if (recentVideoOpening || requestedEntry.destination.route?.substringBefore('?') !in setOf("home", "global_search", "recent_video") ||
            requestedEntry.lifecycle.currentState != androidx.lifecycle.Lifecycle.State.RESUMED || !isCurrent()) return
        recentVideoOpening = true
        recentVideoError = null
        recentVideoOwner = requestedEntry
        val request = ++recentVideoRequest
        fun currentOpening() = recentVideoRequest == request && isCurrent() && navController.currentBackStackEntry === requestedEntry &&
            requestedEntry.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
        recentVideoJob = recentScope.launch {
            try {
                val opening = RecentVideoOpening.prepare(context.applicationContext, key)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!currentOpening()) return@launch
                when (opening) {
                    is RecentVideoOpen.Local -> navController.navigate("local_player?uri=" + android.net.Uri.encode(opening.uri.toString()))
                    is RecentVideoOpen.Online -> {
                        onUrlChanged(opening.sourcePage)
                        onModeSelected(ContentType.VIDEO_STREAM)
                        onIngest()
                        navController.navigate("video")
                    }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                if (currentOpening()) recentVideoError = "The saved video did not respond in time. Try later or select it again in Watch."
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (currentOpening()) recentVideoError = failure.message?.take(300) ?: "This saved video cannot be opened. Select it again in Watch."
            } finally { if (recentVideoRequest == request) recentVideoOpening = false }
        }
    }
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
    LaunchedEffect(backStack, currentRoute) {
        if (currentRoute !in setOf("home", "global_search", "recent_video") || recentVideoOwner != null && backStack !== recentVideoOwner)
            recentVideoJob?.cancel()
    }
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
            composable("home") { homeEntry ->
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
                    onOpenWatch = { navController.navigate("watch") },
                    onOpenGlobalSearch = { query -> navController.navigate("global_search?query=" + android.net.Uri.encode(query.take(160))) },
                    onOpenRecentVideo = { key -> openRecentVideo(key) },
                    onOpenBrowserShortcut = { url ->
                        if (navController.currentBackStackEntry === homeEntry &&
                            homeEntry.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED &&
                            com.mangalens.core.router.UrlEngineRouter.isSafeWebUrl(url)) {
                            onUrlChanged(url); onModeSelected(ContentType.GENERIC_WEB); navController.navigate("web")
                        }
                    },
                    onOpenOrezRequest = { request ->
                        if (navController.currentBackStackEntry === homeEntry && homeEntry.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED)
                            navController.navigate("orez?request=" + android.net.Uri.encode(request.take(1024)))
                    },
                    recentVideoError = recentVideoError.takeIf { recentVideoOwner === navController.currentBackStackEntry }
                )
            }
            composable("global_search?query={query}", arguments = listOf(androidx.navigation.navArgument("query") { defaultValue = "" })) { entry ->
                com.mangalens.ui.search.LocalGlobalSearchScreen(state.library, initialQuery = entry.arguments?.getString("query").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenChapter = { id -> onOpenSavedChapter(id); navController.navigate("reader") },
                    onOpenDownload = { id -> navController.navigate("downloads?focus=" + android.net.Uri.encode(id)) },
                    onOpenBrowser = { url -> onUrlChanged(url); onModeSelected(ContentType.GENERIC_WEB); navController.navigate("web") },
                    onSearchSavedText = { navController.navigate("saved_text_all") },
                    onOpenGlossary = { navController.navigate("series_memory?chapter=") },
                    onOpenRecentVideo = { key, current -> openRecentVideo(key) { navController.currentBackStackEntry === entry && current() } },
                    onCancelRecentVideo = { if (recentVideoOwner === entry) recentVideoJob?.cancel() },
                    recentVideoOpening = recentVideoOwner === entry && recentVideoOpening,
                    recentVideoError = recentVideoError.takeIf { recentVideoOwner === entry },
                    onOpenGlossaryResult = { seriesId, query ->
                        if (navController.currentBackStackEntry === entry && entry.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED)
                            navController.navigate("series_memory?chapter=&series=" + android.net.Uri.encode(seriesId) + "&query=" + android.net.Uri.encode(query))
                    }, onOpenSemanticSearch = { query ->
                        if (navController.currentBackStackEntry === entry && entry.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED)
                            navController.navigate("semantic_library?query=" + android.net.Uri.encode(query.take(160)))
                    })
            }
            composable("reader_link?chapter={chapter}", arguments = listOf(
                androidx.navigation.navArgument("chapter") { defaultValue = "" })) { entry ->
                com.mangalens.ui.library.SavedChapterLinkScreen(entry.arguments?.getString("chapter").orEmpty(),
                    state.library, entry.lifecycle,
                    isCurrentOwner = { navController.currentBackStackEntry === entry },
                    onOpen = { id ->
                        if (navController.currentBackStackEntry === entry && entry.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED && onOpenSavedChapterEntry(id)) {
                            navController.navigate("reader") { popUpTo(entry.destination.id) { inclusive = true } }
                            true
                        } else false
                    }, onLibrary = { navController.navigate("library") { popUpTo(entry.destination.id) { inclusive = true } } })
            }
            composable("recent_video?key={key}", arguments = listOf(androidx.navigation.navArgument("key") { defaultValue = "" })) { entry ->
                RecentVideoWidgetEntryScreen(entry.arguments?.getString("key").orEmpty(), entry.lifecycle,
                    isCurrent = { navController.currentBackStackEntry === entry },
                    opening = recentVideoOwner === entry && recentVideoOpening,
                    error = recentVideoError.takeIf { recentVideoOwner === entry },
                    onOpen = { key, current -> openRecentVideo(key) { navController.currentBackStackEntry === entry && current() } },
                    onCancel = { if (recentVideoOwner === entry) recentVideoJob?.cancel() },
                    onWatch = { navController.navigate("local_video") })
            }
            composable("library") { libraryEntry ->
                LaunchedEffect(libraryEntry) { com.mangalens.orez.agent.NativeLibraryMetadataHints.requestRefresh() }
                LibraryScreen(state = state, onOpenSemanticSearch = { query ->
                    if (navController.currentBackStackEntry === libraryEntry && libraryEntry.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED)
                        navController.navigate("semantic_library?query=" + android.net.Uri.encode(query.take(160)))
                }, onSearchAllSavedText = onOpenSavedTextResult?.let { { navController.navigate("saved_text_all") } }, onChapterDetails = onChapterDetails, onChapterMetadata = onChapterMetadata, onSearchSavedText = onOpenSavedTextResult?.let { { id -> navController.navigate("saved_text/$id") } }, onOpenSeriesMemory = { chapter -> navController.navigate("series_memory?chapter=" + chapter.orEmpty()) }, onDeleteChapter = onDeleteSavedChapter, onOpenSavedChapter = { id -> onOpenSavedChapter(id); navController.navigate("reader") }, onOpenReader = { navController.navigate("reader") }, onOpenLocalVideo = { navController.navigate("local_video") })
            }
            composable("semantic_library?query={query}", arguments = listOf(androidx.navigation.navArgument("query") { defaultValue = "" })) { entry ->
                com.mangalens.ui.library.SemanticLibrarySearchScreen(state.library,
                    initialQuery = entry.arguments?.getString("query").orEmpty(), onBack = { navController.popBackStack() },
                    onOpenChapter = { hit ->
                        if (navController.currentBackStackEntry === entry && entry.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED &&
                            com.mangalens.core.search.embedding.SemanticLibraryMetadata.current(latestSemanticLibrary, hit) && onOpenSavedChapterEntry(hit.id))
                            navController.navigate("reader")
                    }, onOpenSavedText = { prepared ->
                        if (navController.currentBackStackEntry !== entry || entry.lifecycle.currentState != androidx.lifecycle.Lifecycle.State.RESUMED ||
                            onOpenSavedTextResult?.invoke(prepared) != true) false else { navController.navigate("reader"); true }
                    }, onLexicalSearch = { query ->
                        if (navController.currentBackStackEntry === entry && entry.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED)
                            navController.navigate("global_search?query=" + android.net.Uri.encode(query.take(160)))
                    })
            }
            composable("orez?research={research}&request={request}", arguments = listOf(
                androidx.navigation.navArgument("research") { defaultValue = "" },
                androidx.navigation.navArgument("request") { defaultValue = "" })) { entry ->
                OrezAiScreen(initialUserRequest = com.mangalens.orez.OrezEntryDraftPolicy.initial(
                    entry.arguments?.getString("research").orEmpty(), entry.arguments?.getString("request").orEmpty()), hasActiveChapter = state.pages.isNotEmpty(),
                    activeChapterId = state.activeChapter?.id?.takeIf { id -> state.library.any { saved ->
                        saved.id == id && saved.pages.map { it.index to it.localPath } == state.pages.map { it.index to it.localPath }
                    } },
                    activeUrl = state.url.takeIf { it.isNotBlank() }, onTargetLanguage = onTargetLanguageChanged,
                    library = state.library, chapterText = state.overlays.values.flatten().joinToString("\n") { it.translatedText },
                    selectedMedia = state.capturedVideoSelection()?.toOrezSelection(),
                    onOpenSubtitleResult = { result ->
                        navController.navigate("orez_subtitle/${android.net.Uri.encode(result.planId)}/${result.stepIndex}/${result.taskId}/${result.generation}")
                    },
                    onOpenSpeechModels = { navController.navigate("video_speech_models") { launchSingleTop = true } },
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
            composable("video_speech_models") {
                com.mangalens.ui.ai.voice.OrezSpeechModelsScreen(onBack = { navController.popBackStack() })
            }
            composable("downloads?focus={focus}", arguments = listOf(androidx.navigation.navArgument("focus") { defaultValue = "" })) { entry -> DownloadsScreen(focusedDownloadId = entry.arguments?.getString("focus"), onBack = { navController.popBackStack() }, appState = state, onImport = { onImportImages(it); navController.navigate("reader") }, onOpenLibrary = { navController.navigate("library") }, onOpenTools = { navController.navigate("settings") }, onPlayVideo = { value -> onUrlChanged(value); onModeSelected(ContentType.VIDEO_STREAM); onIngest(); navController.navigate("video") }, onPlayDownloadedVideo = { uri -> navController.navigate("local_player?uri=" + android.net.Uri.encode(uri.toString())) }, onPauseTranslation = onTranslationPaused, onCancelTranslation = onTranslationCancelled) }
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
            composable("series_memory?chapter={chapter}&series={series}&query={query}", arguments = listOf(
                androidx.navigation.navArgument("chapter") { defaultValue = "" },
                androidx.navigation.navArgument("series") { defaultValue = "" },
                androidx.navigation.navArgument("query") { defaultValue = "" })) { entry ->
                val id = entry.arguments?.getString("chapter").orEmpty()
                com.mangalens.ui.library.SeriesMemoryScreen(state.library.firstOrNull { it.id == id }, state.targetLanguage,
                    onBack = { navController.popBackStack() }, initialSeriesId = entry.arguments?.getString("series").orEmpty(),
                    initialQuery = entry.arguments?.getString("query").orEmpty())
            }
            composable("saved_text_all") {
                com.mangalens.ui.library.LibraryCrossChapterSavedTextScreen(state.library,
                    onBack = { navController.popBackStack() }, onOpen = { prepared ->
                        if (onOpenSavedTextResult?.invoke(prepared) != true) false else {
                            navController.navigate("reader")
                            true
                        }
                    })
            }
            composable("saved_text/{chapterId}") { entry ->
                val id = entry.arguments?.getString("chapterId").orEmpty()
                com.mangalens.ui.library.LibrarySavedTextScreen(state.library.singleOrNull { it.id == id && id.matches(Regex("[a-f0-9]{32}")) },
                    onBack = { navController.popBackStack() }, onOpen = { prepared ->
                        if (onOpenSavedTextResult?.invoke(prepared) != true) false else {
                            navController.navigate("reader")
                            true
                        }
                    })
            }
            composable("reader") {
                val memoryState = readerMemory?.state?.collectAsState()?.value
                val memoryScope = rememberCoroutineScope()
                val savedTextRequest = state.savedTextSelection?.requestId
                DisposableEffect(readerMemory, savedTextRequest) { readerMemory?.enterReader(); onDispose {
                    readerMemory?.leaveReader(); savedTextRequest?.let(onSavedTextReaderLeft)
                } }
                val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                DisposableEffect(lifecycle, readerMemory) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) readerMemory?.refreshVisiblePage() }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                readerMemory?.let { controller ->
                    com.mangalens.ui.reader.ReaderBubbleToolsPanel(controller.bubbleTools,
                        onRetranslate = { index -> state.pages.firstOrNull { it.index == index }?.let(onTranslatePage) })
                    com.mangalens.ui.reader.ReaderMemoryPanel(controller,
                        onRetranslate = { index -> state.pages.firstOrNull { it.index == index }?.let(onTranslatePage) })
                }
                val capturedBubblePresentation = memoryState?.bubblePresentation
                val onSavedBubble: ((Int, Int, com.mangalens.core.translation.SavedMangaLettering) -> Unit)? =
                    remember(readerMemory, capturedBubblePresentation) {
                        if (readerMemory == null || capturedBubblePresentation == null) null else { page, index, native ->
                            readerMemory.dismiss()
                            readerMemory.bubbleTools.open(capturedBubblePresentation, page, index, native)
                        }
                    }
                val readerPreferences = com.mangalens.core.translation.SavedTextReaderNavigationPolicy.preferences(state.savedTextSelection,
                    com.mangalens.core.translation.SavedTextReaderPreferences(state.targetLanguage, state.translationStyle, state.customTranslationStyle))
                key(state.activeChapter?.id, savedTextRequest) {
                val sfxGateway = remember(readerMemory, capturedBubblePresentation) {
                    if (readerMemory == null || capturedBubblePresentation == null) null else readerMemory.sfxRestorationGateway(capturedBubblePresentation)
                }
                androidx.compose.runtime.CompositionLocalProvider(com.mangalens.ui.reader.LocalReaderSfxRestoration provides sfxGateway) {
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
                    targetLanguage = readerPreferences.target, onTargetLanguageChanged = onTargetLanguageChanged,
                    translationStyle = readerPreferences.style, onTranslationStyleChanged = onTranslationStyleChanged,
                    onBack = { navController.popBackStack() }, onTranslate = onTranslateChapter,
                    onDownload = onDownloadChapter, onMenu = { navController.navigate("settings") },
                    onRetry = { if (state.translationError || state.translationMessage != null) onTranslateChapter() else onIngest() },
                    onRetryPage = onRetryReaderPage,
                    onVisiblePage = { index -> readerMemory?.onVisiblePage(index) },
                    onVisiblePages = { indices -> readerMemory?.onVisiblePages(indices) },
                    readerPresentationEpoch = capturedBubblePresentation?.epoch, ocrDiagnostics = state.ocrDiagnostics,
                    onCorrectPage = readerMemory?.let { controller -> { index -> memoryScope.launch { controller.openPage(index) }; Unit } },
                    onManuallyHiddenPages = { indices -> readerMemory?.onManuallyHiddenPages(indices) },
                    personalOverlays = memoryState?.personalOverlays.orEmpty(),
                    onSavedBubble = onSavedBubble,
                    onOpenWeb = { navController.navigate("web") }, onLongPressPage = onTranslatePage,
                    modifier = Modifier.fillMaxSize()
                )
                }
                }
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
                        videoMimeType = state.videoMimeType, audioMimeType = state.videoAudioMimeType,
                        videoFragments = state.videoFragments, audioFragments = state.videoAudioFragments,
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
                    onResearchQuestion = { question ->
                        if (com.mangalens.ui.web.BrowserResearchInput.command(question) != null)
                            navController.navigate("orez?research=" + android.net.Uri.encode(question))
                    },
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

