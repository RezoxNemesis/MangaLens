package com.mangalens.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mangalens.engine.OrezLiveSearchConnector
import com.mangalens.orez.*
import com.mangalens.ui.components.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OrezAiViewModel @JvmOverloads constructor(
    app: android.app.Application,
    private val answerRequest: (suspend (String, OrezContext) -> OrezBrainResponse)? = null
) : AndroidViewModel(app) {
    private val db = OrezRoomDatabase.get(app)
    private val dao = db.messages()
    private val brain = OrezBrain(db, app) { q -> OrezLiveSearchConnector().search(q, 6) }
    private val modelManager = OrezModelManager(app)
    private val enginePrefs = app.getSharedPreferences("orez_engine", android.content.Context.MODE_PRIVATE)

    val modelState get() = modelManager.state
    val messages = dao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _engineMode = MutableStateFlow(readMode())
    val engineMode: StateFlow<OrezEngineMode> = _engineMode.asStateFlow()

    var typing by mutableStateOf(false)
        private set
    var thinkingStage by mutableStateOf("Ready")
        private set
    private var replyJob: Job? = null

    init {
        modelManager.refresh()
        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow("orez-model").collect {
                val before = modelManager.state.value.installed
                modelManager.refresh()
                if (!before && modelManager.state.value.installed) {
                    thinkingStage = "Optimizing local model…"
                    brain.warmLocalModel()
                    thinkingStage = "Local model ready"
                }
            }
        }
        if (modelManager.state.value.installed) {
            viewModelScope.launch {
                thinkingStage = "Warming local model…"
                brain.warmLocalModel()
                thinkingStage = "Local model ready"
            }
        }
        viewModelScope.launch {
            if (dao.count() == 0) {
                dao.insert(OrezMessageEntity(role = "OREZ", text = "Namaste! Main OREZ hoon. Main local knowledge, reasoning, OCR/translation workflows aur current web information ko coordinate kar sakta hoon."))
            }
        }
    }

    fun setEngineMode(mode: OrezEngineMode) {
        _engineMode.value = mode
        enginePrefs.edit().putString("mode", mode.name).apply()
        thinkingStage = when (mode) {
            OrezEngineMode.LOCAL_LITE -> "Local Lite • on-device only"
            OrezEngineMode.HYBRID_AUTO -> "Hybrid Auto • local first"
            OrezEngineMode.WEB_ASSIST -> "Web Assist • source-backed"
        }
    }

    fun sendMessage(query: String, onRoute: (String, OrezRoute) -> Unit = { _, _ -> }) {
        val input = query.trim()
        if (input.isBlank() || typing) return
        typing = true
        thinkingStage = "Understanding your request…"
        replyJob = viewModelScope.launch {
            try {
                dao.insert(OrezMessageEntity(role = "YOU", text = input))
                val command = OrezCommandRouter().route(input)
                if (command.route != OrezRoute.CHAT) {
                    thinkingStage = "Opening the right MangaLens tool…"
                    dao.insert(OrezMessageEntity(role = "OREZ", text = "Opening " + command.route.name.lowercase().replace('_', ' ') + " flow."))
                    onRoute(command.originalInput, command.route)
                    return@launch
                }

                val recent = messages.value.takeLast(10)
                val targetLanguage = getApplication<android.app.Application>()
                    .getSharedPreferences("mangalens_preferences", android.content.Context.MODE_PRIVATE)
                    .getString("translation_target", "hi") ?: "hi"

                thinkingStage = when (_engineMode.value) {
                    OrezEngineMode.LOCAL_LITE -> "Using local intelligence…"
                    OrezEngineMode.HYBRID_AUTO -> "Choosing the fastest reliable route…"
                    OrezEngineMode.WEB_ASSIST -> "Checking readable web sources…"
                }

                val answer = kotlinx.coroutines.withTimeoutOrNull(32_000L) {
                    (answerRequest ?: brain::answer)(input, OrezContext(recentMessages = recent, targetLanguage = targetLanguage))
                } ?: OrezBrainResponse(
                    "That request exceeded the response budget. I kept the app responsive instead of letting the local model lock the chat. Try again or switch Engine Mode.",
                    OrezIntent.GENERAL
                )

                currentCoroutineContext().ensureActive()
                thinkingStage = "Preparing answer…"
                val sources = answer.sources.distinct()
                    .filter(com.mangalens.core.router.UrlEngineRouter::isSafeWebUrl)
                    .take(6)
                val resultText = answer.text +
                    (if (sources.isEmpty()) "" else "\n\nSources:\n" + sources.joinToString("\n")) +
                    (if (answer.videos.isEmpty()) "" else OrezVideoResultCodec.MARKER + OrezVideoResultCodec.encode(answer.videos))
                dao.insert(OrezMessageEntity(role = "OREZ", text = resultText))
                thinkingStage = "Ready"
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                dao.insert(OrezMessageEntity(role = "OREZ", text = "I hit a recoverable error: " + (t.message ?: "unknown error") + ". The chat stayed alive, so you can retry immediately."))
                thinkingStage = "Recovered • ready"
            } finally {
                typing = false
                replyJob = null
                runCatching { dao.trimHistory() }
            }
        }
    }

    fun stopReply() {
        replyJob?.cancel()
        thinkingStage = "Stopped • ready"
    }

    fun downloadLocalModel() = modelManager.enqueue()
    fun refreshLocalModel() = modelManager.refresh()

    private fun readMode(): OrezEngineMode = runCatching {
        OrezEngineMode.valueOf(enginePrefs.getString("mode", OrezEngineMode.HYBRID_AUTO.name) ?: OrezEngineMode.HYBRID_AUTO.name)
    }.getOrDefault(OrezEngineMode.HYBRID_AUTO)

    override fun onCleared() {
        stopReply()
        brain.close()
        super.onCleared()
    }
}

