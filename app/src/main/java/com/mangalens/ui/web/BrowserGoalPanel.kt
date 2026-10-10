package com.mangalens.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mangalens.orez.*
import com.mangalens.orez.agent.*
import com.mangalens.core.compute.NativeComputePrecondition
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** User-goal planning is foreground-only. Typed manual tools remain available without a model. */
@Composable
internal fun BrowserGoalPanel(tools: OrezBrowserTools, enabled: Boolean, onActiveChange: (Boolean) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val closed = remember(tools) { AtomicBoolean(false) }
    val service = remember(tools) { lazy { OrezLocalModelService(OrezModelManager(context)) } }
    val model = remember(tools) { object : BrowserGoalModel {
        override suspend fun capturePin(): OrezModelPin? = withContext(Dispatchers.IO) {
            check(!closed.get()); val local = service.value
            if (closed.get()) { local.close(); throw CancellationException("Browser planner closed.") }
            local.captureModelPin(OrezModelTask.BROWSER_GOAL)
        }
        override suspend fun propose(input: String, pin: OrezModelPin, owner: NativeComputePrecondition): String? {
            check(!closed.get())
            return OrezBrowserGoalPlanner(service.value).propose(input, pin, owner)
        }
    } }
    var goal by remember(tools) { mutableStateOf("") }
    var session by remember(tools) { mutableStateOf<BrowserGoalSession?>(null) }
    var goalOwner by remember(tools) { mutableStateOf<BrowserDomOwner?>(null) }
    var view by remember(tools) { mutableStateOf<BrowserGoalView?>(null) }
    var status by remember(tools) { mutableStateOf<String?>(null) }
    var confirmation by remember(tools) { mutableStateOf<BrowserGoalProposal?>(null) }
    var busy by remember(tools) { mutableStateOf(false) }
    var job by remember(tools) { mutableStateOf<Job?>(null) }
    var epoch by remember(tools) { mutableLongStateOf(0L) }

    fun stop(message: String = "Foreground browser goal stopped.") {
        epoch++; session?.retire(); job?.cancel(); job = null; busy = false
        confirmation = null; view = null; goalOwner = null; status = message; onActiveChange(false)
    }
    fun run(captured: BrowserGoalSession, operation: suspend () -> BrowserGoalView) {
        if (busy) return
        val capturedEpoch = epoch; busy = true; status = "Reading the current page and reasoning locally…"
        job = scope.launch {
            try {
                var next = operation()
                // The explicit foreground goal authorizes bounded read/scroll steps. Network
                // mutations always stop here for their own exact native confirmation.
                while (!next.finished && next.proposal?.call?.risk in setOf(OrezToolRisk.READ_ONLY, OrezToolRisk.LOCAL_MUTATION)) {
                    currentCoroutineContext().ensureActive()
                    check(capturedEpoch == epoch && session === captured && captured.ownsCurrentPage())
                    val proposed = requireNotNull(next.proposal)
                    status = "Running a bounded page step: ${proposed.call?.name?.removePrefix("browser_")}."
                    next = captured.execute(proposed, approved = false)
                }
                if (capturedEpoch == epoch && session === captured && captured.ownsCurrentPage()) {
                    view = next; status = next.summary; onActiveChange(!next.finished)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) {
                if (capturedEpoch == epoch) {
                    status = failure.message ?: "Local browser planning is unavailable. Inspect the current page before retrying."
                    captured.retire(); view = null; confirmation = null; goalOwner = null; onActiveChange(false)
                }
            } finally { if (capturedEpoch == epoch) { busy = false; job = null } }
        }
    }
    DisposableEffect(tools) { onDispose {
        closed.set(true); session?.retire(); job?.cancel()
        if (service.isInitialized()) service.value.close()
    } }
    LaunchedEffect(session, goalOwner) {
        val captured = session; val source = goalOwner
        if (captured != null && source != null) while (isActive) {
            delay(500)
            if (tools.currentOwner() != source || view != null && !captured.ownsCurrentPage()) {
                val receipt = captured.summary()
                stop("$receipt The page changed or this goal expired. Start a fresh goal for the current page.")
                break
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ask Orez about this page", style = MaterialTheme.typography.titleSmall)
        Text("Describe a question or browser goal. Orez uses the installed local model and visible page evidence. Read and scroll steps are bounded; review each website-changing action.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(goal, {
            if (it.length <= BrowserGoalPlanDecoder.MAX_GOAL && it != goal) { stop(""); goal = it }
        },
            label = { Text("Your page question or goal") }, enabled = enabled && !busy && confirmation == null && view?.finished != false, maxLines = 4)
        Row {
            TextButton(onClick = {
                val owner = tools.currentOwner()
                if (owner == null) { status = "Wait for a loaded current page."; return@TextButton }
                stop(""); epoch++; goalOwner = owner
                val created = BrowserGoalSession(tools, model); session = created; onActiveChange(true)
                run(created) { created.start(goal) }
            }, enabled = enabled && !busy && goal.isNotBlank() && tools.currentOwner() != null) { Text("Plan locally") }
            TextButton(onClick = { val receipt = session?.summary().orEmpty(); stop("Stopped. $receipt") }, enabled = busy || view?.finished == false) { Text("Stop") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        status?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        view?.takeIf { session?.ownsCurrentPage() == true }?.let { current ->
            current.proposal?.answer?.takeIf { it.isNotBlank() }?.let {
                Text("Local answer / suggestion", style = MaterialTheme.typography.labelLarge)
                Text(it)
            }
            current.proposal?.takeIf { it.call != null && !current.finished }?.let { proposal ->
                val call = requireNotNull(proposal.call)
                Text(actionDescription(call, current.page), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    if (call.risk == OrezToolRisk.NETWORK_MUTATION) confirmation = proposal
                    else session?.let { captured -> run(captured) { captured.execute(proposal, approved = false) } }
                }, enabled = !busy && enabled) { Text(if (call.risk == OrezToolRisk.NETWORK_MUTATION) "Review proposed action" else "Run proposed step") }
            }
            if (current.finished) Text("No further action proposed. Goal completion is not verified.", style = MaterialTheme.typography.bodySmall)
            if (current.page.text.isNotBlank()) {
                Text("Latest visible page evidence", style = MaterialTheme.typography.labelLarge)
                Text(current.page.text.take(3_500))
            }
            if (current.page.truncated) Text("The page evidence is bounded; narrow your goal for more specific content.", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
    }
    confirmation?.let { proposal ->
        val call = requireNotNull(proposal.call)
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("Confirm proposed browser action") },
            text = { Text(actionDescription(call, view?.page) + "\n\nThis exact action may change website or account state. Page text and local model suggestions cannot approve it.") },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                session?.let { captured -> run(captured) { captured.execute(proposal, approved = true) } }
            }, enabled = !busy && session?.ownsCurrentPage() == true) { Text("Confirm exact action") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
    }
}

private fun actionDescription(call: OrezToolCall, page: BrowserDomEvidence?): String {
    val label = page?.elements?.firstOrNull { it.id == call.arguments["elementId"] }?.label?.ifBlank { "Unnamed element" }.orEmpty()
    return when (call.name) {
        "browser_navigate" -> "Open this exact address: ${call.arguments.getValue("value")}"
        "browser_click" -> "Click observed element: ${label.ifBlank { "Unavailable element" }}" +
            page?.elements?.firstOrNull { it.id == call.arguments["elementId"] }?.address?.takeIf { it.isNotBlank() }?.let { "\nObserved destination: $it" }.orEmpty()
        "browser_fill" -> "Fill ${label.ifBlank { "this observed field" }} with: ${call.arguments.getValue("text")}"
        "browser_scroll" -> "Scroll ${call.arguments.getValue("delta")} pixels in the current page."
        "browser_extract" -> "Read visible page text${call.arguments["query"]?.let { " matching: $it" }.orEmpty()}."
        else -> "Inspect visible controls${call.arguments["query"]?.let { " matching: $it" }.orEmpty()}."
    }
}
