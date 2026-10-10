package com.mangalens.ui.web

import java.util.concurrent.atomic.AtomicInteger

internal enum class BrowserLifecyclePhase {
    TEST_BEGIN, ACTIVITY_LAUNCH_BEGIN, ACTIVITY_LAUNCHED, MOUNT_BEGIN, MOUNT_END,
    INITIAL_DOCUMENT_WAIT_BEGIN, INITIAL_DOCUMENT_WAIT_END, HISTORY_WAIT_BEGIN, HISTORY_WAIT_END,
    VERIFY_BEGIN, VERIFY_END, FIXTURE_FINALLY_BEGIN, CONTENT_CLEAR_REQUEST_BEGIN,
    CONTENT_CLEAR_MAIN_BEGIN, CONTENT_CLEAR_MAIN_END, CONTENT_CLEAR_REQUEST_END,
    SCENARIO_SCOPE_END, OUTER_FINALLY_BEGIN, OUTER_FINALLY_END,
    WORKSPACE_RESTORING_COMPOSED, WORKSPACE_BROWSER_COMPOSED,
    VIEW_FACTORY_BEGIN, VIEW_FACTORY_END, VIEW_ATTACHED, VIEW_DETACH_BEGIN, VIEW_DETACHED,
    VIEW_MEASURE_BEGIN, VIEW_MEASURE_END, VIEW_MEASURE_ABORT,
    VIEW_DRAW_BEGIN, VIEW_DRAW_END, VIEW_DRAW_ABORT, VIEW_RELEASE,
    DISPOSE_BEGIN, WEBVIEW_DESTROY_BEGIN, WEBVIEW_DESTROY_END, DISPOSE_END,
    TEST_FINISHED
}

/** Fixed scalars only. Content, source identities, exceptions, URLs and paths have no field. */
internal data class BrowserLifecycleObservation(
    val phase: BrowserLifecyclePhase,
    val viewOrdinal: Int = 0,
    val attached: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val widthSpecMode: Int? = null,
    val heightSpecMode: Int? = null,
    val revision: Long? = null
)

/** Inert unless the exact opt-in Android fixture RunListener installs it. */
internal object BrowserLifecycleDiagnostics {
    private data class Subscription(val observer: (BrowserLifecycleObservation) -> Unit,
        val ordinals: AtomicInteger = AtomicInteger())
    @Volatile private var listener: Subscription? = null
    val enabled: Boolean get() = listener != null

    @Synchronized fun install(observer: (BrowserLifecycleObservation) -> Unit): AutoCloseable {
        val installed = Subscription(observer)
        listener = installed
        return AutoCloseable { synchronized(this) { if (listener === installed) listener = null } }
    }

    fun nextViewOrdinal(): Int = listener?.ordinals?.updateAndGet { (it + 1).coerceAtMost(65535) } ?: 0

    fun observe(event: BrowserLifecycleObservation) {
        val installed = listener ?: return
        runCatching { installed.observer(event) }
    }

    fun mark(phase: BrowserLifecyclePhase, revision: Long? = null) {
        if (enabled) observe(BrowserLifecycleObservation(phase, revision = revision))
    }
}

internal data class BrowserLifecycleTraceRow(val sequence: Int, val uptimeMillis: Long,
    val mainThread: Boolean, val event: BrowserLifecycleObservation)
internal data class BrowserLifecycleTraceSnapshot(val rows: List<BrowserLifecycleTraceRow>, val dropped: Int)

/** Bounded memory only; the selected listener owns crash-safe logcat and final JSON. */
internal class BrowserLifecycleTraceBuffer(private val capacity: Int = 256) {
    init { require(capacity in 1..256) }
    private val rows = ArrayList<BrowserLifecycleTraceRow>(capacity)
    private var dropped = 0
    private var closed = false

    @Synchronized fun append(event: BrowserLifecycleObservation, uptimeMillis: Long,
        mainThread: Boolean): BrowserLifecycleTraceRow? {
        if (closed) return null
        if (rows.size >= capacity) { if (dropped < Int.MAX_VALUE) dropped++; return null }
        val row = BrowserLifecycleTraceRow(rows.size + 1, uptimeMillis.coerceAtLeast(0), mainThread, event)
        rows += row
        return row
    }

    @Synchronized fun finish(): BrowserLifecycleTraceSnapshot {
        closed = true
        return BrowserLifecycleTraceSnapshot(rows.toList(), dropped)
    }
}
