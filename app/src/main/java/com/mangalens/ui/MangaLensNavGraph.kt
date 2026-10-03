package com.mangalens.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
    onResetAdBlockStats: () -> Unit,
    onOpenSavedChapter: (String) -> Unit,
    onReadingPositionChanged: (String, Int, Int) -> Unit,
    onImportImages: (List<android.net.Uri>) -> Unit
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
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
            enterTransition = { fadeIn(tween(300)) + slideInHorizontally(tween(300)) { it / 8 } },
            exitTransition = { fadeOut(tween(300)) + slideOutHorizontally(tween(300)) { -it / 8 } },
            popEnterTransition = { fadeIn(tween(300)) + slideInHorizontally(tween(300)) { -it / 8 } },
            popExitTransition = { fadeOut(tween(300)) + slideOutHorizontally(tween(300)) { it / 8 } }
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
                    onImportImages = { uris -> onImportImages(uris); navController.navigate("reader") }
                )
            }
            composable("library") {
                LibraryScreen(state = state, onOpenSavedChapter = { id -> onOpenSavedChapter(id); navController.navigate("reader") }, onOpenReader = { navController.navigate("reader") }, onOpenLocalVideo = { navController.navigate("local_video") })
            }
            composable("orez") {
                OrezAiScreen { value, route ->
                    onUrlChanged(value)
                    when (route) {
                        OrezRoute.MANGA_READER -> { onModeSelected(ContentType.IMAGE_CHAPTER); onIngest(); navController.navigate("reader") }
                        OrezRoute.VIDEO_PLAYER -> { onModeSelected(ContentType.VIDEO_STREAM); onIngest(); navController.navigate("video") }
                        OrezRoute.WEB_VIEW -> { onModeSelected(ContentType.GENERIC_WEB); navController.navigate("web") }
                        OrezRoute.TRANSLATE_MANGA -> { onModeSelected(ContentType.IMAGE_CHAPTER); onIngestAndTranslate(); navController.navigate("reader") }
                        OrezRoute.TRANSLATE_VIDEO -> { onModeSelected(ContentType.VIDEO_STREAM); onVideoTranslationChanged(true); onIngest(); navController.navigate("video") }
                        OrezRoute.TRANSLATE_WEB -> { onModeSelected(ContentType.GENERIC_WEB); onWebTranslationChanged(true); navController.navigate("web") }
                        OrezRoute.CHAT -> Unit
                    }
                }
            }
            composable("downloads") { DownloadsScreen(onBack = { navController.popBackStack() }) }
            composable("settings") {
                SettingsScreen(
                    state = state,
                    onThemeModeChanged = onThemeModeChanged,
                    onMangaTranslationChanged = onMangaTranslationChanged,
                    onVideoTranslationChanged = onVideoTranslationChanged,
                    onWebTranslationChanged = onWebTranslationChanged,
                    onTranslationStyleChanged = onTranslationStyleChanged,
                    onCustomTranslationStyleChanged = onCustomTranslationStyleChanged,
                    onResetAdBlockStats = onResetAdBlockStats
                )
            }
            composable("reader") {
                MangaContinuousReader(title = state.activeChapter?.title ?: "Chapter", chapterId = state.activeChapter?.id ?: "", initialPosition = state.activeChapter?.position ?: 0, initialOffset = state.activeChapter?.scrollOffset ?: 0, onPositionChanged = { id, position, offset -> onReadingPositionChanged(id, position, offset) }, loading = state.loading, pages = state.pages, translated = state.translationEnabled, translating = state.translating, error = state.error, overlays = state.overlays, targetLanguage = state.targetLanguage, onTargetLanguageChanged = onTargetLanguageChanged, onTranslate = onTranslateChapter, onDownload = onDownloadChapter, onMenu = { navController.navigate("settings") }, onLongPressPage = onTranslatePage, modifier = Modifier.fillMaxSize())
            }
            composable("video") {
                state.videoUrl?.let { NativeVideoPlayer(it, translationEnabled = state.videoTranslationEnabled, modifier = Modifier.fillMaxSize()) }
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
            composable("web") { AdBlockedWebScreen(state.url, translationEnabled = state.webTranslationEnabled, modifier = Modifier.fillMaxSize(), targetLanguage = state.targetLanguage, onOpenManga = { value -> onUrlChanged(value); onModeSelected(ContentType.IMAGE_CHAPTER); onIngest(); navController.navigate("reader") }) }
        }
    }
}