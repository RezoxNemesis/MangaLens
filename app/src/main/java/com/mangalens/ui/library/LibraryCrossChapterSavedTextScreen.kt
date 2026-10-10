package com.mangalens.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.*
import com.mangalens.core.translation.memory.MemorySearchKind
import com.mangalens.orez.agent.OrezCrossChapterSavedTextHost
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

private data class SavedTextDisplayRow(val id: String, val scope: SavedTextChapterScope, val pageIndex: Int,
    val ordinal: Int, val kind: MemorySearchKind, val text: String, val target: String, val revision: Int, val native: Boolean)

/** Explicit offline lexical search; only current proven text reaches results or typed Reader navigation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryCrossChapterSavedTextScreen(library: List<SavedChapter>, onBack: () -> Unit,
    onOpen: (PreparedSavedTextOpen) -> Boolean, suppliedController: CrossChapterSavedTextSearchController? = null,
    initialQuery: String = "") {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val host = remember(context) { OrezCrossChapterSavedTextHost(context) }
    var selectedSource by rememberSaveable { mutableStateOf(SavedTextSearchSource.ALL) }
    val controller = remember(context, suppliedController, selectedSource) {
        val sourceForRequest = selectedSource
        suppliedController ?: CrossChapterSavedTextSearchController(scope,
            { query, refresh -> host.search(query, refresh, sourceForRequest) }, host::prepareOpen)
    }
    val accepted by controller.state.collectAsState()
    val warming by host.warmState.collectAsState()
    val nativeRevision by host.nativeRevision.collectAsState()
    var setupError by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf(initialQuery.take(256).replace("\u0000", "")) }
    val currentOpen by rememberUpdatedState(onOpen)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(host) { try { host.initialize() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { setupError = "Saved chapters could not be loaded. Reopen this search to retry." } }
    LaunchedEffect(controller) { controller.setQuery(query) }
    LaunchedEffect(controller, nativeRevision, library.map { it.id to (it.sourceUrl to it.pages.map { page -> page.index to page.localPath }) }) {
        controller.setActive(false)
        controller.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) controller.setActive(true)
            if (event == Lifecycle.Event.ON_STOP) { controller.setActive(false); host.pauseWarming() }
        }
        lifecycle.addObserver(observer)
        controller.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); controller.close() }
    }
    DisposableEffect(host) { onDispose { host.close() } }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Search saved text", style = MaterialTheme.typography.headlineSmall)
            TextButton(onBack) { Text("Back") }
        } }
        item { Text("Search original OCR and saved translations from completed saved pages, or your personal corrections. Text stays on this device.") }
        item { Text("Shows up to 8 verified matches from 4 chapters. The native inventory holds up to 64 saved receipts. Native chapters need indexing once after app restart; long chapters continue across bounded passes. Personal corrections retain the 64-page and 32 MiB source limit.", style = MaterialTheme.typography.bodySmall) }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SavedTextSearchSource.entries.forEach { source ->
                FilterChip(selectedSource == source, { selectedSource = source }, enabled = !accepted.busy,
                    label = { Text(when (source) { SavedTextSearchSource.ALL -> "All"; SavedTextSearchSource.NATIVE -> "Saved native"; SavedTextSearchSource.PERSONAL -> "Personal" }) })
            }
        } }
        item { OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${warming.verifiedTasks} of ${warming.totalTasks} loaded saved chapter receipts verified")
            if (warming.receivedBytes > 0) Text("${warming.receivedBytes / (1024 * 1024)} MiB checked", style = MaterialTheme.typography.bodySmall)
            if (warming.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (warming.headerLimitedTasks > 0) Text("${warming.headerLimitedTasks} receipts exceed this index's image-header inspection limit. Open those pages in Reader; this index cannot verify their image headers.")
            val otherBlocked = warming.blockedTasks - warming.headerLimitedTasks
            if (otherBlocked > 0) Text("$otherBlocked receipts could not be verified. Open their Reader to check missing or changed saved pages.")
            warming.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ scope.launch { try { host.startWarming() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { setupError = "Saved chapter indexing could not start. Reopen this search to retry." } } },
                    enabled = !warming.running, modifier = Modifier.semantics { contentDescription = "Index saved chapters or continue paused indexing" }) {
                    Text(if (warming.paused) "Continue indexing" else if (warming.blockedTasks > 0) "Retry indexing" else "Index saved chapters")
                }
                if (warming.running) OutlinedButton({ host.pauseWarming() }, modifier = Modifier.semantics { contentDescription = "Pause saved chapter indexing" }) { Text("Pause") }
            }
        } } }
        setupError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        item { OutlinedTextField(query, { value -> if (value.length <= 256 && '\u0000' !in value) {
            query = value; controller.setQuery(value)
        } }, label = { Text("Words or phrase") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search text across saved chapters" }) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ controller.search() }, enabled = query.isNotBlank() && !accepted.busy,
                modifier = Modifier.semantics { contentDescription = "Run cross chapter saved text search" }) { Text("Search") }
            OutlinedButton({ controller.search(refresh = true) }, enabled = query.isNotBlank() && !accepted.busy,
                modifier = Modifier.semantics { contentDescription = "Refresh personal index and search saved text" }) { Text("Refresh and search") }
        } }
        if (accepted.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        accepted.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { contentDescription = "Cross chapter saved text search error: $message" }) } }
        accepted.snapshot?.let { found ->
            val rows = found.nativeRows.map { SavedTextDisplayRow(it.id, it.scope, it.proof.page.index, it.pageOrdinal, it.kind,
                it.text, it.receipt.configuration.targetLanguage, 0, true) } + found.rows.map { SavedTextDisplayRow(it.id,
                it.chapterSnapshot.scope, it.row.hit.source.pageIndex, it.pageOrdinal, it.row.hit.kind, it.row.hit.text,
                it.row.hit.targetLanguage, it.row.hit.revision, false) }
            val visible = rows.filter { row -> library.any { chapter -> chapter.id == row.scope.chapter.id &&
                chapter.sourceUrl == row.scope.chapter.sourceUrl && chapter.pages.map { it.index to it.localPath } ==
                row.scope.chapter.pages.map { it.index to it.localPath } && chapter.pages.any { it.index == row.pageIndex } } }
            item { Text("${visible.size} verified results", modifier = Modifier.semantics { contentDescription = "Cross chapter saved text results: ${visible.size}" }) }
            if (found.incomplete) item { Text("Some matches are outside this view's limits or need indexing or Reader validation. Index saved chapters, then search again.") }
            if (visible.isEmpty()) item { Text("No verified saved text matches this phrase.") }
            items(visible, key = { it.id }) { row ->
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val kind = when (row.kind) { MemorySearchKind.OCR -> "Original OCR"; MemorySearchKind.TRANSLATION -> "Saved translation";
                        MemorySearchKind.CORRECTED_OCR -> "Personal OCR"; MemorySearchKind.CORRECTED_TRANSLATION -> "Personal translation" }
                    Text(row.scope.chapter.title, style = MaterialTheme.typography.titleMedium)
                    Text("$kind · page ${row.ordinal + 1} · ${row.target}", style = MaterialTheme.typography.labelLarge)
                    Text(row.text, modifier = Modifier.semantics { contentDescription = "Cross chapter saved text result ${row.id}: ${row.text}" })
                    if (!row.native && row.kind in setOf(MemorySearchKind.CORRECTED_OCR, MemorySearchKind.CORRECTED_TRANSLATION))
                        Text("Personal edit version ${row.revision}", style = MaterialTheme.typography.labelSmall)
                    TextButton({ controller.open(row.id, currentOpen) }, enabled = !accepted.busy,
                        modifier = Modifier.semantics { contentDescription = "Open cross chapter saved text result ${row.id}" }) { Text("Open page") }
                } }
            }
        }
    }
}
