package com.mangalens.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.memory.*
import com.mangalens.ui.video.*
import com.mangalens.download.DownloadDatabase
import com.mangalens.orez.OrezMessageSearchMetadata
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.ui.web.BrowserWorkspaceRepository
import com.mangalens.core.router.UrlEngineRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalGlobalSearchScreen(library: List<SavedChapter>, initialQuery: String = "", onBack: () -> Unit,
    onOpenChapter: (String) -> Unit, onOpenDownload: (String) -> Unit, onOpenBrowser: (String) -> Unit,
    onSearchSavedText: () -> Unit, onOpenGlossary: () -> Unit,
    onOpenRecentVideo: ((String, () -> Boolean) -> Unit)? = null, onCancelRecentVideo: () -> Unit = {},
    recentVideoOpening: Boolean = false, recentVideoError: String? = null,
    onOpenGlossaryResult: ((String, String) -> Unit)? = null,
    onOpenSemanticSearch: ((String) -> Unit)? = null) {
    val application = LocalContext.current.applicationContext
    val browser by remember(application) { BrowserWorkspaceRepository.session(application) }.state.collectAsState()
    val latestLibrary by rememberUpdatedState(library)
    val latestBrowser by rememberUpdatedState(browser)
    val recent by remember(application) { RecentVideoStore.shared(application) }.state.collectAsState()
    val latestRecent by rememberUpdatedState(recent)
    val memory = remember(application) { SeriesMemoryStore(application.filesDir) }
    val currentCancelRecent by rememberUpdatedState(onCancelRecentVideo)
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery.take(LocalGlobalSearchPolicy.QUERY_CHARS)) }
    var includeConversations by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<LocalSearchResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf<Job?>(null) }
    var request by remember { mutableLongStateOf(0L) }
    var detail by remember { mutableStateOf<OrezMessageSearchMetadata?>(null) }

    fun retire() {
        request++; active?.cancel(); currentCancelRecent(); active = null; result = null; detail = null; error = null
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) retire() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); request++; active?.cancel(); currentCancelRecent() }
    }
    LaunchedEffect(library, browser?.revision, recent) { retire() }

    fun search() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        retire()
        val accepted = try { LocalGlobalSearchPolicy.query(query) }
        catch (_: IllegalArgumentException) { error = "Enter a search of 1–160 characters."; return }
        val generation = request
        val capturedLibrary = library
        val capturedBrowser = browser
        val conversations = includeConversations
        val capturedRecent = recent
        active = scope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    val downloads = DownloadDatabase.get(application).downloads()
                    val messages = OrezRoomDatabase.get(application).messages()
                    val candidates = LocalSqlMetadataSearch.search(accepted, conversations,
                        { cursor, limit -> if (cursor == null) downloads.firstSearchCandidates(limit) else downloads.nextSearchCandidates(cursor, limit) },
                        { cursor -> if (cursor == null) downloads.hasSearchCandidates() else downloads.hasSearchCandidatesBefore(cursor) },
                        { cursor, limit -> if (cursor == null) messages.firstSearchCandidates(limit) else messages.nextSearchCandidates(cursor, limit) },
                        { cursor -> if (cursor == null) messages.hasSearchCandidates() else messages.hasSearchCandidatesBefore(cursor) })
                    LocalGlobalSearch.search(accepted, capturedLibrary, capturedBrowser,
                        { pattern, limit -> downloads.searchLocalMetadata(pattern, limit) },
                        { pattern, needle, limit -> messages.searchLocalMetadata(pattern, needle, limit) }, conversations,
                        capturedRecent, memory.searchGlossaryMetadata(accepted), candidates)
                }
                currentCoroutineContext().ensureActive()
                if (request == generation && latestLibrary === capturedLibrary && latestBrowser?.revision == capturedBrowser?.revision &&
                    latestRecent === capturedRecent) {
                    val delivered = found.tryDeliver {
                        if (request != generation) false else { result = it; true }
                    }
                    if (!delivered && request == generation) error = "A series glossary changed during search. Search again."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (request == generation) error = "Local search could not be read. Try again." }
            finally { if (request == generation) active = null }
        }
    }

    fun open(hit: LocalSearchHit) {
        if (lifecycle.currentState != Lifecycle.State.RESUMED) return
        if (result?.hits?.none { it === hit } != false) return
        when {
            hit.recentVideoKey != null -> {
                val current = latestRecent.entries.singleOrNull { it.key == hit.recentVideoKey && it.source == hit.recentVideoSource }
                val found = result ?: return
                if (current == null || onOpenRecentVideo == null) error = "This video history entry changed. Search again."
                else {
                    val generation = request
                    onOpenRecentVideo(current.key) { request == generation && result === found &&
                        latestRecent.entries.any { it.key == current.key && it.source == current.source } }
                }
            }
            hit.glossary != null -> {
                if (active != null) return
                val found = result ?: return
                val generation = request
                val match = hit.glossary
                active = scope.launch {
                    try {
                        val prepared = memory.prepareProfileRead(match.read.profile.id)
                        currentCoroutineContext().ensureActive()
                        var acceptedId: String? = null
                        if (request == generation && result === found) prepared?.tryDeliver { profile ->
                            if (profile.glossary.singleOrNull { it.id == match.term.id } != match.term ||
                                !MemoryGlossaryMetadataQuery.matches(match.term, found.query)) false
                            else { acceptedId = profile.id; true }
                        }
                        if (request == generation) {
                            if (acceptedId == null || onOpenGlossaryResult == null) error = "This glossary term changed. Search again."
                            else onOpenGlossaryResult(acceptedId!!, found.query)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (request == generation) error = "This glossary could not be read. Try again." }
                    finally { if (request == generation) active = null }
                }
            }
            hit.chapterId != null -> {
                val current = latestLibrary.singleOrNull { it.id == hit.chapterId }
                if (current == null) error = "This chapter changed. Search again." else onOpenChapter(current.id)
            }
            hit.browserUrl != null -> {
                val current = latestBrowser
                val stillPresent = current?.history?.any { it.url == hit.browserUrl } == true || current?.bookmarks?.any { it.url == hit.browserUrl } == true
                if (!stillPresent || !UrlEngineRouter.isSafeWebUrl(hit.browserUrl)) error = "This browser entry changed. Search again."
                else onOpenBrowser(hit.browserUrl)
            }
            hit.downloadId != null || hit.messageId != null -> {
                if (active != null) return
                val found = result ?: return
                val generation = request
                active = scope.launch {
                    try {
                        if (hit.downloadId != null) {
                            val current = withContext(Dispatchers.IO) {
                                DownloadDatabase.get(application).downloads().searchMetadataDetail(hit.downloadId)?.takeIf { row ->
                                    hit.downloadMetadata?.let { LocalSqlMetadataSearch.currentDownload(row, it, found.query) } == true
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            if (request == generation && result === found) {
                                if (current == null) error = "This download changed or was removed. Search again."
                                else if (lifecycle.currentState == Lifecycle.State.RESUMED) onOpenDownload(current.id)
                            }
                        } else {
                            val current = withContext(Dispatchers.IO) {
                                OrezRoomDatabase.get(application).messages().searchMessageDetail(requireNotNull(hit.messageId))?.takeIf { row ->
                                    hit.messageMetadata?.let { LocalSqlMetadataSearch.currentMessage(row, it, found.query) } == true
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            if (request == generation && result === found) {
                                if (current == null) error = "This conversation changed or was removed. Search again."
                                else if (lifecycle.currentState == Lifecycle.State.RESUMED) detail = current
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (request == generation) error = "This saved result could not be opened. Try again." }
                    finally { if (request == generation) active = null }
                }
            }
        }
    }

    detail?.let { message -> AlertDialog(onDismissRequest = { detail = null },
        title = { Text(if (message.role == "YOU") "Your saved message" else "Saved Orez message") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text(message.snippet)
            if (message.snippet.codePointCount(0, message.snippet.length) == 16_000) Text("Showing up to the first 16,000 Unicode characters.", style = MaterialTheme.typography.labelSmall)
        } }, confirmButton = { TextButton({ detail = null }) { Text("Close") } }) }

    Scaffold(topBar = { TopAppBar(title = { Text("Search MangaLens") }, navigationIcon = {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(query, { query = it.take(LocalGlobalSearchPolicy.QUERY_CHARS); retire() }, Modifier.fillMaxWidth(),
                    label = { Text("Search saved content") }, singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row {
                        Checkbox(includeConversations, { includeConversations = it; retire() })
                        Text("Include Orez conversations", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(::search, enabled = active == null && query.isNotBlank()) { Text("Search") }
                if (active != null || recentVideoOpening) LinearProgressIndicator(Modifier.fillMaxWidth())
                recentVideoError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onSearchSavedText) { Text("OCR & translations") }
                    OutlinedButton(onOpenGlossary) { Text("Series glossary") }
                }
                if (onOpenSemanticSearch != null) TextButton({
                    if (lifecycle.currentState == Lifecycle.State.RESUMED) onOpenSemanticSearch(query.take(160))
                }) { Text("English semantic Library search · optional CPU model") }
                Text("Search stays on this device. Glossary results open the current selected series; OCR results keep saved-source checks.", style = MaterialTheme.typography.bodySmall)
            }
            result?.let { found ->
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(filter == null, { filter = null }, label = { Text("All") }) }
                        items(LocalSearchKind.entries) { kind -> FilterChip(filter == kind.name, { filter = kind.name }, label = { Text(kind.label) }) }
                    }
                    Text("Up to 16 matches per source. Search again to refresh changed content.", style = MaterialTheme.typography.bodySmall)
                    if (found.libraryIncomplete) Text("The first 5,000 loaded chapters were searched. Narrow the Library to find older chapters.", style = MaterialTheme.typography.bodySmall)
                    if (found.glossaryIncomplete) Text("Glossary search reached its 128-profile, 16 MiB or inventory/result limit, or a profile could not be read. Open its series glossary for the full current terms.", style = MaterialTheme.typography.bodySmall)
                    if (found.recentVideosLoading) Text("Recent video history is loading. Search again when it is ready.", style = MaterialTheme.typography.bodySmall)
                    if (found.recentVideosStorageError) Text("Recent video history has a storage error. Results use its last readable records.", style = MaterialTheme.typography.bodySmall)
                    found.sqlCoverage?.let { coverage ->
                        Text("Unicode matching checked ${coverage.downloadCoverage.checked} download title prefixes; ${coverage.downloadCoverage.matching} matched. Metadata read: ${coverage.receivedBytes / 1024} KiB.", style = MaterialTheme.typography.bodySmall)
                        if (coverage.downloadCoverage.truncated > 0) Text("${coverage.downloadCoverage.truncated} download titles exceeded the 768-character prefix limit.", style = MaterialTheme.typography.bodySmall)
                        if (coverage.downloadCoverage.remaining || coverage.downloadCoverage.byteLimited || coverage.downloadCoverage.omitted > 0)
                            Text("Download search is partial: up to 1,024 stored records and an 8 MiB shared metadata budget; ${coverage.downloadCoverage.omitted} invalid or repeated records were omitted. Open Downloads or search again to inspect current records.", style = MaterialTheme.typography.bodySmall)
                        coverage.messageCoverage?.let { messages ->
                            Text("Opted-in conversation search checked ${messages.checked} message prefixes; ${messages.matching} matched; ${messages.truncated} exceeded 16,000 characters.", style = MaterialTheme.typography.bodySmall)
                            if (messages.remaining || messages.byteLimited) Text("Conversation search is partial: up to 200 stored messages share the metadata budget. Saved text beyond each prefix is not searched.", style = MaterialTheme.typography.bodySmall)
                        }
                    } ?: Text("Downloaded titles and conversation text may need exact spelling for some scripts and compatibility characters.", style = MaterialTheme.typography.bodySmall)
                    if (found.browserRestoring) Text("Browser history is restoring. Search again when it is ready.", style = MaterialTheme.typography.bodySmall)
                }
                val visible = found.hits.filter { filter == null || it.kind.name == filter }
                if (visible.isEmpty()) item { Text("No matching saved content") }
                items(visible, key = { it.key }) { hit ->
                    OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = active == null && !recentVideoOpening) { open(hit) }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(hit.kind.label, style = MaterialTheme.typography.labelSmall)
                            Text(hit.title, style = MaterialTheme.typography.titleSmall)
                            Text(hit.snippet, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
