package com.mangalens.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import com.mangalens.orez.agent.OrezAgentContext
import com.mangalens.orez.agent.OrezAgentRuntime
import com.mangalens.orez.agent.OrezTaskStore
import com.mangalens.orez.agent.OrezTaskStatus
import com.mangalens.orez.agent.OrezStepStatus
import com.mangalens.orez.agent.OrezTaskPlan
import com.mangalens.orez.agent.OrezToolRegistry
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
    private val agentRuntime = OrezAgentRuntime()
    private val taskStore = OrezTaskStore(db.tasks())
    private val toolRegistry = OrezToolRegistry()
    private val enginePrefs = app.getSharedPreferences("orez_engine", android.content.Context.MODE_PRIVATE)

    val modelState get() = modelManager.state
    val messages = dao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeTasks = db.tasks().observeActive()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _engineMode = MutableStateFlow(readMode())
    val engineMode: StateFlow<OrezEngineMode> = _engineMode.asStateFlow()

    var typing by mutableStateOf(false)
        private set
    var thinkingStage by mutableStateOf("Ready")
        private set
    private var replyJob: Job? = null

    init {
        modelManager.refresh()
        // Do not load the local model merely because the Orez screen opened.
        val localState = modelManager.state.value
        thinkingStage = when {
            localState.optimized -> "Optimized local model installed • warm on demand"
            localState.legacyInstalled -> "Legacy 650 MB model detected • optimization available"
            else -> "Ready"
        }
        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow("orez-model").collect {
                val before = modelManager.state.value.installed
                modelManager.synchronizeWorkState(it)
                if (!before && modelManager.state.value.installed) {
                    thinkingStage = if (modelManager.state.value.optimized)
                        "Optimized local model installed • warm on demand"
                    else "Legacy local model detected"
                }
            }
        }
        viewModelScope.launch {
            taskStore.pruneFinished()
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

    fun sendMessage(
        query: String,
        libraryContext: String = "",
        chapterText: String = "",
        hasActiveChapter: Boolean = chapterText.isNotBlank(),
        activeUrl: String? = null,
        onTargetLanguage: (String) -> Unit = {},
        onRoute: (String, OrezRoute) -> Unit = { _, _ -> }
    ) {
        val input = query.trim()
        if (input.isBlank() || typing) return
        typing = true
        thinkingStage = "Understanding your request…"
        replyJob = viewModelScope.launch {
            var activePlan: OrezTaskPlan? = null
            try {
                dao.insert(OrezMessageEntity(role = "YOU", text = input))
                val appContext = OrezAgentContext(
                        hasActiveChapter = hasActiveChapter,
                        hasLibrary = libraryContext.isNotBlank(),
                        activeUrl = activeUrl
                    )
                var agentDecision = agentRuntime.decide(input, appContext)
                if (agentDecision.continueToBrain && answerRequest == null) {
                    brain.planAction(input, appContext)?.let { agentDecision = agentRuntime.decidePlan(it, appContext) }
                }
                activePlan = agentDecision.plan
                agentDecision.plan?.let { taskStore.checkpoint(it, it.status) }
                if (!agentDecision.continueToBrain) {
                    thinkingStage = if (agentDecision.requiresApproval) "Waiting for approval…" else "Executing MangaLens action…"
                    dao.insert(
                        OrezMessageEntity(
                            role = "OREZ",
                            text = agentDecision.message.ifBlank { "I prepared the requested MangaLens action." }
                        )
                    )
                    val route = agentDecision.immediateRoute
                    if (route != null) {
                        val plan = requireNotNull(agentDecision.plan)
                        val step = plan.steps.first()
                        toolRegistry.validate(step.call)
                        if (step.call.name == "enqueue_download") {
                            taskStore.checkpoint(plan, OrezTaskStatus.PLANNED)
                            com.mangalens.orez.agent.OrezDownloadTaskWorker.enqueue(getApplication<android.app.Application>(), plan.id)
                            activePlan = null
                            onRoute(agentDecision.routeValue, route)
                            thinkingStage = "Download queued • runs in background"
                            return@launch
                        }
                        activePlan = plan.copy(steps = listOf(step.copy(status = OrezStepStatus.RUNNING)))
                        taskStore.checkpoint(requireNotNull(activePlan))
                        step.call.arguments["targetLanguage"]?.let(onTargetLanguage)
                        onRoute(agentDecision.routeValue, route)
                        // Navigation completes here; long-running work belongs to its subsystem.
                        // Never claim a chapter translation/download finished merely on dispatch.
                        val handedOff = route !in setOf(OrezRoute.LIBRARY, OrezRoute.SETTINGS) &&
                            !(route == OrezRoute.DOWNLOADS && agentDecision.routeValue.isBlank())
                        taskStore.checkpoint(plan.copy(
                            status = if (handedOff) OrezTaskStatus.DISPATCHED else OrezTaskStatus.COMPLETED,
                            steps = listOf(step.copy(status = if (handedOff) OrezStepStatus.DISPATCHED else OrezStepStatus.COMPLETED))
                        ))
                        activePlan = null
                        thinkingStage = if (handedOff) "Handed to MangaLens • ready" else "Ready"
                    }
                    return@launch
                }

                // Questions mentioning URLs remain questions. All executable URL actions
                // pass the same structured registry and policy as other app tools.
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
                    (answerRequest ?: brain::answer)(
                        input,
                        OrezContext(
                            recentMessages = recent,
                            targetLanguage = targetLanguage,
                            libraryContext = libraryContext,
                            chapterText = chapterText.takeIf(String::isNotBlank)
                        )
                    )
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
                activePlan?.let { plan ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        taskStore.checkpoint(plan,
                            if (t is kotlinx.coroutines.CancellationException) OrezTaskStatus.CANCELLED else OrezTaskStatus.FAILED,
                            "Action interrupted. Check the destination before retrying.")
                    }
                }
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

    fun dismissTask(id: String) = viewModelScope.launch {
        taskStore.load(id)?.let { taskStore.checkpoint(it, OrezTaskStatus.CANCELLED) }
        androidx.work.WorkManager.getInstance(getApplication<android.app.Application>()).cancelUniqueWork("orez-task-$id")
    }

    fun taskProgress(encoded: String): Pair<Int, Int>? = runCatching {
        val plan = taskStore.decode(encoded)
        plan.steps.count { it.status == OrezStepStatus.COMPLETED } to plan.steps.size
    }.getOrNull()

    fun canResumeTask(encoded: String): Boolean = runCatching {
        com.mangalens.orez.agent.OrezDurablePlanRules.validate(taskStore.decode(encoded))
        true
    }.getOrDefault(false)

    fun resumeTask(id: String) = viewModelScope.launch {
        val plan = taskStore.load(id) ?: return@launch
        if (plan.status !in setOf(OrezTaskStatus.WAITING, OrezTaskStatus.FAILED)) return@launch
        try {
            com.mangalens.orez.agent.OrezDurablePlanRules.validate(plan)
            val next = plan.steps.firstOrNull { it.status != OrezStepStatus.COMPLETED } ?: return@launch
            com.mangalens.download.MediaDownloadManager(getApplication<android.app.Application>()).resume(
                com.mangalens.orez.agent.OrezDurablePlanRules.requestId(id, next.index))
            val resumable = plan.copy(status = OrezTaskStatus.PLANNED, steps = plan.steps.map {
                if (it.status == OrezStepStatus.FAILED) it.copy(status = OrezStepStatus.PENDING) else it
            })
            if (taskStore.checkpoint(resumable)) {
                com.mangalens.orez.agent.OrezDownloadTaskWorker.enqueue(getApplication<android.app.Application>(), id, replace = true)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            dao.insert(OrezMessageEntity(role = "OREZ", text = "Could not resume this task: " + failure.message.orEmpty().take(250)))
        }
    }

    fun stopReply() {
        replyJob?.cancel()
        thinkingStage = "Stopped • ready"
    }

    fun selectModelTier(tier: OrezModelTier) {
        modelManager.selectTier(tier)
        val descriptor = OrezModelCatalog.descriptor(tier)
        thinkingStage = if (modelManager.state.value.selectedInstalled) {
            (descriptor?.label ?: tier.displayName) + " selected • warm on demand"
        } else {
            (descriptor?.label ?: tier.displayName) + " selected • download when ready"
        }
    }

    fun downloadLocalModel() = modelManager.enqueue(modelManager.state.value.selectedTier)
    fun pauseLocalModel() = modelManager.pause()
    fun refreshLocalModel() = modelManager.refresh()

    fun deleteMessage(id: Long) = viewModelScope.launch {
        dao.deleteMessage(id)
    }

    fun deleteExchange(message: OrezMessageEntity) = viewModelScope.launch {
        if (message.role != "YOU") {
            dao.deleteMessage(message.id)
            return@launch
        }
        val until = dao.nextUserMessageId(message.id) ?: Long.MAX_VALUE
        dao.deleteRange(message.id, until)
    }

    fun clearConversation() = viewModelScope.launch {
        stopReply()
        dao.clear()
    }

    private fun readMode(): OrezEngineMode = runCatching {
        OrezEngineMode.valueOf(enginePrefs.getString("mode", OrezEngineMode.HYBRID_AUTO.name) ?: OrezEngineMode.HYBRID_AUTO.name)
    }.getOrDefault(OrezEngineMode.HYBRID_AUTO)

    override fun onCleared() {
        stopReply()
        brain.close()
        super.onCleared()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OrezAiScreen(
    vm: OrezAiViewModel = viewModel(),
    library: List<com.mangalens.core.reader.SavedChapter> = emptyList(),
    chapterText: String = "",
    hasActiveChapter: Boolean = chapterText.isNotBlank(),
    activeUrl: String? = null,
    onTargetLanguage: (String) -> Unit = {},
    onImport: (List<android.net.Uri>) -> Unit = {},
    onRoute: (String, OrezRoute) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val messages by vm.messages.collectAsState()
    val modelState by vm.modelState.collectAsState()
    val engineMode by vm.engineMode.collectAsState()
    val activeTasks by vm.activeTasks.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var showEngine by rememberSaveable { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<OrezMessageEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val expandedMessages = remember { mutableStateMapOf<Long, Boolean>() }
    val list = rememberLazyListState()
    val libraryContext = remember(library) {
        library.take(30).joinToString("\n") { chapter ->
            buildString {
                append(chapter.title)
                append(" • status=").append(chapter.readingStatus.label)
                append(" • progress=")
                    .append((chapter.position + 1).coerceAtMost(chapter.pages.size.coerceAtLeast(1)))
                    .append("/").append(chapter.pages.size.coerceAtLeast(1))
                if (chapter.bookmarked) append(" • bookmarked")
            }
        }
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { if (it.isNotEmpty()) onImport(it) }

    LaunchedEffect(messages.size) {
        if (messages.size > 1) list.animateScrollToItem(messages.size + 3)
    }

    selectedMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { selectedMessage = null },
            title = { Text(if (message.role == "YOU") "Message options" else "OREZ message") },
            text = { Text("Long-press actions keep the chat clean without placing delete buttons on every message.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteExchange(message)
                    selectedMessage = null
                }) { Text(if (message.role == "YOU") "Delete exchange" else "Delete message") }
            },
            dismissButton = {
                Row {
                    if (message.role == "YOU") {
                        TextButton(onClick = {
                            vm.deleteMessage(message.id)
                            selectedMessage = null
                        }) { Text("Delete only this") }
                    }
                    TextButton(onClick = { selectedMessage = null }) { Text("Cancel") }
                }
            }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear Orez conversation?") },
            text = { Text("This removes the visible chat history on this device. Orez model files and translation memory stay installed.") },
            confirmButton = {
                TextButton(onClick = { vm.clearConversation(); confirmClear = false }) { Text("Clear chat") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 16.dp)) {
        BrandHeader("Orez AI", "YOUR STORIES · YOUR INTELLIGENCE") {
            IconButton({ showEngine = !showEngine }) { Icon(Icons.Outlined.Tune, "OREZ engine") }
        }

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 14.dp)
        ) {
            item {
                NeonActionTile("Translate a chapter", "Import images, PDF or CBZ", Icons.Outlined.Translate,
                    Modifier.fillMaxWidth()) { picker.launch(com.mangalens.core.reader.DocumentImporter.MIME_TYPES) }
            }

            if (activeTasks.isNotEmpty()) {
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Orez tasks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    activeTasks.first().objective.take(110),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            NeonStatusPill(activeTasks.size.toString() + " tracked", positive = true)
                        }
                        activeTasks.take(5).forEach { task ->
                            Text(task.objective, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                            val progress = remember(task.planJson) { vm.taskProgress(task.planJson) }
                            Text("${task.status.lowercase().replace('_', ' ')}" +
                                (progress?.let { " • ${it.first}/${it.second} steps verified" } ?: ""),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            task.lastError?.takeIf { it.isNotBlank() }?.let {
                                Text(it, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                            }
                            Row {
                                if (task.status in setOf("WAITING", "FAILED") && vm.canResumeTask(task.planJson)) {
                                    TextButton(onClick = { vm.resumeTask(task.id) }) { Text("Resume task") }
                                }
                                TextButton(onClick = { input = task.objective }) { Text("Review request") }
                                TextButton(onClick = { vm.dismissTask(task.id) }) { Text("Dismiss task") }
                            }
                        }
                    }
                }
            }

            item {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { AssistChip(onClick = { input = "Find manga: " }, label = { Text("Find") }) }
                    item { AssistChip(onClick = { input = "Summarize this chapter faithfully without spoilers." }, label = { Text("Summarize") }) }
                    item { AssistChip(onClick = { input = "Help me improve OCR for this chapter." }, label = { Text("Fix OCR") }) }
                    item { AssistChip(onClick = { input = "Help me plan my reading list from my saved chapters." }, label = { Text("Reading list") }) }
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
                                    when {
                                        modelState.downloading ->
                                            "Installing " + (modelState.selectedDescriptor?.label ?: modelState.selectedTier.displayName) +
                                                " • " + ((modelState.progress * 100).toInt()) + "%"
                                        modelState.selectedInstalled ->
                                            (modelState.selectedDescriptor?.label ?: modelState.selectedTier.displayName) +
                                                " local intelligence • " + (modelState.total / 1_000_000L) + " MB"
                                        modelState.legacyInstalled && modelState.installedTiers.isEmpty() ->
                                            "Legacy local model • " + (OrezModelManager.LEGACY_MODEL_BYTES / 1_000_000L) +
                                                " MB • upgrade recommended"
                                        else ->
                                            (modelState.selectedDescriptor?.label ?: modelState.selectedTier.displayName) +
                                                " available • " + (modelState.total / 1_000_000L) + " MB"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            NeonStatusPill(
                                when {
                                    modelState.downloading -> "Installing"
                                    modelState.selectedInstalled -> modelState.selectedTier.displayName
                                    modelState.legacyInstalled -> "Legacy pack"
                                    else -> "App + web logic"
                                },
                                positive = modelState.selectedInstalled
                            )
                        }

                        if (modelState.downloading) {
                            LinearProgressIndicator(progress = { modelState.progress }, modifier = Modifier.fillMaxWidth())
                            TextButton(vm::pauseLocalModel) { Text("Pause model download") }
                        }
                        modelState.error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }

                        Text("Local intelligence", style = MaterialTheme.typography.labelLarge)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OrezModelCatalog.availableDescriptors.forEach { descriptor ->
                                FilterChip(
                                    selected = modelState.selectedTier == descriptor.tier,
                                    enabled = !modelState.downloading,
                                    onClick = { vm.selectModelTier(descriptor.tier) },
                                    label = { Text(descriptor.label, maxLines = 1) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Text(
                            "Lite stays fast on modest phones. Core installs separately for stronger local reasoning and falls back to Lite when available memory is too low.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

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
                            "The local model loads only when an answer needs it and releases memory after idle. If it is slow or memory-constrained, Orez falls back instead of freezing scrolling or navigation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { confirmClear = true }) { Text("Clear conversation") }

                        if (!modelState.downloading && !modelState.selectedInstalled) {
                            Button(
                                vm::downloadLocalModel,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                            ) {
                                Text(
                                    "Download " +
                                        (modelState.selectedDescriptor?.label ?: modelState.selectedTier.displayName) +
                                        " model"
                                )
                            }
                        } else if (modelState.selectedInstalled) {
                            TextButton(vm::refreshLocalModel) { Text("Recheck model health →") }
                        }
                    }
                }
            }

            items(messages, key = { it.id }) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.role == "YOU") Arrangement.End else Arrangement.Start) {
                    Card(
                        Modifier.widthIn(max = 360.dp).combinedClickable(
                            onClick = {},
                            onLongClick = { selectedMessage = message }
                        ),
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
                                    if (message.role == "YOU") "YOU" else "OREZ AI",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.Bold
                                )
                                if (message.role != "YOU") {
                                    Text(
                                        if (message.text.contains("\n\nSources:") || message.text.contains(OrezVideoResultCodec.MARKER)) "WEB SOURCES"
                                        else when (engineMode) {
                                            OrezEngineMode.LOCAL_LITE -> "MODE: LOCAL LITE"
                                            OrezEngineMode.HYBRID_AUTO -> "MODE: HYBRID"
                                            OrezEngineMode.WEB_ASSIST -> "MODE: WEB ASSIST"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            val answerBody = message.text
                                .substringBefore("\n\nSources:")
                                .substringBefore(OrezVideoResultCodec.MARKER)
                            val expanded = expandedMessages[message.id] == true
                            Text(if (expanded || answerBody.length <= 1800) answerBody else answerBody.take(1800).trimEnd() + "…")
                            if (answerBody.length > 1800) {
                                TextButton(onClick = { expandedMessages[message.id] = !expanded }) {
                                    Text(if (expanded) "Show less" else "Show full answer")
                                }
                            }

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
                                    TextButton({ onRoute(video.url, OrezRoute.WEB_VIEW) }) { Text("Web") }
                                    TextButton({ onRoute(video.url, OrezRoute.VIDEO_PLAYER) }) { Text("Play") }
                                }
                            }

                            if (message.text.contains("\n\nSources:")) {
                                message.text.substringAfter("\n\nSources:")
                                    .lineSequence().map { it.trim() }
                                    .filter(com.mangalens.core.router.UrlEngineRouter::isSafeWebUrl)
                                    .take(6).forEach { url ->
                                        TextButton({ onRoute(url, OrezRoute.WEB_VIEW) }) {
                                            Text(java.net.URI(url).host ?: "Source", maxLines = 1)
                                        }
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
                        vm.sendMessage(query, libraryContext, chapterText, hasActiveChapter, activeUrl, onTargetLanguage, onRoute)
                    },
                    enabled = input.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
                ) { Icon(Icons.Outlined.Send, "Send") }
            }
        }
    }
}

