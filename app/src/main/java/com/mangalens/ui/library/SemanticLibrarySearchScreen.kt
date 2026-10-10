package com.mangalens.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.mangalens.core.search.embedding.*
import com.mangalens.core.translation.PreparedSavedTextOpen
import com.mangalens.core.translation.memory.MemorySearchKind
import com.mangalens.orez.agent.OrezCrossChapterSavedTextHost
import com.mangalens.ui.search.LocalGlobalSearchPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SemanticLibrarySearchScreen(library: List<SavedChapter>, initialQuery: String = "", onBack: () -> Unit,
    onOpenChapter: (SemanticLibraryMetadataEntry) -> Unit, onLexicalSearch: (String) -> Unit,
    onOpenSavedText: ((PreparedSavedTextOpen) -> Boolean)? = null) {
    val application = LocalContext.current.applicationContext
    val manager = remember(application) { SemanticModelManager.shared(application) }
    val provider = remember(application, manager) { SemanticNativeEmbeddingProvider.shared(application, manager.store) }
    val cache = remember(application) { SemanticVectorIndex(application.filesDir) }
    val scope = rememberCoroutineScope()
    val controller = remember(provider, cache, scope) { SemanticLibrarySearchController(scope, provider, cache) }
    val host = remember(application) { OrezCrossChapterSavedTextHost(application) }
    val dialogueCache = remember(application) { SemanticVectorIndex(application.filesDir, SemanticVectorCorpus.DIALOGUE) }
    val dialogue = remember(provider, dialogueCache, scope, host) { NativeSavedDialogueSemanticController(scope, host, provider, dialogueCache) }
    var savedDialogue by rememberSaveable { mutableStateOf(false) }
    val dialogueState by dialogue.state.collectAsState()
    val warming by host.warmState.collectAsState()
    val nativeRevision by host.nativeRevision.collectAsState()
    var sourceError by remember { mutableStateOf<String?>(null) }
    var warmingRequest by remember { mutableLongStateOf(0L) }
    val currentSavedOpen by rememberUpdatedState(onOpenSavedText)
    val model by manager.state.collectAsState()
    val nativeBusy by provider.busy.collectAsState()
    val restartRequired by provider.restartRequired.collectAsState()
    val search by controller.state.collectAsState()
    val latestLibrary by rememberUpdatedState(library)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery.take(160)) }
    var license by remember { mutableStateOf<String?>(null) }
    var licenseTitle by remember { mutableStateOf("") }
    var licenseError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(library) { controller.setLibrary(library); dialogue.setLibrary(library) }
    LaunchedEffect(query) { controller.setQuery(query); dialogue.setQuery(query) }
    LaunchedEffect(nativeRevision) { dialogue.setRevision(nativeRevision) }
    LaunchedEffect(host, savedDialogue) { if (savedDialogue) try { host.initialize() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { sourceError = "Saved native dialogue could not be loaded. Reopen this search to retry." }
    }
    DisposableEffect(host) { onDispose { host.close() } }
    DisposableEffect(lifecycle, controller, dialogue, savedDialogue) {
        val started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        controller.setActive(started && !savedDialogue); dialogue.setActive(started && savedDialogue)
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> { controller.setActive(!savedDialogue); dialogue.setActive(savedDialogue) }
            Lifecycle.Event.ON_STOP -> { controller.setActive(false); dialogue.setActive(false); warmingRequest++; host.pauseWarming() }
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.close(); dialogue.close(); warmingRequest++; host.pauseWarming() }
    }
    fun openLicense(asset: String, title: String, maximumBytes: Int = 32_768) {
        licenseError = null
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    application.assets.open(asset).use { input ->
                        val bytes = input.readBytesBounded(maximumBytes)
                        bytes.toString(Charsets.UTF_8)
                    }
                }
                licenseTitle = title; license = text
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { licenseError = "The bundled license could not be read." }
        }
    }
    license?.let { text -> AlertDialog(onDismissRequest = { license = null }, title = { Text(licenseTitle) },
        text = { LazyColumn(Modifier.heightIn(max = 440.dp)) { items(text.chunked(8_192)) { part -> Text(part, style = MaterialTheme.typography.bodySmall) } } },
        confirmButton = { TextButton({ license = null }) { Text("Close") } }) }
    val ready = model.verified && !model.checking && !model.downloading && !model.stopping
    val result = search.result?.takeIf { it.query == query.trim() }
    val dialogueResult = dialogueState.result?.takeIf { it.query == query.trim() }
    val busy = if (savedDialogue) dialogueState.busy else search.busy
    val visibleDialogue = dialogueResult?.hits.orEmpty().filter { NativeSavedDialogueSemanticController.matchesLibrary(latestLibrary, it.row) }
    val visibleHits = result?.hits.orEmpty().filter { hit -> SemanticLibraryMetadata.current(latestLibrary, hit.entry) }
    Scaffold(topBar = { TopAppBar(title = { Text("English semantic Library search") }, navigationIcon = {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(!savedDialogue, { savedDialogue = false }, label = { Text("Library metadata") })
                    FilterChip(savedDialogue, { savedDialogue = true }, label = { Text("Verified saved dialogue") })
                }
                Text(if (savedDialogue) "Find saved pages by the meaning of their verified original OCR or native translations. Personal corrections use ordinary OCR search."
                    else "Find chapters by the meaning of their saved titles, series labels and notes.")
                Text("English first · all-MiniLM-L6-v2 · sentence-transformers · Apache 2.0", style = MaterialTheme.typography.bodySmall)
                Text("This optional CPU model downloads 90.4 MB after installation. The APK contains no semantic model weights. Similarity scores are rankings, not confidence or a translation guarantee.", style = MaterialTheme.typography.bodySmall)
                TextButton({ onLexicalSearch(query.take(160)) }) { Text("Use ordinary saved-content search") }
            }
            item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when {
                        restartRequired -> "Restart MangaLens before more native work"
                        model.checking -> "Checking the pinned model"
                        model.stopping -> "Pausing model download"
                        model.downloading -> "Downloading the pinned English search model"
                        model.verified -> "Model file verified · CPU search available"
                        else -> "Optional English search model is not verified"
                    }, style = MaterialTheme.typography.titleSmall)
                    if (model.downloading || model.stopping) {
                        LinearProgressIndicator(progress = { (model.receivedBytes.toDouble() / model.totalBytes).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${model.receivedBytes / 1_000_000.0} / 90.4 MB received", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(manager::pauseDownload, enabled = model.downloading && !model.stopping) { Text("Pause download") }
                    } else if (!model.checking) {
                        if (!model.verified) Button(manager::download, enabled = !restartRequired) { Text(if (model.receivedBytes > 0) "Resume pinned download" else "Download English search model") }
                        OutlinedButton(manager::recheck, enabled = !nativeBusy) { Text("Recheck model file") }
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("The model file is verified by its whole SHA-256. The graph and actual output shape are checked when CPU search runs.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton({ openLicense(SemanticEmbeddingPin.LICENSE_ASSET, "Model publisher · Apache 2.0") }) { Text("Model license") }
                        TextButton({ openLicense("third_party/onnxruntime-LICENSE.txt", "ONNX Runtime · MIT") }) { Text("Runtime license") }
                    }
                    TextButton({ openLicense("third_party/onnxruntime-ThirdPartyNotices.txt", "Runtime third-party notices", 400_000) }) { Text("Runtime third-party notices") }
                    TextButton({ openLicense("semantic/MODEL-CARD.md", "Published model information") }) { Text("Publisher model information") }
                    licenseError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } }
            }
            if (savedDialogue) item {
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${warming.verifiedTasks} / ${warming.totalTasks} loaded native receipts have current whole-source verification")
                    Text("${warming.receivedBytes / (1024 * 1024)} MiB checked across explicit 32 MiB passes. Continue reaches long chapters; proofs are process-local.", style = MaterialTheme.typography.bodySmall)
                    if (warming.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (warming.headerLimitedTasks > 0) Text("${warming.headerLimitedTasks} receipts exceed the 64 KiB image-header inspection limit; this index cannot verify those headers.", style = MaterialTheme.typography.bodySmall)
                    if (warming.blockedTasks > warming.headerLimitedTasks) Text("Some saved files need Reader repair or validation before indexing.", style = MaterialTheme.typography.bodySmall)
                    warming.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ val expected = ++warmingRequest; scope.launch { try { host.startWarming { warmingRequest == expected && savedDialogue && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) } }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { sourceError = "Saved source verification could not start. Retry this screen." }
                        } }, enabled = !warming.running && !busy && !nativeBusy) { Text(if (warming.paused) "Continue source verification" else "Verify saved sources") }
                        if (warming.running) OutlinedButton({ warmingRequest++; host.pauseWarming() }) { Text("Pause verification") }
                    }
                    sourceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } }
            }
            item {
                OutlinedTextField(query, { changed -> query = changed.take(160); controller.setQuery(query); dialogue.setQuery(query) }, Modifier.fillMaxWidth(),
                    label = { Text("Describe a chapter in English") }, singleLine = true)
                Button({ if (savedDialogue) { dialogue.setLibrary(latestLibrary); dialogue.setQuery(query); dialogue.search() }
                    else { controller.setLibrary(latestLibrary); controller.setQuery(query); controller.search() } },
                    enabled = ready && !busy && !nativeBusy && !warming.running && !restartRequired && query.isNotBlank()) {
                    Text(if (savedDialogue) "Index next dialogue & search" else if (result != null && result.indexed < result.candidates) "Index next chapters & search" else "Search chapter meaning")
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${if (savedDialogue) dialogueState.encoded else search.encodedThisPass} / ${if (savedDialogue) dialogueState.planned else search.plannedThisPass} texts encoded this pass", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({ if (savedDialogue) dialogue.pause() else controller.pause() }) { Text("Pause indexing") }
                } else if (nativeBusy && !restartRequired) {
                    Text("Finishing the previous CPU pass and closing its native session. Search becomes available after the real close.", style = MaterialTheme.typography.bodySmall)
                }
                if (restartRequired) Text("A native resource could not be confirmed closed. Its capacity remains reserved until the app process restarts.", color = MaterialTheme.colorScheme.error)
                (if (savedDialogue) dialogueState.error else search.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item {
                Text("Each explicit pass encodes at most 32 new records plus the query, with one CPU session and a 30-second pass budget. A single native call can finish after that budget. Indexing pauses for low memory or critical device pressure.", style = MaterialTheme.typography.bodySmall)
                if (!savedDialogue) Text("The first 1,024 loaded Library records and up to 16 MiB of metadata are considered. Oversized records and records without Latin letters are omitted. English quality is not established for mixed-language titles. Text is capped at 4,096 characters and 128 tokens; committed vectors use at most 4 MiB, with up to 4 MiB of temporary atomic-write storage.", style = MaterialTheme.typography.bodySmall)
                if (savedDialogue) {
                    Text("Considers up to 1,024 Latin-containing native OCR/translation fields, 4,096 page/field visits and 8 MiB of field/identity text from the loaded 64-receipt inventory. Each pass checks at most 64 new candidate proofs and 64 ranked hints; at most 32 new vectors from four chapters are encoded. Personal corrections are separate.", style = MaterialTheme.typography.bodySmall)
                    Text("Text uses at most 4,096 characters and 128 tokenizer positions. English-first relevance is unqualified for mixed-language OCR or translations. Dialogue vectors are hints in a separate 4 MiB committed cache with up to 4 MiB atomic staging; current whole source/native/profile and Reader checks still control each result open.", style = MaterialTheme.typography.bodySmall)
                    dialogueResult?.let { found ->
                        Text("${found.indexed} / ${found.catalogue.fields.size} candidate fields indexed · ${found.catalogue.checkedFields} checked · ${found.catalogue.omitted} omitted fields · ${found.catalogue.invalidTasks} invalid receipts · ${found.needsProof} attempts need current proof", style = MaterialTheme.typography.bodySmall)
                        Text("${found.catalogue.visits} visits · ${found.catalogue.receivedBytes / 1024} KiB metadata · ${visibleDialogue.size} current results", style = MaterialTheme.typography.bodySmall)
                        if (found.catalogue.inventoryLimited || found.passLimited) Text("This search reached an inventory, proof, ranking or pass limit. Continue explicit indexing; content outside the bounded candidate prefix is omitted.", style = MaterialTheme.typography.bodySmall)
                        if (found.cacheUnreadable) Text("The previous derived dialogue index was unreadable; this pass rebuilt current hints.", style = MaterialTheme.typography.bodySmall)
                        if (visibleDialogue.isEmpty()) Text("No verified indexed dialogue results. Verify saved sources, continue indexing or use ordinary OCR search.")
                    }
                }
                if (!savedDialogue) result?.let { found ->
                    Text("${found.indexed} / ${found.candidates} chapter records indexed · ${found.omitted} omitted · ${found.truncated} indexed records truncated", style = MaterialTheme.typography.bodySmall)
                    if (found.inventoryLimited) Text("The inventory or metadata byte limit omitted additional chapters. Narrow the saved Library contents for broader coverage.", style = MaterialTheme.typography.bodySmall)
                    if (found.cacheUnreadable) Text("The previous derived index could not be read; this explicit pass rebuilt current hints.", style = MaterialTheme.typography.bodySmall)
                    if (found.passLimited) Text("The pass reached its time limit. Use Index next chapters & search to continue.", style = MaterialTheme.typography.bodySmall)
                    if (visibleHits.isEmpty()) Text("No current indexed chapter matches are available. Continue indexing or use ordinary search.")
                }
            }
            if (savedDialogue) items(visibleDialogue, key = { it.field.key }) { hit ->
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(hit.row.chapterTitle, style = MaterialTheme.typography.titleSmall)
                    Text((if (hit.row.kind == MemorySearchKind.OCR) "Original OCR" else "Saved translation") +
                        " · page ${hit.row.pageOrdinal + 1} · ${hit.row.receipt.configuration.targetLanguage}", style = MaterialTheme.typography.labelSmall)
                    Text(LocalGlobalSearchPolicy.snippet(hit.field.input, query.take(160)), style = MaterialTheme.typography.bodySmall)
                    Text("Dialogue similarity ${String.format(Locale.ROOT, "%.3f", hit.score)}" + if (hit.truncated) " · truncated" else "", style = MaterialTheme.typography.labelSmall)
                    TextButton({ if (savedDialogue && lifecycle.currentState == Lifecycle.State.RESUMED && dialogueState.result === dialogueResult &&
                        NativeSavedDialogueSemanticController.matchesLibrary(latestLibrary, hit.row)) dialogue.open(hit) { prepared ->
                            lifecycle.currentState == Lifecycle.State.RESUMED && currentSavedOpen?.invoke(prepared) == true
                        } }, enabled = !busy && currentSavedOpen != null) { Text("Open verified page") }
                } }
            }
            if (!savedDialogue) items(visibleHits, key = { it.entry.cacheKey }) { hit ->
                Card(Modifier.fillMaxWidth().clickable {
                    if (lifecycle.currentState == Lifecycle.State.RESUMED && search.result === result &&
                        SemanticLibraryMetadata.current(latestLibrary, hit.entry)) onOpenChapter(hit.entry)
                }) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(hit.entry.title, style = MaterialTheme.typography.titleSmall)
                    if (hit.entry.series.isNotBlank()) Text(hit.entry.series, style = MaterialTheme.typography.bodySmall)
                    Text("Metadata similarity ${String.format(Locale.ROOT, "%.3f", hit.score)}" + if (hit.truncated) " · truncated" else "", style = MaterialTheme.typography.labelSmall)
                } }
            }
        }
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8_192)
    while (output.size() <= limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) break
        require(count > 0)
        output.write(buffer, 0, count)
    }
    require(output.size() <= limit)
    return output.toByteArray()
}
