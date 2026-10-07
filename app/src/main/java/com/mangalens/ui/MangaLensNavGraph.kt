package com.mangalens.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    onResolvedVideo: (String, Map<String, String>, String, String?, Map<String, String>) -> Unit
) {
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
                restoreState = true
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
                    onIngest = {
                        onIngest()
                        when (state.mode) {
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
                    onOpenWeb = { navController.navigate("web") }
                )
            }
            composable("library") {
                LibraryScreen(state = state, onChapterDetails = onChapterDetails, onDeleteChapter = onDeleteSavedChapter, onOpenSavedChapter = { id -> onOpenSavedChapter(id); navController.navigate("reader") }, onOpenReader = { navController.navigate("reader") }, onOpenLocalVideo = { navController.navigate("local_video") })
            }
            composable("orez") {
                OrezAiScreen(hasActiveChapter = state.pages.isNotEmpty(), activeUrl = state.url.takeIf { it.isNotBlank() }, onTargetLanguage = onTargetLanguageChanged, library = state.library, chapterText = state.overlays.values.flatten().joinToString("\n") { it.translatedText }, onImport = { onImportImages(it); navController.navigate("reader") }) { value, route ->
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
            composable("reader") {
                MangaContinuousReader(title = state.activeChapter?.title ?: "Chapter", chapterId = state.activeChapter?.id ?: "", initialPosition = state.activeChapter?.position ?: 0, initialOffset = state.activeChapter?.scrollOffset ?: 0, onPositionChanged = { id, position, offset -> onReadingPositionChanged(id, position, offset) }, loading = state.loading, pages = state.pages, translated = state.translationEnabled, translating = state.translating, error = state.error, overlays = state.overlays, promoPages = state.promoPages, targetLanguage = state.targetLanguage, onTargetLanguageChanged = onTargetLanguageChanged, translationStyle = state.translationStyle, onTranslationStyleChanged = onTranslationStyleChanged, onBack = { navController.popBackStack() }, onTranslate = onTranslateChapter, onDownload = onDownloadChapter, onMenu = { navController.navigate("settings") }, onRetry = { if (state.translationError) onTranslateChapter() else onIngest() }, onOpenWeb = { navController.navigate("web") }, onLongPressPage = onTranslatePage, modifier = Modifier.fillMaxSize())
            }
            composable("video") {
                state.videoUrl?.let {
                    NativeVideoPlayer(
                        it,
                        translationEnabled = state.videoTranslationEnabled,
                        modifier = Modifier.fillMaxSize(),
                        onBack = { navController.popBackStack() },
                        onOpenWeb = { navController.navigate("web") },
                        sourcePageUrl = state.videoPageUrl,
                        requestHeaders = state.videoHeaders,
                        audioUrl = state.videoAudioUrl,
                        audioHeaders = state.videoAudioHeaders
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
                    } else {
                        Text(state.error ?: "No playable stream has been detected yet.")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { navController.navigate("web") }) { Text("Open source page") }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onIngest) { Text("Retry detection") }
                    }
                }
            }
            composable("watch") {
                LocalVideoGalleryScreen(
                    onOpenPlayer = { uri -> navController.navigate("local_player?uri=" + android.net.Uri.encode(uri.toString())) },
                    onOpenSystem = { navController.navigate("local_player") },
                    onOpenExternal = { uri -> navController.navigate("local_player?uri=" + android.net.Uri.encode(uri.toString())) },
                    modifier = Modifier.fillMaxSize().statusBarsPadding()
                )
            }
            composable("local_video") {
                LocalVideoGalleryScreen(
                    onOpenPlayer = { uri -> navController.navigate("local_player?uri=" + android.net.Uri.encode(uri.toString())) },
                    onOpenSystem = { navController.navigate("local_player") },
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
                        onResolvedVideo(
                            media.url,
                            media.headers,
                            sourcePage,
                            media.audioUrl,
                            media.audioHeaders
                        )
                        navController.navigate("video")
                    }
                )
            }
        }
    }
}