@Composable
fun OrezAiScreen(
    vm: OrezAiViewModel = viewModel(),
    library: List<com.mangalens.core.reader.SavedChapter> = emptyList(),
    chapterText: String = "",
    onImport: (List<android.net.Uri>) -> Unit = {},
    onRoute: (String, OrezRoute) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val messages by vm.messages.collectAsState()
    val modelState by vm.modelState.collectAsState()
    val engineMode by vm.engineMode.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var showEngine by rememberSaveable { mutableStateOf(true) }
    val list = rememberLazyListState()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { if (it.isNotEmpty()) onImport(it) }

    LaunchedEffect(messages.size) {
        if (messages.size > 1) list.animateScrollToItem(messages.size + 3)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 16.dp)) {
        BrandHeader("OREZ AI 2.0", "YOUR READING COMPANION") {
            IconButton({ showEngine = !showEngine }) { Icon(Icons.Outlined.Tune, "OREZ engine") }
        }

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 14.dp)
        ) {
            item {
                ArtworkHero(
                    "Orez 2.0 is ready",
                    "Hybrid reasoning, OCR repair, chapter help and subtitle assistance in one place.",
                    com.mangalens.R.drawable.orez_portrait,
                    "Translate a chapter"
                ) { picker.launch(com.mangalens.core.reader.DocumentImporter.MIME_TYPES) }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Quick actions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text("SMART WORKSPACE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonActionTile("Find manga", "Titles, genres, recommendations", Icons.Outlined.Search, Modifier.weight(1f)) { input = "Find manga: " }
                    NeonActionTile("Summarize", "Chapter or story context", Icons.Outlined.Summarize, Modifier.weight(1f)) {
                        input = if (chapterText.isBlank()) "How do I import and summarize a chapter?"
                        else "Summarize this chapter faithfully. Keep character relationships and emotional tone. Do not infer missing pages.\n" + chapterText.take(6000)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonActionTile("Fix OCR", "Repair awkward text", Icons.Outlined.CenterFocusStrong, Modifier.weight(1f)) {
                        input = "Help me improve the OCR and translation of this chapter while preserving the original tone."
                    }
                    NeonActionTile("Reading list", "Library & progress", Icons.Outlined.MenuBook, Modifier.weight(1f)) {
                        input = "Help me plan my reading list from these saved chapters only: " + library.joinToString { it.title }.take(5000)
                    }
                }
            }

            item {
                AnimatedVisibility(
                    visible = showEngine,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Panel(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("OREZ Engine", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                                Text(
                                    if (modelState.installed) "Local model optimized • ${OrezModelManager.MODEL_BYTES / 1_000_000L} MB"
                                    else if (modelState.downloading) "Installing local model • ${(modelState.progress * 100).toInt()}%"
                                    else "Optional local model • ${OrezModelManager.MODEL_BYTES / 1_000_000L} MB",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            NeonStatusPill(
                                if (modelState.installed) "Model healthy" else if (modelState.downloading) "Installing" else "Cloud + app logic",
                                positive = modelState.installed
                            )
                        }

                        if (modelState.downloading) {
                            LinearProgressIndicator(progress = { modelState.progress }, modifier = Modifier.fillMaxWidth())
                        }

                        Text("Engine mode", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                OrezEngineMode.LOCAL_LITE to "Local Lite",
                                OrezEngineMode.HYBRID_AUTO to "Hybrid Auto",
                                OrezEngineMode.WEB_ASSIST to "Web Assist"
                            ).forEach { (mode, label) ->
                                FilterChip(
                                    selected = engineMode == mode,
                                    onClick = { vm.setEngineMode(mode) },
                                    label = { Text(label, maxLines = 1) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NeonStatusPill("Warm start", positive = modelState.installed)
                            NeonStatusPill("Fast fallback", positive = true)
                            NeonStatusPill("Story context", positive = true)
                        }

                        Text(
                            "The 650 MB model now has a strict response budget. If it is slow or memory-constrained, Orez falls back instead of freezing the chat.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!modelState.installed && !modelState.downloading) {
                            Button(vm::downloadLocalModel, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
                                Text("Download optimized local model")
                            }
                        } else if (modelState.installed) {
                            TextButton(vm::refreshLocalModel) { Text("Recheck model health →") }
                        }
                    }
                }
            }

            items(messages, key = { it.id }) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.role == "YOU") Arrangement.End else Arrangement.Start) {
                    Card(
                        Modifier.widthIn(max = 360.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (message.role == "YOU") MaterialTheme.colorScheme.primaryContainer.copy(alpha = .82f)
                            else MaterialTheme.colorScheme.surface.copy(alpha = .94f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (message.role == "YOU") MaterialTheme.colorScheme.primary.copy(alpha = .45f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = .55f)
                        )
                    ) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    if (message.role == "YOU") "YOU" else "OREZ AI 2.0",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.Bold
                                )
                                if (message.role != "YOU") {
                                    Text(
                                        when (engineMode) {
                                            OrezEngineMode.LOCAL_LITE -> "LOCAL"
                                            OrezEngineMode.HYBRID_AUTO -> "HYBRID"
                                            OrezEngineMode.WEB_ASSIST -> "WEB"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(message.text.substringBefore("\n\nSources:").substringBefore(OrezVideoResultCodec.MARKER))

                            OrezVideoResultCodec.fromMessage(message.text).forEach { video ->
                                video.thumbnail?.let {
                                    coil.compose.AsyncImage(
                                        it, video.title, Modifier.fillMaxWidth().height(150.dp),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                }
                                Text(video.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    listOfNotNull(
                                        video.creator.takeIf { it.isNotBlank() },
                                        video.durationSeconds?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
                                        video.uploadDate
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row {
                                    TextButton({ onRoute(video.url, OrezRoute.WEB_VIEW) }) { Text("Open") }
                                    TextButton({ onRoute(video.url, OrezRoute.VIDEO_PLAYER) }) { Text("Play") }
                                }
                            }

                            if (message.text.contains("\n\nSources:")) {
                                message.text.substringAfter("\n\nSources:")
                                    .lineSequence().map { it.trim() }
                                    .filter(com.mangalens.core.router.UrlEngineRouter::isSafeWebUrl)
                                    .take(6).forEach { url ->
                                        TextButton({
                                            runCatching {
                                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                                            }
                                        }) { Text(java.net.URI(url).host ?: "Source", maxLines = 1) }
                                    }
                            }
                        }
                    }
                }
            }

            item {
                AnimatedVisibility(vm.typing) {
                    Panel(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Column {
                                Text("Orez is working", fontWeight = FontWeight.Bold)
                                Text(vm.thinkingStage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f), maxLines = 4,
                placeholder = { Text("Ask Orez anything…") },
                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
            )
            if (vm.typing) {
                FilledTonalButton(vm::stopReply, contentPadding = PaddingValues(horizontal = 13.dp, vertical = 14.dp)) {
                    Icon(Icons.Outlined.Stop, "Stop")
                }
            } else {
                Button(
                    onClick = {
                        val query = input
                        input = ""
                        vm.sendMessage(query, onRoute)
                    },
                    enabled = input.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
                ) { Icon(Icons.Outlined.Send, "Send") }
            }
        }
    }
}
