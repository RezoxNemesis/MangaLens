package com.mangalens.ui.web

import com.mangalens.core.compute.NativeComputePrecondition
import com.mangalens.orez.OrezModelPin
import com.mangalens.orez.agent.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class BrowserGoalView(val page: BrowserDomEvidence, val proposal: BrowserGoalProposal?, val summary: String, val finished: Boolean)

/** An ephemeral USER goal. It never writes a durable task or rebinds a retired document. */
internal class BrowserGoalSession(private val tools: OrezBrowserTools, private val model: BrowserGoalModel,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val ownerDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate) {
    private data class Request(val goal: String, val owner: BrowserDomOwner, val pin: OrezModelPin, val epoch: Long, val deadline: Long)
    private val generation = AtomicLong(0)
    private val mutex = Mutex()
    @Volatile private var request: Request? = null
    private var pending: BrowserGoalProposal? = null
    private var pendingCall: OrezToolCall? = null
    private var turns = 0
    private val dispatched = mutableListOf<String>()
    private var attemptedEffects = 0
    fun retire() { generation.incrementAndGet(); request = null; pending = null; pendingCall = null }
    fun ownsCurrentPage(): Boolean = request?.let { generation.get() == it.epoch && tools.currentOwner() == it.owner && clock() < it.deadline } == true
    fun summary(): String = buildString {
        if (attemptedEffects == 0) append("No page changes attempted in this goal.")
        else append("${dispatched.size} browser action(s) have confirmed DISPATCHED receipts. Website and account completion remain unverified.")
        if (attemptedEffects > dispatched.size) append(" An attempted action has no dispatch receipt; inspect the page before retrying.")
    }

    suspend fun start(goal: String): BrowserGoalView = mutex.withLock {
        retire(); dispatched.clear(); attemptedEffects = 0; turns = 0
        val capturedGoal = goal.trim()
        require(capturedGoal.isNotBlank() && capturedGoal.length <= BrowserGoalPlanDecoder.MAX_GOAL && '\u0000' !in capturedGoal) { "Enter a goal using up to 1000 characters." }
        val owner = requireNotNull(tools.currentOwner()) { "Wait for a loaded current Web page." }
        val epoch = generation.get(); val deadline = clock() + 120_000L
        val pin = withTimeoutOrNull(25_000L) { model.capturePin() }
        currentCoroutineContext().ensureActive()
        require(epoch == generation.get() && tools.currentOwner() == owner) { "The browser document changed before planning. Start a fresh goal." }
        requireNotNull(pin) { "A verified local Orez model is unavailable. Open Download models to install or verify one; manual page tools remain available." }
        val captured = Request(capturedGoal, owner, pin, epoch, deadline)
        request = captured
        next(captured)
    }

    suspend fun execute(proposal: BrowserGoalProposal, approved: Boolean): BrowserGoalView = mutex.withLock {
        val captured = requireNotNull(request) { "This foreground goal was retired." }
        checkScope(captured)
        require(proposal === pending) { "This is not the current proposed step." }
        val call = requireNotNull(pendingCall).let { it.copy(arguments = it.arguments.toMap()) }
        require(proposal.call == call) { "The proposed action changed after it was presented. Start a fresh goal." }
        val grant = BrowserDomGrant.capture(captured.owner, call, OrezAgentContext(origin = OrezTrustOrigin.USER, explicitUserRequest = true), approved)
        pending = null; pendingCall = null // An accepted step is never automatically replayed after timeout or cancellation.
        if (call.risk != OrezToolRisk.READ_ONLY) attemptedEffects++
        when (val result = tools.execute(call, grant)) {
            is OrezToolResult.Completed -> if (result.outputs.containsKey("browserAction")) dispatched += call.name.removePrefix("browser_")
            is OrezToolResult.Failed -> error(result.reason)
            is OrezToolResult.Pending -> error(result.reason)
            is OrezToolResult.Cancelled -> throw CancellationException(result.reason)
        }
        if (!ownsCurrentPage()) {
            retire()
            error("${summary()} The source document changed; this plan is retired. Inspect the loaded page and start a fresh goal.")
        }
        next(captured) // Bounded actual post-action observation before another proposal or summary.
    }

    private fun checkScope(captured: Request) {
        check(request === captured && generation.get() == captured.epoch && tools.currentOwner() == captured.owner && clock() < captured.deadline) {
            "This page or foreground goal changed, or its two-minute budget expired. Start a fresh goal."
        }
    }
    private suspend fun read(captured: Request, name: String): BrowserDomEvidence {
        checkScope(captured)
        val call = OrezToolRegistry().call(name, mapOf("pageId" to captured.owner.pageId))
        val grant = BrowserDomGrant.capture(captured.owner, call, OrezAgentContext(origin = OrezTrustOrigin.USER, explicitUserRequest = true))
        val result = tools.execute(call, grant)
        checkScope(captured)
        val completed = result as? OrezToolResult.Completed ?: error((result as? OrezToolResult.Failed)?.reason ?: "Current page evidence is unavailable.")
        return BrowserDomCodec.decode(requireNotNull(completed.outputs["browserEvidence"])).also {
            require(it.pageId == captured.owner.pageId && it.origin == OrezTrustOrigin.WEB_CONTENT)
        }
    }
    private suspend fun next(captured: Request): BrowserGoalView = withTimeoutOrNull(40_000L) {
        checkScope(captured)
        val text = read(captured, "browser_extract")
        val observed = read(captured, "browser_observe")
        val page = observed.copy(text = text.text, truncated = text.truncated || observed.truncated)
        if (turns >= BrowserGoalPlanDecoder.MAX_TURNS) {
            pending = null; pendingCall = null
            return@withTimeoutOrNull BrowserGoalView(page, null, "${summary()} Three-step limit reached; showing fresh visible evidence.", true)
        }
        val input = BrowserGoalPlanDecoder.prompt(captured.goal, page, dispatched.toList(), turns + 1)
        val precondition = NativeComputePrecondition {
            withContext(ownerDispatcher) { currentCoroutineContext().ensureActive(); checkScope(captured) }
        }
        val raw = model.propose(input, captured.pin, precondition)
        currentCoroutineContext().ensureActive(); checkScope(captured)
        requireNotNull(raw) { "The pinned local model did not produce a completed browser plan. ${summary()} Narrow the goal or retry after local work finishes." }
        val proposal = BrowserGoalPlanDecoder.decode(raw, captured.goal, captured.owner, page)
        turns++; pending = proposal.takeIf { it.call != null }
        pendingCall = proposal.call?.let { it.copy(arguments = it.arguments.toMap()) }
        BrowserGoalView(page, proposal, "${summary()} Latest bounded evidence is from ${page.title.ifBlank { page.address }}.", proposal.call == null)
    } ?: error("Local browser planning exceeded its time budget. ${summary()} Inspect the page before retrying.")
}
