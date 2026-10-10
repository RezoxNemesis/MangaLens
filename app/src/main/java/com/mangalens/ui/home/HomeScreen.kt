package com.mangalens.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.mangalens.R
import com.mangalens.core.model.ContentType
import com.mangalens.ui.MangaLensUiState
import com.mangalens.ui.components.*

@Composable
fun HomeScreen(state: MangaLensUiState, onUrlChanged: (String) -> Unit, onPaste: () -> Unit,
    onModeSelected: (ContentType) -> Unit, onIngest: (ContentType) -> Unit, onOpenReader: () -> Unit,
    onOpenVideo: () -> Unit, onOpenDownloads: () -> Unit, onOpenChapter: (String) -> Unit,
    onImportImages: (List<Uri>) -> Unit, onOpenSavedChapter: (String) -> Unit,
    onOpenOrez: () -> Unit, onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit = {}, onOpenWeb: () -> Unit = {}, onOpenWatch: () -> Unit = onOpenVideo,
    onOpenGlobalSearch: (String) -> Unit = {},
    onOpenRecentVideo: (String) -> Unit = { onOpenVideo() }, recentVideoError: String? = null,
    onOpenBrowserShortcut: (String) -> Unit = {}, onOpenOrezRequest: (String) -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val recentStore = remember(context.applicationContext) { com.mangalens.ui.video.RecentVideoStore.shared(context) }
    val recentHistory by recentStore.state.collectAsState()
    val home = rememberHomeLayout()
    val browserNeeded = home.layout.visibleModules.any { it == HomeModule.BROWSER_HISTORY || it == HomeModule.RECENT_SITES }
    val browserSession = remember(context.applicationContext, browserNeeded) {
        if (browserNeeded) com.mangalens.ui.web.BrowserWorkspaceRepository.session(context) else null
    }
    val absentBrowser = remember { kotlinx.coroutines.flow.MutableStateFlow<com.mangalens.ui.web.BrowserWorkspaceSnapshot?>(null) }
    val absentBrowserError = remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    val browserSnapshot by (browserSession?.state ?: absentBrowser).collectAsState()
    val browserError by (browserSession?.error ?: absentBrowserError).collectAsState()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val homeLifecycle by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    var browserShortcutError by remember { mutableStateOf<String?>(null) }
    var customize by rememberSaveable { mutableStateOf(false) }
    if (customize) HomeCustomizationSheet(home.layout, home.preferences) { customize = false }
    var query by rememberSaveable { mutableStateOf("") }
    var queryEpoch by remember { mutableLongStateOf(0L) }
    var tab by rememberSaveable { mutableStateOf("For you") }
    var showLink by rememberSaveable { mutableStateOf(false) }
    var requestedMode by rememberSaveable(showLink) { mutableStateOf<ContentType?>(null) }
    val linkMode = requestedMode ?: state.mode
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) onImportImages(it) }
    fun openBrowserVisit(visit: com.mangalens.ui.web.BrowserVisit, capturedQuery: String,
        capturedQueryEpoch: Long, sitesOnly: Boolean) {
        if (lifecycleOwner.lifecycle.currentState != androidx.lifecycle.Lifecycle.State.RESUMED) return
        if (capturedQuery != query || capturedQueryEpoch != queryEpoch ||
            !HomeBrowserShortcutPolicy.rows(browserSession?.state?.value, query, sitesOnly).contains(visit)) {
            browserShortcutError = "This saved visit changed. Refresh and try again."
            return
        }
        browserShortcutError = null
        onOpenBrowserShortcut(visit.url)
    }
    LaunchedEffect(query, browserSnapshot?.revision) { browserShortcutError = null }
    val chapters = state.library.filter { it.title.contains(query, true) && (tab != "Bookmarks" || it.bookmarked) }
    if (showLink) AlertDialog(onDismissRequest = { showLink = false }, title = { Text("Open a link") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(state.url, onUrlChanged, singleLine = true, placeholder = { Text("Chapter, video or web URL") }, trailingIcon = { TextButton(onPaste) { Text("Paste") } })
            listOf(ContentType.IMAGE_CHAPTER to "Manga / Manhwa", ContentType.VIDEO_STREAM to "Video", ContentType.GENERIC_WEB to "Web page").forEach { (mode, label) ->
                Row { RadioButton(linkMode == mode, { requestedMode = mode; onModeSelected(mode) }); Text(label, Modifier.padding(top = 13.dp)) }
            }
        }
    }, confirmButton = { Button({ onIngest(linkMode); showLink = false }, enabled = state.url.isNotBlank() && !state.loading) { Text("Open content") } }, dismissButton = { TextButton({ showLink = false }) { Text("Cancel") } })
    LazyColumn(
        Modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = .055f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(com.mangalens.ui.theme.LocalAppearance.current.density.gap)
    ) {
        item { BrandHeader("MangaLens", "READ · WATCH · BROWSE", action = {
            IconButton({ showLink = true }) { Icon(Icons.Outlined.Link, "Open link") }
            IconButton({ customize = true }) { Icon(Icons.Outlined.Tune, "Customize Home") }
            IconButton(onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings and protection") }
        }) }
        item { OutlinedTextField(query, { if (it != query) queryEpoch++; query = it }, Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { TextButton({ onOpenGlobalSearch(query) }) { Text("Search all") } }, placeholder = { Text("Search your manga & chapters") }, shape = RoundedCornerShape(18.dp)) }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("For you", "Recent", "Bookmarks")) { label -> FilterChip(tab == label, { tab = label }, label = { Text(label) }) }
        } }
        if (home.layout.visibleModules.isEmpty()) item {
            Panel {
                Text("Your Home sections are hidden", style = MaterialTheme.typography.titleMedium)
                TextButton({ customize = true }) { Text("Choose Home sections") }
            }
        }
        home.layout.visibleModules.forEach { module ->
            when (module) {
                HomeModule.QUICK_ACTIONS -> item(key = module.id) {
                    HomeSection(module) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { NeonActionTile("Open link", null, Icons.Outlined.Link, Modifier.width(170.dp)) { showLink = true } }
                            item { NeonActionTile("Import", null, Icons.Outlined.AddPhotoAlternate, Modifier.width(168.dp)) { picker.launch(com.mangalens.core.reader.DocumentImporter.MIME_TYPES) } }
                            item { NeonActionTile("Watch", null, Icons.Outlined.PlayCircle, Modifier.width(160.dp), onClick = onOpenWatch) }
                            item { NeonActionTile("Web", null, Icons.Outlined.Language, Modifier.width(152.dp), onClick = onOpenWeb) }
                            item { NeonActionTile("Downloads", null, Icons.Outlined.FileDownload, Modifier.width(196.dp), onClick = onOpenDownloads) }
                        }
                    }
                }
                HomeModule.CONTINUE_READING -> state.library.firstOrNull()?.takeIf { query.isBlank() && tab == "For you" }?.let { chapter ->
                    item(key = module.id) { HomeSection(module) {
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("Continue reading", style = MaterialTheme.typography.titleMedium)
                            ContinueCard(chapter) { onOpenSavedChapter(chapter.id) }
                        }
                    } }
                }
                HomeModule.RECENT_MANGA -> item(key = module.id) {
                    HomeSection(module) {
                        Column(verticalArrangement = Arrangement.spacedBy(com.mangalens.ui.theme.LocalAppearance.current.density.gap)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (tab == "Bookmarks") "Your bookmarks" else "Recently saved", style = MaterialTheme.typography.titleMedium)
                                TextButton(onOpenLibrary, contentPadding = PaddingValues(0.dp)) { Text("View all →") }
                            }
                            if (chapters.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(chapters.take(20), key = { it.id }) { chapter ->
                                        ChapterCover(chapter, { onOpenSavedChapter(chapter.id) }, Modifier.width(112.dp))
                                    }
                                }
                            } else {
                                Panel {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Outlined.CollectionsBookmark, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(34.dp))
                                        Column {
                                            Text(if (query.isNotBlank()) "No matching chapters" else if (tab == "Bookmarks") "Bookmark a story you love" else "Bring your first story", style = MaterialTheme.typography.titleMedium)
                                            Text("Your library becomes richer as you read.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Text("Images, ZIP, CBZ and PDF chapters are supported. Your saved pages appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Button({ picker.launch(com.mangalens.core.reader.DocumentImporter.MIME_TYPES) }, shape = RoundedCornerShape(14.dp)) { Text("Import chapter →") }
                                }
                            }
                        }
                    }
                }
                HomeModule.SOURCE_CHAPTERS -> if (state.chapters.isNotEmpty()) item(key = module.id) {
                    HomeSection(module) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Chapters from your source", style = MaterialTheme.typography.titleMedium)
                        state.chapters.take(20).forEach { chapter -> TextButton({ onOpenChapter(chapter.url) }) { Text(chapter.title.ifBlank { "Chapter" }) } }
                    } }
                }
                HomeModule.OREZ_AI -> item(key = module.id) {
                    HomeSection(module) {
                        NeonActionTile("Orez AI", "Translate, understand and organise your stories", Icons.Outlined.AutoAwesome,
                            Modifier.fillMaxWidth(), onClick = onOpenOrez)
                    }
                }
                HomeModule.CONTINUE_WATCHING -> {
                    val continued = recentHistory.entries.firstOrNull { it.canContinue && query.isBlank() && tab == "For you" }
                    if (continued != null) item(key = module.id) { HomeSection(module) {
                        com.mangalens.ui.video.RecentVideoCard(continued, recentStore, onOpenRecentVideo)
                    } } else if (state.videoUrl != null) item(key = module.id) { HomeSection(module) {
                        NeonActionTile("Resume video", "Continue in MangaLens Video", Icons.Outlined.PlayCircle,
                            Modifier.fillMaxWidth(), onClick = onOpenVideo)
                    } }
                }
                HomeModule.RECENT_VIDEO -> item(key = module.id) { HomeSection(module) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recent videos", style = MaterialTheme.typography.titleMedium)
                        val recent = recentHistory.entries.filter { it.title.contains(query, true) && (tab != "Bookmarks" || it.favorite) }.take(12)
                        if (recent.isEmpty()) Text(if (recentHistory.loading) "Loading recent videos…" else "Videos appear here after playback starts.")
                        recent.forEach { entry -> com.mangalens.ui.video.RecentVideoCard(entry, recentStore, onOpenRecentVideo) }
                        (recentVideoError ?: recentHistory.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                } }
                HomeModule.BROWSER_HISTORY, HomeModule.RECENT_SITES -> item(key = module.id) {
                    HomeSection(module) {
                        val renderedQuery = query
                        val renderedEpoch = queryEpoch
                        val sitesOnly = module == HomeModule.RECENT_SITES
                        HomeBrowserSection(module.label, HomeBrowserShortcutPolicy.rows(browserSnapshot, renderedQuery,
                            sitesOnly = sitesOnly), browserSnapshot == null && browserError == null,
                            browserShortcutError ?: browserError,
                            homeLifecycle == androidx.lifecycle.Lifecycle.State.RESUMED,
                            { visit -> openBrowserVisit(visit, renderedQuery, renderedEpoch, sitesOnly) }, onOpenWeb)
                    }
                }
                HomeModule.OREZ_SUGGESTIONS -> item(key = module.id) {
                    HomeSection(module) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Try with Orez", style = MaterialTheme.typography.titleMedium)
                        Text("Choose a prompt, review it, then send it in Orez.", style = MaterialTheme.typography.bodySmall)
                        if (state.pages.isNotEmpty()) TextButton({ onOpenOrezRequest("Summarize this chapter faithfully without spoilers.") }) {
                            Text("Summarize the current chapter")
                        }
                        if (state.library.isNotEmpty()) TextButton({ onOpenOrezRequest("Help me plan my reading list from my saved chapters.") }) {
                            Text("Plan from my saved library")
                        }
                        TextButton({ onOpenOrezRequest("Explain MangaLens Reader, Web, Video and download controls.") }) {
                            Text("Understand MangaLens controls")
                        }
                    } }
                }
                HomeModule.DOWNLOADS -> item(key = module.id) {
                    HomeSection(module) {
                        NeonActionTile("Downloads", "Media, saved files and translation tasks", Icons.Outlined.FileDownload,
                            Modifier.fillMaxWidth(), onClick = onOpenDownloads)
                    }
                }
                HomeModule.TRANSLATION_QUEUE -> if (state.translating || state.translationPaused || state.translationTotal > 0) item(key = module.id) {
                    HomeSection(module) { Panel {
                        Text("Translation queue", style = MaterialTheme.typography.titleMedium)
                        Text((state.activeChapter?.title ?: "Current chapter") + " · ${state.translationDone}/${state.translationTotal} pages processed")
                        LinearProgressIndicator(progress = { state.translationDone.toFloat() / state.translationTotal.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                        Text(if (state.translationPaused) "Paused" else if (state.translating) "Processing pages" else "Latest chapter translation", style = MaterialTheme.typography.bodySmall)
                        TextButton(onOpenDownloads) { Text("Open translation queue →") }
                    } }
                }
                HomeModule.BOOKMARKS -> item(key = module.id) {
                    val bookmarks = state.library.filter { it.bookmarked && it.title.contains(query, true) }.take(20)
                    HomeSection(module) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Your bookmarks", style = MaterialTheme.typography.titleMedium)
                        if (bookmarks.isEmpty()) {
                            Text(if (query.isBlank()) "No bookmarked chapters yet" else "No matching bookmarked chapters", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onOpenLibrary) { Text("Open library →") }
                        } else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(bookmarks, key = { it.id }) { chapter ->
                                ChapterCover(chapter, { onOpenSavedChapter(chapter.id) }, Modifier.width(112.dp))
                            }
                        }
                    } }
                }
            }
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Preparing chapter…") }
    }
}


@Composable
private fun HomeSection(module: HomeModule, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().semantics { contentDescription = "Home section: ${module.label}" }) { content() }
}
