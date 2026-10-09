package com.mangalens.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mangalens.core.reader.*
import com.mangalens.ui.MangaLensUiState
import com.mangalens.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(state: MangaLensUiState, onDeleteChapter: (String) -> Unit, onOpenSavedChapter: (String) -> Unit,
    onOpenReader: () -> Unit, onOpenLocalVideo: () -> Unit,
    onChapterDetails: (String, Boolean, ReadingStatus) -> Unit,
    onChapterMetadata: (suspend (String, LibraryChapterMetadata) -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { LibrarySettingsStore(context) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var optionsJson by rememberSaveable { mutableStateOf<String?>(null) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var savingSettings by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    val options = LibraryOptionsCodec.decode(optionsJson)
    var availability by remember { mutableStateOf<Map<String, LibraryOfflineFacts>>(emptyMap()) }
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(settings) {
        if (optionsJson == null) optionsJson = LibraryOptionsCodec.encode(withContext(Dispatchers.IO) { settings.load() })
    }
    LaunchedEffect(state.library, refresh) {
        val captured = state.library.toList()
        // Hide old availability during rechecking rather than showing stale "fully offline" facts.
        availability = emptyMap()
        availability = withContext(Dispatchers.IO) { LibraryAvailability.capture(captured, File(context.filesDir, "chapters")) }
    }
    fun acceptOptions(changed: LibraryOptions, closeSheet: Boolean = false) {
        if (savingSettings || optionsJson == null) return
        val encoded = LibraryOptionsCodec.encode(changed)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            savingSettings = true; settingsError = null
            try {
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { settings.save(changed) }
                    optionsJson = encoded
                    if (closeSheet) filtersOpen = false
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { settingsError = failure.message ?: "Library filters could not be saved. Please retry." }
            finally { savingSettings = false }
        }
    }
    var result by remember { mutableStateOf<LibraryQueryResult?>(null) }
    LaunchedEffect(state.library, query, options, availability) {
        result = null
        if (query.isNotBlank()) delay(120)
        result = withContext(Dispatchers.Default) { LibraryQuery.evaluate(state.library, query, options, availability) }
    }
    val chapters = result?.chapters.orEmpty()
    val sources = LibraryQuery.sources(state.library)
    val collections = LibraryQuery.collections(state.library)
    val continueChapter = chapters.filter { it.readingStatus == ReadingStatus.READING }
        .maxWithOrNull(compareBy<SavedChapter> { it.lastReadAt }.thenBy { if (it.lastReadAt == 0L) it.updatedAt else 0L }.thenBy { it.id })
    state.library.firstOrNull { it.id == pendingDeleteId }?.let { chapter ->
        AlertDialog(onDismissRequest = { pendingDeleteId = null }, title = { Text("Delete saved chapter?") },
            text = { Text("Remove ${chapter.title} and its offline pages? Shared pages are retained.") },
            confirmButton = { TextButton({ onDeleteChapter(chapter.id); pendingDeleteId = null }) { Text("Delete") } },
            dismissButton = { TextButton({ pendingDeleteId = null }) { Text("Cancel") } })
    }
    if (filtersOpen) LibraryFilterSheet(options, sources, collections, savingSettings, settingsError,
        onDismiss = { if (!savingSettings) filtersOpen = false }, onApply = { acceptOptions(it, true) })
    val editing = state.library.firstOrNull { it.id == editingId }
    if (editingId != null && onChapterMetadata != null) LibraryMetadataSheet(editingId!!, editing, collections,
        onDismiss = { editingId = null }, onSave = { metadata -> onChapterMetadata(editingId!!, metadata); editingId = null })

    LazyVerticalGrid(GridCells.Adaptive(140.dp),
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .045f), MaterialTheme.colorScheme.background))).statusBarsPadding(),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { BrandHeader("My Library", "YOUR STORIES, YOUR PACE") }
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(query, { if (it.length <= 512) query = it }, Modifier.fillMaxWidth().semantics { contentDescription = "Search saved chapters" },
                singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { IconButton(onClick = { filtersOpen = true }, enabled = optionsJson != null && !savingSettings) {
                    Icon(Icons.Outlined.Tune, "Library filters and sort")
                } }, placeholder = { Text("Title, series, notes, collection or source") }, shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(options.status == null, { acceptOptions(options.copy(status = null)) }, enabled = optionsJson != null && !savingSettings,
                        label = { Text("All (${result?.allStatusCount ?: 0})") })
                    ReadingStatus.displayOrder.forEach { status ->
                        val count = result?.statusCounts?.get(status) ?: 0
                        FilterChip(options.status == status, { acceptOptions(options.copy(status = status)) }, enabled = optionsJson != null && !savingSettings,
                            modifier = Modifier.semantics { contentDescription = "Filter status: ${status.label}" }, label = { Text("${status.label} ($count)", maxLines = 1) })
                    }
                    FilterChip(options.bookmarksOnly, { acceptOptions(options.copy(bookmarksOnly = !options.bookmarksOnly)) }, enabled = optionsJson != null && !savingSettings,
                        modifier = Modifier.semantics { contentDescription = "Filter bookmarks" }, label = { Text("Bookmarks") })
                }
                Text("${chapters.size} of ${state.library.size} chapters · ${options.sort.label}", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.semantics { contentDescription = "Library results: ${chapters.size} of ${state.library.size}, ${options.sort.label}" })
                if (options.sourceKey != null || options.collection != null || options.offline != LibraryOfflineFilter.ANY) {
                    Text(listOfNotNull(options.sourceKey?.let { key -> sources.firstOrNull { it.key == key }?.label ?: key.removePrefix("host:") },
                        options.collection, options.offline.takeUnless { it == LibraryOfflineFilter.ANY }?.label).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                if (result == null || optionsJson == null || savingSettings || (options.offline != LibraryOfflineFilter.ANY && availability.size < state.library.size)) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!filtersOpen && settingsError != null) Text(settingsError!!, color = MaterialTheme.colorScheme.error)
                if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
            }
        }
        continueChapter?.let { chapter -> item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Continue reading", style = MaterialTheme.typography.titleMedium)
            Surface(onClick = { onOpenSavedChapter(chapter.id) }, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, null)
                    Column(Modifier.weight(1f)) {
                        Text(chapter.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        Text("Page ${(chapter.position + 1).coerceAtMost(chapter.pages.size)} of ${chapter.pages.size}", style = MaterialTheme.typography.labelSmall)
                        availability[chapter.id]?.let { facts -> Text(if (facts.fullyOffline) "Available offline" else "${facts.downloaded}/${facts.total} pages offline", style = MaterialTheme.typography.labelSmall) }
                    }
                    Icon(Icons.Outlined.PlayArrow, "Continue reading")
                }
            }
        } } }
        if (chapters.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Panel {
                Text(if (result == null) "Searching saved chapters…" else if (state.library.isEmpty()) "Your library starts here" else "No matching chapters", style = MaterialTheme.typography.titleLarge)
                Text(if (state.library.isEmpty()) "Import a chapter or open a manga link from Home. Reading lists are saved on this device." else
                    "Try another search or clear the combined filters.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.library.isNotEmpty() && result != null) TextButton({ query = ""; acceptOptions(LibraryOptions(sort = options.sort)) }, enabled = !savingSettings) { Text("Clear search and filters") }
            }
        }
        items(chapters, key = { it.id }) { chapter ->
            var menu by remember { mutableStateOf(false) }
            Column(Modifier.semantics { contentDescription = "Saved chapter: ${chapter.title}" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChapterCover(chapter, { onOpenSavedChapter(chapter.id) }, menu = {
                    IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "Chapter actions for ${chapter.title}", tint = androidx.compose.ui.graphics.Color.White) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(if (chapter.bookmarked) "Remove bookmark" else "Bookmark") }, onClick = { onChapterDetails(chapter.id, !chapter.bookmarked, chapter.readingStatus); menu = false })
                        ReadingStatus.displayOrder.forEach { status -> DropdownMenuItem(text = { Text(status.label) }, onClick = { onChapterDetails(chapter.id, chapter.bookmarked, status); menu = false }) }
                        if (onChapterMetadata != null) DropdownMenuItem(text = { Text("Edit series, notes and collections") },
                            modifier = Modifier.semantics { contentDescription = "Edit library details for ${chapter.title}" }, onClick = { editingId = chapter.id; menu = false })
                        DropdownMenuItem(text = { Text("Delete offline chapter") }, onClick = { pendingDeleteId = chapter.id; menu = false })
                    }
                })
                if (chapter.seriesTitle.isNotBlank()) Text(chapter.seriesTitle, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(chapter.readingStatus.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                if (chapter.collections.isNotEmpty()) Text(chapter.collections.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                availability[chapter.id]?.let { facts -> Text(if (facts.fullyOffline) "Offline · ${facts.total} pages" else "${facts.downloaded}/${facts.total} pages offline", style = MaterialTheme.typography.bodySmall) }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Panel {
                Text("Local Media Player", style = MaterialTheme.typography.titleLarge)
                Text("Play videos from your device or document provider.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onOpenLocalVideo, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) { Text("Browse videos →") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LibraryFilterSheet(accepted: LibraryOptions, sources: List<LibrarySource>, collections: List<String>, saving: Boolean,
    error: String?, onDismiss: () -> Unit, onApply: (LibraryOptions) -> Unit) {
    var draftJson by rememberSaveable { mutableStateOf(LibraryOptionsCodec.encode(accepted)) }
    val draft = LibraryOptionsCodec.decode(draftJson)
    fun change(options: LibraryOptions) { draftJson = LibraryOptionsCodec.encode(options) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Library filters and sort", style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sort by", style = MaterialTheme.typography.titleMedium)
                LibrarySort.entries.forEach { sort ->
                    FilterChip(draft.sort == sort, { change(draft.copy(sort = sort)) }, enabled = !saving,
                        modifier = Modifier.semantics { contentDescription = "Sort library: ${sort.label}" }, label = { Text(sort.label) })
                }
                Text("Older imports without a saved date appear after dated chapters. Last read changes only when you open the reader.", style = MaterialTheme.typography.bodySmall)
                Text("Reading status", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(draft.status == null, { change(draft.copy(status = null)) }, enabled = !saving, label = { Text("Any status") })
                    ReadingStatus.displayOrder.forEach { status -> FilterChip(draft.status == status, { change(draft.copy(status = status)) }, enabled = !saving,
                        modifier = Modifier.semantics { contentDescription = "Choose status filter: ${status.label}" }, label = { Text(status.label) }) }
                }
                FilterChip(draft.bookmarksOnly, { change(draft.copy(bookmarksOnly = !draft.bookmarksOnly)) }, enabled = !saving,
                    modifier = Modifier.semantics { contentDescription = "Choose bookmarks filter" }, label = { Text("Bookmarked chapters only") })
                Text("Source", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(draft.sourceKey == null, { change(draft.copy(sourceKey = null)) }, enabled = !saving, label = { Text("Any source") })
                    sources.forEach { source -> FilterChip(draft.sourceKey == source.key, { change(draft.copy(sourceKey = source.key)) }, enabled = !saving,
                        modifier = Modifier.semantics { contentDescription = "Choose source filter: ${source.label}" }, label = { Text(source.label) }) }
                }
                Text("Collections", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(draft.collection == null, { change(draft.copy(collection = null)) }, enabled = !saving, label = { Text("Any collection") })
                    collections.forEach { collection -> FilterChip(draft.collection?.let(::libraryTextKey) == libraryTextKey(collection), { change(draft.copy(collection = collection)) }, enabled = !saving,
                        modifier = Modifier.semantics { contentDescription = "Choose collection filter: $collection" }, label = { Text(collection) }) }
                }
                if (collections.isEmpty()) Text("Add a collection in a chapter's details.", style = MaterialTheme.typography.bodySmall)
                Text("Offline pages", style = MaterialTheme.typography.titleMedium)
                LibraryOfflineFilter.entries.forEach { offline -> FilterChip(draft.offline == offline, { change(draft.copy(offline = offline)) }, enabled = !saving,
                    modifier = Modifier.semantics { contentDescription = "Choose offline filter: ${offline.label}" }, label = { Text(offline.label) }) }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ change(LibraryOptions()) }, enabled = !saving) { Text("Reset") }
                Spacer(Modifier.weight(1f))
                TextButton(onDismiss, enabled = !saving) { Text("Cancel") }
                Button({ onApply(draft) }, enabled = !saving) { Text(if (saving) "Saving…" else "Apply") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LibraryMetadataSheet(chapterId: String, chapter: SavedChapter?, knownCollections: List<String>,
    onDismiss: () -> Unit, onSave: suspend (LibraryChapterMetadata) -> Unit) {
    var series by rememberSaveable(chapterId) { mutableStateOf(chapter?.seriesTitle.orEmpty()) }
    var notes by rememberSaveable(chapterId) { mutableStateOf(chapter?.notes.orEmpty()) }
    var names by rememberSaveable(chapterId) { mutableStateOf(ArrayList(chapter?.collections.orEmpty())) }
    var newName by rememberSaveable(chapterId) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by rememberSaveable(chapterId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun addName(name: String) {
        try {
            val normalized = LibraryChapterMetadata(series, notes, names + name).normalized()
            names = ArrayList(normalized.collections); newName = ""; error = null
        } catch (failure: IllegalArgumentException) { error = failure.message }
    }
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Chapter details", style = MaterialTheme.typography.headlineSmall)
            Text(chapter?.title ?: "Chapter is no longer saved", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(series, { if (it.length <= LibraryChapterMetadata.MAX_SERIES_LENGTH) series = it }, Modifier.fillMaxWidth().semantics { contentDescription = "Library series title" }, label = { Text("Series title") }, singleLine = true, enabled = !saving)
                OutlinedTextField(notes, { if (it.length <= LibraryChapterMetadata.MAX_NOTES_LENGTH) notes = it }, Modifier.fillMaxWidth().semantics { contentDescription = "Library chapter notes" }, label = { Text("Chapter notes") }, minLines = 4, maxLines = 8,
                    supportingText = { Text("${notes.length}/${LibraryChapterMetadata.MAX_NOTES_LENGTH}") }, enabled = !saving)
                Text("Collections", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    names.forEach { name -> InputChip(true, { if (!saving) names = ArrayList(names.filterNot { it == name }) }, enabled = !saving,
                        label = { Text(name) }, trailingIcon = { Icon(Icons.Outlined.Close, null) }, modifier = Modifier.semantics { contentDescription = "Remove collection: $name" }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newName, { if (it.length <= LibraryChapterMetadata.MAX_COLLECTION_LENGTH) newName = it }, Modifier.weight(1f).semantics { contentDescription = "Library new collection" }, label = { Text("New collection") }, singleLine = true, enabled = !saving)
                    Button({ addName(newName) }, enabled = !saving && newName.isNotBlank() && names.size < LibraryChapterMetadata.MAX_COLLECTIONS) { Text("Add") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    knownCollections.filterNot { existing -> names.any { libraryTextKey(it) == libraryTextKey(existing) } }.forEach { name ->
                        SuggestionChip({ addName(name) }, enabled = !saving && names.size < LibraryChapterMetadata.MAX_COLLECTIONS, label = { Text(name) })
                    }
                }
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onDismiss, enabled = !saving) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    val captured = LibraryChapterMetadata(series, notes, names.toList())
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        saving = true; error = null
                        try { onSave(captured.normalized()) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Details could not be saved. Please retry." }
                        finally { saving = false }
                    }
                }, enabled = !saving && chapter != null) { Text(if (saving) "Saving…" else "Save details") }
            }
        }
    }
}
