package com.mangalens.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
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
import com.mangalens.core.translation.parseMemoryChapterOrdinal
import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/** Association is explicit; library display titles never create a series-memory identity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeriesMemoryScreen(chapter: SavedChapter?, targetLanguage: String, onBack: () -> Unit, suppliedStore: SeriesMemoryStore? = null, initialSeriesId: String = "", initialQuery: String = "") {
    val context = LocalContext.current.applicationContext
    val memory = remember(context, suppliedStore) { suppliedStore ?: SeriesMemoryStore(context.filesDir) }
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<SeriesMemoryProfile>>(emptyList()) }
    val directSeries = initialSeriesId.takeIf(::memoryValidId)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var selectedId by rememberSaveable(chapter?.id, initialSeriesId) { mutableStateOf<String?>(directSeries) }
    var termQuery by rememberSaveable(initialQuery) { mutableStateOf(runCatching { MemoryGlossaryMetadataQuery.query(initialQuery) }.getOrDefault("")) }
    var association by remember(memory, chapter?.id) { mutableStateOf<MemoryChapterAssociation?>(null) }
    var ordinal by rememberSaveable(chapter?.id) { mutableStateOf("") }
    var newTitle by rememberSaveable { mutableStateOf("") }
    var busy by remember(memory, chapter?.id) { mutableStateOf(false) }
    var error by remember(memory, chapter?.id) { mutableStateOf<String?>(null) }
    var message by remember(memory, chapter?.id) { mutableStateOf<String?>(null) }
    var termDialog by remember(memory, chapter?.id) { mutableStateOf(false) }
    var editingTerm by remember(memory, chapter?.id) { mutableStateOf<SeriesGlossaryTerm?>(null) }
    var deletingSeries by remember(memory, chapter?.id) { mutableStateOf<SeriesMemoryProfile?>(null) }
    var styleDialog by remember(memory, chapter?.id) { mutableStateOf(false) }
    var refresh by remember(memory, chapter?.id) { mutableIntStateOf(0) }
    var selectionInitialized by remember(memory, chapter?.id, initialSeriesId) { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(memory, chapter?.id, initialSeriesId, refresh) {
        try {
            error = null
            if (initialSeriesId.isNotEmpty()) {
                require(directSeries != null) { "This series identity is invalid. Return to search." }
                profiles = emptyList()
                val prepared = memory.prepareProfileRead(selectedId ?: directSeries)
                if (prepared?.tryDeliver { current -> profiles = listOf(current); true } != true)
                    error = "This series glossary changed or was removed. Return to search and refresh."
            } else profiles = memory.listSeries()
            association = chapter?.id?.let { memory.inspectChapter(it).association }
            if (!selectionInitialized) {
                if (initialSeriesId.isEmpty()) selectedId = association?.seriesId
                ordinal = association?.ordinal?.toString().orEmpty()
                selectionInitialized = true
            }
            if (selectedId != null && profiles.none { it.id == selectedId }) selectedId = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { error = problem.message ?: "Series memory could not be read." }
    }
    fun perform(success: String, action: suspend () -> Unit) {
        if (busy) return
        // Acquire before dispatch: a second click must see the pending write immediately.
        busy = true; error = null; message = null
        scope.launch {
            try { action(); message = success; refresh++ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { error = problem.message ?: "Series memory could not be saved. Please retry." }
            finally { busy = false }
        }
    }
    val selected = profiles.singleOrNull { it.id == selectedId }
    deletingSeries?.let { profile -> AlertDialog(onDismissRequest = { if (!busy) deletingSeries = null }, title = { Text("Remove series glossary?") },
        text = { Text("Remove ${profile.title}'s personal glossary and style preference? Saved chapter pages remain available.") },
        confirmButton = { TextButton({ perform("Series glossary removed.") { memory.removeSeries(profile.id); deletingSeries = null } }, enabled = !busy) { Text("Remove glossary") } },
        dismissButton = { TextButton({ deletingSeries = null }, enabled = !busy) { Text("Cancel") } }) }
    if (termDialog && selected != null) MemoryTermDialog(editingTerm, targetLanguage, busy, error,
        onDismiss = { if (!busy) { termDialog = false; error = null } }, onSave = { term -> perform("Glossary term saved.") {
            memory.upsertTerm(selected.id, term); termDialog = false
        } })
    if (styleDialog && selected != null) MemoryStyleDialog(selected.style, targetLanguage, busy, error,
        onDismiss = { if (!busy) styleDialog = false }, onSave = { style -> perform("Series style preference saved.") {
            memory.setStyle(selected.id, style); styleDialog = false
        } })

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Series memory", style = MaterialTheme.typography.headlineSmall)
            TextButton(onBack) { Text("Back") }
        } }
        item { Text("Choose an explicit series for shared spellings and earlier dialogue. Equal library titles do not link chapters.", style = MaterialTheme.typography.bodyMedium) }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { contentDescription = "Series memory error: $it" }) } }
        message?.let { item { Text(it, modifier = Modifier.semantics { contentDescription = "Series memory status: $it" }) } }
        item { OutlinedTextField(newTitle, { if (it.length <= 256) newTitle = it }, label = { Text("New series name") }, singleLine = true,
            enabled = !busy, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "New memory series name" }) }
        item { Button({ perform("Series created. Link a chapter explicitly below.") { selectedId = memory.createSeries(newTitle).id; newTitle = "" } }, enabled = newTitle.isNotBlank() && !busy,
            modifier = Modifier.semantics { contentDescription = "Create memory series" }) { Text("Create series") } }
        if (profiles.isEmpty() && initialSeriesId.isEmpty()) item { Text("No personal series yet.") }
        items(profiles, key = { it.id }) { profile ->
            FilterChip(profile.id == selectedId, { selectedId = profile.id }, enabled = !busy,
                label = { Text(profile.title) }, modifier = Modifier.semantics { contentDescription = "Select memory series: ${profile.title}" })
        }
        if (chapter != null) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(chapter.title, style = MaterialTheme.typography.titleMedium)
                    Text(association?.let { link -> "Linked to ${profiles.firstOrNull { it.id == link.seriesId }?.title ?: "an unavailable series"} · order ${link.ordinal?.toString() ?: "unknown"}" } ?: "Chapter-only memory",
                        modifier = Modifier.semantics { contentDescription = "Chapter memory association" })
                    OutlinedTextField(ordinal, { if (it.length <= 16) ordinal = it }, label = { Text("Chapter order (optional)") }, singleLine = true,
                        supportingText = { Text("0–1000000. Leave blank when order is unknown; earlier chapters then stay out of dialogue context.") }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Memory chapter order" })
                    Button({ perform("Chapter linked to the selected series.") { memory.associateChapter(chapter.id, requireNotNull(selectedId), parseMemoryChapterOrdinal(ordinal)) } },
                        enabled = selected != null && !busy, modifier = Modifier.semantics { contentDescription = "Link chapter to memory series" }) { Text("Link chapter") }
                    if (association != null) TextButton({ perform("Chapter unlinked. Its personal corrections remain saved.") { memory.unlinkChapter(chapter.id); ordinal = "" } },
                        enabled = !busy, modifier = Modifier.semantics { contentDescription = "Unlink memory series" }) { Text("Unlink chapter") }
                }
            }
        }
        if (selected != null) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${selected.title} glossary", style = MaterialTheme.typography.titleLarge)
                TextButton({ editingTerm = null; termDialog = true }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Add glossary term" }) { Text("Add term") }
            } }
            item { OutlinedTextField(termQuery, { value -> if (value.length <= 160 && value.none { it.code < 32 || it.code == 127 }) termQuery = value },
                label = { Text("Filter source, preferred spelling or aliases") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            if (initialSeriesId.isNotEmpty()) item { Text("Search opened this current series directly. Imported terms keep their saved source metadata; reopen the saved bubble to verify an edit.", style = MaterialTheme.typography.bodySmall) }
            if (selected.glossary.isEmpty()) item { Text("Add names, places, phrases or a preferred spelling. User-created terms have no source-page origin.") }
            val visibleTerms = selected.glossary.filter { termQuery.isBlank() || MemoryGlossaryMetadataQuery.matches(it, termQuery.trim()) }
            if (selected.glossary.isNotEmpty() && visibleTerms.isEmpty()) item { Text("No current glossary terms match this filter.") }
            items(visibleTerms, key = { it.id }) { term ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${term.source} → ${term.preferred}", style = MaterialTheme.typography.titleMedium)
                        Text("${term.targetLanguage} · ${term.kind.name.lowercase().replace('_', ' ')}")
                        if (term.aliases.isNotEmpty()) Text("Aliases: ${term.aliases.joinToString()}")
                        term.origin?.let { Text("Source page ${it.pageIndex}", style = MaterialTheme.typography.bodySmall) }
                        Row {
                            TextButton({ editingTerm = term; termDialog = true }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Edit glossary term: ${term.source}" }) { Text("Edit") }
                            TextButton({ perform("Glossary term removed.") { memory.removeTerm(selected.id, term.id) } }, enabled = !busy,
                                modifier = Modifier.semantics { contentDescription = "Remove glossary term: ${term.source}" }) { Text("Remove") }
                        }
                    }
                }
            }
            item { TextButton({ styleDialog = true }, enabled = !busy) { Text("Series style preference") } }
            selected.style?.let { style -> item { Text("${style.styleId} · ${style.targetLanguage}", style = MaterialTheme.typography.bodySmall) } }
            item { TextButton({ deletingSeries = selected }, enabled = !busy) { Text("Remove this series glossary") } }
        }
    }
}

@Composable
private fun MemoryTermDialog(original: SeriesGlossaryTerm?, target: String, busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: (SeriesGlossaryTerm) -> Unit) {
    var source by rememberSaveable(original?.id) { mutableStateOf(original?.source.orEmpty()) }
    var preferred by rememberSaveable(original?.id) { mutableStateOf(original?.preferred.orEmpty()) }
    var language by rememberSaveable(original?.id) { mutableStateOf(original?.targetLanguage ?: target) }
    var aliases by rememberSaveable(original?.id) { mutableStateOf(original?.aliases?.joinToString(", ").orEmpty()) }
    var kind by rememberSaveable(original?.id) { mutableStateOf((original?.kind ?: MemoryTermKind.NAME).name) }
    var kindMenu by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (original == null) "Add glossary term" else "Edit glossary term") }, text = {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(source, { if (it.length <= 256) source = it }, label = { Text("Source spelling") }, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Glossary source spelling" })
            OutlinedTextField(preferred, { if (it.length <= 256) preferred = it }, label = { Text("Preferred spelling") }, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Glossary preferred spelling" })
            OutlinedTextField(language, { if (it.length <= 32) language = it }, label = { Text("Target language") }, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Glossary target language" })
            OutlinedTextField(aliases, { if (it.length <= 4112) aliases = it }, label = { Text("Aliases (comma separated)") }, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Glossary aliases" })
            Box {
                TextButton({ kindMenu = true }, enabled = !busy) { Text("Kind: ${kind.lowercase().replace('_', ' ')}") }
                DropdownMenu(kindMenu, { kindMenu = false }) { MemoryTermKind.entries.forEach { value -> DropdownMenuItem(text = { Text(value.name.lowercase().replace('_', ' ')) }, onClick = { kind = value.name; kindMenu = false }) } }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({ onSave(SeriesGlossaryTerm(original?.id ?: UUID.randomUUID().toString(), source.trim(), preferred.trim(), language.trim(), MemoryTermKind.valueOf(kind),
        aliases.split(',').map { it.trim() }.filter { it.isNotEmpty() }, original?.origin, originSourceSha256 = original?.originSourceSha256)) },
        enabled = source.isNotBlank() && preferred.isNotBlank() && language.isNotBlank() && !busy,
        modifier = Modifier.semantics { contentDescription = "Save glossary term" }) { Text("Save term") } },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Cancel") } })
}

@Composable
private fun MemoryStyleDialog(original: SeriesStylePreference?, target: String, busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: (SeriesStylePreference?) -> Unit) {
    var language by rememberSaveable { mutableStateOf(original?.targetLanguage ?: target) }
    var instructions by rememberSaveable { mutableStateOf(original?.customInstructions.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Series style preference") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Save an explicit preference for this series. The current translation's captured settings stay fixed.")
            OutlinedTextField(language, { if (it.length <= 32) language = it }, label = { Text("Target language") }, enabled = !busy)
            OutlinedTextField(instructions, { if (it.length <= 2048) instructions = it }, label = { Text("Preferred style") }, enabled = !busy)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (original != null) TextButton({ onSave(null) }, enabled = !busy) { Text("Remove preference") }
        }
    }, confirmButton = { TextButton({ onSave(SeriesStylePreference("custom", language.trim(), instructions.trim())) }, enabled = language.isNotBlank() && instructions.isNotBlank() && !busy) { Text("Save preference") } },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Cancel") } })
}
