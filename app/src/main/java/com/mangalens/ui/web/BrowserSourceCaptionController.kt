package com.mangalens.ui.web

import android.content.Context
import android.webkit.WebView
import com.mangalens.ui.video.BrowserCaptionAuthorityKey
import com.mangalens.ui.video.BrowserCaptionAuthorityRetired
import com.mangalens.ui.video.BrowserCaptionOperation
import com.mangalens.ui.video.BrowserCaptionTaskBinding
import com.mangalens.ui.video.SubtitleGenerationConfig
import com.mangalens.ui.video.SubtitleGenerationJobs
import com.mangalens.ui.video.SubtitleGenerationStatus
import com.mangalens.ui.video.SubtitleGenerationStore
import com.mangalens.ui.video.SubtitleGenerationTask
import com.mangalens.ui.video.SubtitleInputs
import com.mangalens.ui.video.SubtitleMediaSource
import com.mangalens.ui.video.SubtitleSourceIdentity
import com.mangalens.ui.video.SubtitleTargetOptions
import com.mangalens.ui.video.browserCaptionBinding
import com.mangalens.ui.video.hasVerifiedProviderCaptions
import com.mangalens.ui.video.normalized
import com.mangalens.ui.video.sameSubtitleGeneration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A reference read from the actual current browser owner on Main, never reconstructed from disk. */
internal data class BrowserCaptionLiveHost(val owner: BrowserCaptionPageOwner, val view: WebView)

internal data class BrowserSourceCaptionState(
    val requested: Boolean = false,
    val scheduling: Boolean = false,
    val status: SubtitleGenerationStatus? = null,
    val message: String = "Source captions are off.",
    val cueText: String? = null,
    val taskId: String? = null,
    val generation: String? = null,
    val translatedCues: Int = 0,
    val sourceCues: Int = 0,
    val targetLanguage: String? = null
) {
    val running: Boolean get() = scheduling || status in setOf(SubtitleGenerationStatus.QUEUED, SubtitleGenerationStatus.RUNNING)
}

/**
 * Live DOM scope -> common durable subtitle jobs -> real clock -> visible verified provider cues.
 * All public methods and host reads execute on Main. Commands survive disposal just long enough
 * to retire an accepted NonCancellable start; no stale result can acquire a replacement authority.
 */
