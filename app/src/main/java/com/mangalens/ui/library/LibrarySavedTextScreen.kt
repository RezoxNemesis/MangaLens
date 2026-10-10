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
import com.mangalens.core.translation.PreparedSavedTextOpen
import com.mangalens.core.translation.SavedTextSearchController
import com.mangalens.core.translation.memory.MemorySearchKind
import com.mangalens.orez.agent.OrezNativeMemoryHost

/** Explicit one-chapter lexical search over verified saved text and separate personal edits. */
@Composable
internal fun LibrarySavedTextScreen(chapter: SavedChapter?, onBack: () -> Unit,
    onOpen: (PreparedSavedTextOpen) -> Boolean, suppliedController: SavedTextSearchController? = null) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val host = remember(context) { OrezNativeMemoryHost(context) }
    val controller = remember(chapter?.id, suppliedController) { suppliedController ?: SavedTextSearchController(
        chapter?.id.orEmpty(), scope, host::searchDetailed, host::prepareOpen) }
    val accepted by controller.state.collectAsState()
    var query by rememberSaveable(chapter?.id) { mutableStateOf("") }
    val currentOpen by rememberUpdatedState(onOpen)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller) { controller.setQuery(query) }
    // A Library deletion/replacement retires displayed rows; the proof pipeline independently checks disk authority.
    LaunchedEffect(controller, chapter?.sourceUrl, chapter?.pages?.map { Triple(it.index, it.sourceUrl, it.localPath) }) {
        controller.setActive(false)
        controller.setActive(chapter != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(controller, lifecycle, chapter != null) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) controller.setActive(chapter != null)
            if (event == Lifecycle.Event.ON_STOP) controller.setActive(false)
        }
        lifecycle.addObserver(observer)
        controller.setActive(chapter != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); controller.close() }
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Search saved text", style = MaterialTheme.typography.headlineSmall)
            TextButton(onBack) { Text("Back") }
        } }
        if (chapter == null) {
            item { Text("This chapter is no longer saved. Return to the Library.") }
        } else {
            item { Text(chapter.title, style = MaterialTheme.typography.titleMedium) }
            item { Text("Search original OCR, saved translations and personal edits in this chapter. Results require current source and translation files.") }
            item { OutlinedTextField(query, { value -> if (value.length <= 256 && '\u0000' !in value) {
                query = value; controller.setQuery(value)
            } }, label = { Text("Words or phrase") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search text in selected chapter" }) }
            item { Button({ controller.search() }, enabled = query.isNotBlank() && !accepted.busy,
                modifier = Modifier.semantics { contentDescription = "Run selected chapter text search" }) { Text("Search") } }
            if (accepted.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            accepted.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = "Saved text search error: $message" }) } }
            val result = accepted.snapshot
            if (result != null) {
                val visibleRows = result.rows.filter { row -> chapter.sourceUrl == result.scope.chapter.sourceUrl &&
                    chapter.pages.any { it.index == row.hit.source.pageIndex && it.localPath == row.hit.source.sourcePath } }
                item { Text("${visibleRows.size} saved text results", modifier = Modifier.semantics { contentDescription = "Saved text results: ${visibleRows.size}" }) }
                if (result.incomplete) item { Text("Some saved entries could not be verified or the result limit was reached. Open the Reader to validate saved pages, then search again.") }
                if (visibleRows.isEmpty()) item { Text("No verified text matches this phrase.") }
                items(visibleRows, key = { it.id }) { row ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val label = when (row.hit.kind) {
                                MemorySearchKind.OCR -> "Original OCR"
                                MemorySearchKind.TRANSLATION -> "Saved translation"
                                MemorySearchKind.CORRECTED_OCR -> "Personal OCR"
                                MemorySearchKind.CORRECTED_TRANSLATION -> "Personal translation"
                            }
                            val ordinal = chapter.pages.indexOfFirst { it.index == row.hit.source.pageIndex }
                            Text("$label · page ${ordinal + 1} · ${row.hit.targetLanguage}", style = MaterialTheme.typography.labelLarge)
                            Text(row.hit.text, modifier = Modifier.semantics { contentDescription = "Saved text result ${row.id}: ${row.hit.text}" })
                            if (row.hit.kind in setOf(MemorySearchKind.CORRECTED_OCR, MemorySearchKind.CORRECTED_TRANSLATION))
                                Text("Personal edit version ${row.hit.revision}", style = MaterialTheme.typography.labelSmall)
                            TextButton({ controller.open(row.id, currentOpen) }, enabled = !accepted.busy,
                                modifier = Modifier.semantics { contentDescription = "Open saved text result ${row.id}" }) { Text("Open page") }
                        }
                    }
                }
            }
        }
    }
}
