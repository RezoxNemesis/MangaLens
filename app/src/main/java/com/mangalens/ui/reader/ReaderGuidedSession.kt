package com.mangalens.ui.reader

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.mangalens.core.reader.ChapterPage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A scalar UI-owner key. It confers no native or personal-memory publication authority. */
internal data class ReaderGuidedOwner(val chapterId: String, val pageIndex: Int, val sourcePath: String,
    val contentRevision: String?, val presentationEpoch: Long?, val rightToLeft: Boolean)

internal class ReaderGuidedSession(val owner: ReaderGuidedOwner, private val index: MutableIntState,
    private val whole: MutableState<Boolean>, initiallyResumed: Boolean = true) {
    private var resumed = initiallyResumed
    private var lifecycleGeneration by mutableLongStateOf(0L)
    val lifecycleRevision get() = lifecycleGeneration
    fun resume() { if (!resumed) { resumed = true; ++lifecycleGeneration } }
    fun detectionTicket(): Long? = lifecycleGeneration.takeIf { resumed }
    private fun isCurrent(ticket: Long) = resumed && lifecycleGeneration == ticket
    var detection by mutableStateOf<ReaderPanelDetection?>(null); private set
    var busy by mutableStateOf(true); private set
    var failed by mutableStateOf(false); private set
    val panelIndex get() = ReaderGuidedViewPolicy.boundedIndex(index.intValue, detection?.panels?.size ?: 0)
    val panelCount get() = detection?.panels?.size ?: 0
    val wholePage get() = whole.value
    fun accept(ticket: Long, result: ReaderPanelDetection, startAtLast: Boolean): Boolean {
        if (!isCurrent(ticket)) return false
        check(result.sourceSha256.matches(Regex("[a-f0-9]{64}")) && result.width > 0 && result.height > 0 &&
            result.panels.size in 1..ReaderPanelGeometry.MAX_PANELS && result.panels.all(ReaderGuidedViewPolicy::valid))
        val expected = owner.contentRevision?.substringBefore(':')?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        check(expected == null || result.sourceSha256 == expected)
        detection = result; index.intValue = if (startAtLast) result.panels.lastIndex else ReaderGuidedViewPolicy.boundedIndex(index.intValue, result.panels.size)
        busy = false; failed = false
        return true
    }
    fun retire() { resumed = false; ++lifecycleGeneration; detection = null; busy = true; failed = false }
    fun unavailable(ticket: Long): Boolean { if (!isCurrent(ticket)) return false; detection = null; busy = false; failed = true; return true }
    fun showWhole() { whole.value = true }
    fun showPanel() { whole.value = false }
    fun advance(delta: Int): ReaderGuidedStep {
        val step = ReaderGuidedViewPolicy.advance(panelIndex, panelCount, delta)
        if (step.pageDelta == 0) { index.intValue = step.panelIndex; whole.value = false }
        return step
    }
    fun panelFor(page: ChapterPage, epoch: Long?): ReaderPanelRect {
        if (page.index != owner.pageIndex || page.localPath != owner.sourcePath || page.contentRevision != owner.contentRevision ||
            epoch != owner.presentationEpoch || whole.value) return ReaderGuidedViewPolicy.WHOLE_PAGE
        return detection?.panels?.getOrNull(panelIndex) ?: ReaderGuidedViewPolicy.WHOLE_PAGE
    }
}

/** Only the accepted active page is decoded. Owner changes cancel the held-FD operation and retire its session. */
@Composable
internal fun rememberReaderGuidedSession(owner: ReaderGuidedOwner?, startAtLast: Boolean,
    onReady: () -> Unit): ReaderGuidedSession? {
    if (owner == null) return null
    val selected = rememberSaveable(owner) { mutableIntStateOf(0) }
    val whole = rememberSaveable(owner) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val session = remember(owner, lifecycle) { ReaderGuidedSession(owner, selected, whole,
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val latestReady by rememberUpdatedState(onReady)
    var resumed by remember(owner, lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner, session, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { session.resume(); resumed = true }
            else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                resumed = false; session.retire()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); session.retire() }
    }
    LaunchedEffect(owner, resumed, session.lifecycleRevision) {
        if (!resumed) return@LaunchedEffect
        val ticket = session.detectionTicket() ?: return@LaunchedEffect
        try {
            val expected = owner.contentRevision?.substringBefore(':')?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
            val result = detectReaderPanels(owner.sourcePath, owner.rightToLeft, expected)
            currentCoroutineContext().ensureActive()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && session.accept(ticket, result, startAtLast)) latestReady()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session.unavailable(ticket) }
    }
    return session
}