internal class BrowserSourceCaptionController(
    context: Context,
    private val workspace: StateFlow<BrowserWorkspaceSnapshot?>,
    private val currentHost: () -> BrowserCaptionLiveHost?
) {
    private class Frame(
        val serial: Long,
        val scope: BrowserSourceCaptionScope,
        val store: SubtitleGenerationStore,
        val source: SubtitleSourceIdentity,
        val config: SubtitleGenerationConfig,
        val operation: BrowserCaptionOperation,
        var task: SubtitleGenerationTask? = null,
        var verified: SubtitleGenerationTask? = null,
        var clock: BrowserCaptionClockSample? = null
    )
    private data class Paused(val scope: BrowserSourceCaptionScope, val task: SubtitleGenerationTask)
    private val app = context.applicationContext
    private val commands = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
        CoroutineExceptionHandler { _, _ ->
            // Feature-owned IO/verification failures retire this source; they must never
            // escape as an unhandled Main exception or fabricate a successful control receipt.
            if (!disposed) retire("Source captions could not continue. Start again for the current video.")
        })
    private val lane = Mutex()
    private val clockLane = Mutex()
    private val mutable = MutableStateFlow(BrowserSourceCaptionState())
    val state: StateFlow<BrowserSourceCaptionState> = mutable
    private var serial = 0L
    private var disposed = false
    private var active: Frame? = null
    private val liveLease = AtomicReference<Frame?>(null)
    private val workspaceWatcher = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var paused: Paused? = null
    private var clockJob: Job? = null
    private var observerJob: Job? = null

    init {
        // Session publication may run on IO. Revoke only the short memory authority here;
        // WebView reads, UI changes and exact-generation durable cancellation stay on Main/IO.
        workspaceWatcher.launch {
            workspace.collect { snapshot ->
                val frame = liveLease.get() ?: return@collect
                val page = frame.scope.page
                if (snapshot == null || snapshot.activeTabId != page.tabId || snapshot.activeTab.url != page.pageUrl) {
                    if (!liveLease.compareAndSet(frame, null)) return@collect
                    val binding = frame.store.browserCaptionAuthority.revoke(frame.operation)
                    commands.launch {
                        if (active === frame) retire("The active browser tab or page changed.")
                        if (binding != null) lane.withLock { retireBinding(frame, binding) }
                    }
                }
            }
        }
    }

    fun start(options: SubtitleTargetOptions) {
        if (disposed) return
        retire("Checking original source captions…")
        val request = serial
        val capturedOptions = options.capture()
        mutable.value = mutable.value.copy(requested = true, scheduling = true, targetLanguage = capturedOptions.targetLanguage)
        commands.launch {
            lane.withLock {
                if (!ownsRequest(request)) return@withLock
                try {
                    val host = currentHost() ?: throw CaptionUnavailable("Wait for the current page to finish loading.")
                    val raw = evaluate(host, BrowserCaptionScript.capture())
                    if (!ownsRequest(request) || currentHost() != host) return@withLock
                    val sourceScope = BrowserSourceCaptionParser.capture(raw, host.owner)
                        ?: throw CaptionUnavailable("Original source captions are unavailable or their video/audio source is unclear.")
                    val media = SubtitleMediaSource(sourceScope.page.pageUrl, headers = emptyMap(),
                        cacheKey = "browser-source-caption:" + sourceScope.sourceId, label = "Browser source captions",
                        providerCaptions = sourceScope.inventory, sourceResolutionId = sourceScope.sourceId,
                        captionDocumentOnly = true)
                    val source = SubtitleInputs.capture(app, media)
                    val language = requireNotNull(sourceScope.captured.audioLanguage).substringBefore('-')
                    val config = SubtitleGenerationConfig(sourceLanguage = language, modelSha256 = null)
                        .withTarget(capturedOptions).normalized()
                    val store = withContext(Dispatchers.IO) { SubtitleGenerationStore.shared(app) }
                    val clock = readClock(sourceScope)
                    if (!ownsRequest(request) || clock == null || !sourceScope.accepts(currentHost()?.owner ?: return@withLock, clock))
                        return@withLock
                    val operation = store.browserCaptionAuthority.begin(BrowserCaptionAuthorityKey(
                        sourceScope.sourceId, source.fingerprint, config.fingerprint()))
                    val frame = Frame(request, sourceScope, store, source, config, operation, clock = clock)
                    active = frame
                    liveLease.set(frame)
                    if (!owns(frame)) {
                        retire("The browser page changed before source captions could start.")
                        return@withLock
                    }
                    pollClock(frame)
                    val task = SubtitleGenerationJobs.startBrowser(app, source, config, operation)
                    frame.task = task
                    if (!owns(frame)) {
                        retireLateTask(frame, task)
                        return@withLock
                    }
                    observe(frame)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: BrowserCaptionAuthorityRetired) {
                    if (ownsRequest(request)) retire("The browser caption source changed. Start again for the current video.")
                } catch (failure: Exception) {
                    if (ownsRequest(request)) retire(failure.message?.take(300) ?: "Source captions could not start.")
                } finally {
                    if (ownsRequest(request)) mutable.value = mutable.value.copy(scheduling = false)
                }
            }
        }
    }

    /** Revocation is memory-only and never waits for a Store write, export or WorkManager command. */
    fun retire(message: String = "Source captions stopped for the previous video.") {
        serial++
        val old = active
        val oldPaused = paused
        active = null
        liveLease.compareAndSet(old, null)
        paused = null
        clockJob?.cancel(); clockJob = null
        observerJob?.cancel(); observerJob = null
        val binding = old?.store?.browserCaptionAuthority?.revoke(old.operation)
            ?: old?.task?.let(::browserCaptionBinding)
        mutable.value = BrowserSourceCaptionState(requested = mutable.value.requested,
            message = if (mutable.value.requested) message else "Source captions are off.")
        if (old != null && binding != null) commands.launch { lane.withLock { retireBinding(old, binding) } }
        if (oldPaused != null) commands.launch { lane.withLock {
            SubtitleGenerationJobs.cancel(app, oldPaused.task.id, oldPaused.task.generation)
        } }
    }

    fun pause() {
        val frame = active ?: return
        val captured = frame.task ?: return
        if (captured.status !in setOf(SubtitleGenerationStatus.QUEUED, SubtitleGenerationStatus.RUNNING)) return
        // The IO command is the durable pause boundary. Until acknowledged, source retirement
        // still revokes the live operation immediately and supersedes this pending command.
        mutable.value = mutable.value.copy(scheduling = true, message = "Pausing source captions…", cueText = null)
        frame.verified = null
        commands.launch { lane.withLock {
            val saved = SubtitleGenerationJobs.pause(app, captured.id, captured.generation)
            if (!owns(frame)) return@withLock
            if (saved?.status == SubtitleGenerationStatus.PAUSED) {
                serial++
                active = null
                liveLease.compareAndSet(frame, null)
                clockJob?.cancel(); clockJob = null
                observerJob?.cancel(); observerJob = null
                frame.store.browserCaptionAuthority.revoke(frame.operation)
                paused = Paused(frame.scope, saved)
                mutable.value = BrowserSourceCaptionState(requested = true, status = SubtitleGenerationStatus.PAUSED,
                    message = "Source captions paused.", taskId = saved.id, generation = saved.generation,
                    translatedCues = saved.translatedCueCount, sourceCues = saved.sourceCueCount,
                    targetLanguage = saved.config.targetLanguage)
            } else {
                mutable.value = mutable.value.copy(scheduling = false,
                    message = "The source-caption request finished or changed before it could pause.")
            }
        } }
    }

    /** Resume preserves the paused task's complete captured config; it does not adopt changed UI options. */
    fun resume() {
        if (disposed) return
        val old = active
        val remembered = paused ?: old?.let { frame -> frame.task?.takeIf {
            it.status in setOf(SubtitleGenerationStatus.PARTIAL, SubtitleGenerationStatus.FAILED)
        }?.let { Paused(frame.scope, it) } } ?: return
        if (old != null) {
            old.store.browserCaptionAuthority.revoke(old.operation)
            liveLease.compareAndSet(old, null)
            active = null
            clockJob?.cancel(); clockJob = null
            observerJob?.cancel(); observerJob = null
        }
        paused = remembered
        val request = ++serial
        mutable.value = mutable.value.copy(scheduling = true, cueText = null, message = "Checking the paused source…")
        commands.launch { lane.withLock {
            if (!ownsRequest(request) || paused !== remembered) return@withLock
            try {
                val clock = readClock(remembered.scope)
                val page = currentHost()?.owner
                if (!ownsRequest(request) || clock == null || page == null || !remembered.scope.accepts(page, clock)) {
                    if (ownsRequest(request)) retire("The paused video source changed. Start source captions again.")
                    return@withLock
                }
                val store = withContext(Dispatchers.IO) { SubtitleGenerationStore.shared(app) }
                val captured = remembered.task
                val operation = store.browserCaptionAuthority.begin(browserCaptionBinding(captured).key)
                val frame = Frame(request, remembered.scope, store, captured.source, captured.config, operation, clock = clock)
                active = frame; paused = null
                liveLease.set(frame)
                if (!owns(frame)) { retire("The browser page changed before source captions could resume."); return@withLock }
                pollClock(frame)
                val task = SubtitleGenerationJobs.resumeBrowser(app, captured.id, captured.generation, operation)
                    ?: throw CaptionUnavailable("The paused subtitle request was replaced. Start again for this video.")
                frame.task = task
                if (!owns(frame)) { retireLateTask(frame, task); return@withLock }
                observe(frame)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (ownsRequest(request)) retire(failure.message?.take(300) ?: "Source captions could not resume.") }
            finally { if (ownsRequest(request)) mutable.value = mutable.value.copy(scheduling = false) }
        } }
    }

    fun close() {
        if (disposed) return
        retire()
        disposed = true
        workspaceWatcher.cancel()
        // Already accepted starts are NonCancellable. Wait for their exact-generation cleanup
        // before cancelling the private scope; Main performs no durable IO in this method.
        commands.launch { lane.withLock { }; commands.cancel() }
    }

    private fun observe(frame: Frame) {
        observerJob?.cancel()
        observerJob = commands.launch {
            frame.store.states.collectLatest { tasks ->
                if (!owns(frame)) {
                    if (active === frame) retire("The active browser page changed.")
                    return@collectLatest
                }
                val captured = frame.task ?: return@collectLatest
                val task = tasks.firstOrNull { sameSubtitleGeneration(captured, it) } ?: run {
                    retire("The source-caption request was replaced.")
                    return@collectLatest
                }
                if (!matches(frame, task)) { retire("The source-caption request was replaced."); return@collectLatest }
                frame.task = task
                frame.verified = null
                val message = when (task.status) {
                    SubtitleGenerationStatus.QUEUED -> task.error ?: "Source captions queued."
                    SubtitleGenerationStatus.RUNNING -> "Source captions • ${task.translatedCueCount}/${task.sourceCueCount} translated"
                    SubtitleGenerationStatus.COMPLETED -> "Verifying source-caption exports…"
                    SubtitleGenerationStatus.PAUSED -> "Source captions paused."
                    SubtitleGenerationStatus.PARTIAL, SubtitleGenerationStatus.FAILED -> task.error ?: "Some source captions still need translation."
                    SubtitleGenerationStatus.CANCELLED -> "Source captions cancelled."
                }
                mutable.value = BrowserSourceCaptionState(requested = true, status = task.status, message = message,
                    taskId = task.id, generation = task.generation, translatedCues = task.translatedCueCount,
                    sourceCues = task.sourceCueCount, targetLanguage = task.config.targetLanguage)
                if (task.status == SubtitleGenerationStatus.COMPLETED && hasVerifiedProviderCaptions(task)) {
                    val exported = withContext(Dispatchers.IO) { frame.store.exportVerified(task.id, task.generation) }
                    if (!owns(frame) || exported == null || !sameSubtitleGeneration(task, exported) ||
                        frame.store.states.value.none { it == exported } || !matches(frame, exported)) return@collectLatest
                    val clock = readClock(frame.scope)
                    val page = currentHost()?.owner
                    if (!owns(frame) || clock == null || page == null || !frame.scope.accepts(page, clock)) {
                        if (owns(frame)) retire("The captioned browser video changed.")
                        return@collectLatest
                    }
                    if (!browserCaptionPublicationCurrent(frame.store.browserCaptionAuthority,
                            exported, frame.store.states.value) || !matches(frame, exported)) {
                        retire("The source-caption request changed before captions could attach.")
                        return@collectLatest
                    }
                    frame.verified = exported; frame.clock = clock
                    mutable.value = mutable.value.copy(message = "Source captions ready • ${exported.config.targetLanguage.uppercase()}",
                        cueText = browserCaptionTextAt(exported.cues, clock))
                }
            }
        }
    }

    private fun pollClock(frame: Frame) {
        clockJob?.cancel()
        clockJob = commands.launch {
            while (ownsRequest(frame.serial) && active === frame) {
                if (!owns(frame)) { retire("The active browser page changed."); break }
                val clock = readClock(frame.scope)
                val page = currentHost()?.owner
                if (!owns(frame)) {
                    if (active === frame) retire("The active browser page changed.")
                    break
                }
                if (clock == null || page == null || !frame.scope.accepts(page, clock)) {
                    retire("The browser video or its audio/caption source changed. Start source captions again.")
                    break
                }
                frame.clock = clock
                frame.task?.let { task ->
                    if (!matches(frame, task)) {
                        retire("The source-caption request was replaced.")
                        return@launch
                    }
                }
                val task = frame.verified
                mutable.value = mutable.value.copy(cueText = task?.takeIf { matches(frame, it) &&
                    browserCaptionPublicationCurrent(frame.store.browserCaptionAuthority, it, frame.store.states.value) }
                    ?.let { browserCaptionTextAt(it.cues, clock) })
                delay(150)
            }
        }
    }

    private fun matches(frame: Frame, task: SubtitleGenerationTask): Boolean =
        task.source == frame.source && task.config == frame.config && task.source.source.captionDocumentOnly &&
            task.ownerRequestId == null && task.config.modelSha256 == null &&
            frame.store.browserCaptionAuthority.permits(browserCaptionBinding(task))

    private fun ownsRequest(request: Long): Boolean = !disposed && serial == request
    private fun owns(frame: Frame): Boolean = ownsRequest(frame.serial) && active === frame &&
        currentHost()?.owner == frame.scope.page

    private suspend fun retireLateTask(frame: Frame, task: SubtitleGenerationTask) {
        frame.store.browserCaptionAuthority.revoke(frame.operation)
        SubtitleGenerationJobs.cancel(app, task.id, task.generation)
    }

    private suspend fun retireBinding(frame: Frame, binding: BrowserCaptionTaskBinding) {
        val saved = frame.store.states.value.firstOrNull { it.id == binding.taskId && it.generation == binding.generation }
        if (saved?.status != SubtitleGenerationStatus.COMPLETED)
            SubtitleGenerationJobs.cancel(app, binding.taskId, binding.generation)
    }

    private suspend fun readClock(scope: BrowserSourceCaptionScope): BrowserCaptionClockSample? = clockLane.withLock {
        // Completion verification and the presentation poll share one actual DOM read lane;
        // an older asynchronous sample cannot overwrite a later seek/pause observation.
        val host = currentHost()?.takeIf { it.owner == scope.page } ?: return@withLock null
        val captured = scope.captured
        BrowserSourceCaptionParser.clock(evaluate(host, BrowserCaptionScript.clock(captured.documentNonce,
            captured.elementId, captured.sourceVersion, requireNotNull(captured.audioTrackKey),
            requireNotNull(captured.audioLanguage), captured.pageUrl)))
    }

    private suspend fun evaluate(host: BrowserCaptionLiveHost, script: String): String? = withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(1_500) {
            suspendCancellableCoroutine { continuation ->
                if (currentHost() != host) { continuation.resume(null); return@suspendCancellableCoroutine }
                try {
                    host.view.evaluateJavascript(script) { raw ->
                        if (continuation.isActive) continuation.resume(raw.takeIf { currentHost() == host })
                    }
                } catch (_: RuntimeException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    private class CaptionUnavailable(message: String) : IllegalStateException(message)
}
