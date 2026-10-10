package com.mangalens.ui.reader

internal enum class ReaderHudFixture { MODE, RESTORATION, GEOMETRY, DOCUMENT, SOURCE_REPAIR, RASTER_REPAIR }
internal enum class ReaderHudMode { VERTICAL, LTR, RTL, SINGLE, HORIZONTAL, SPREAD, GUIDED, UNKNOWN;
    companion object {
        fun from(mode: String) = when (mode) { "vertical" -> VERTICAL; "ltr" -> LTR; "rtl" -> RTL; "single" -> SINGLE; "horizontal" -> HORIZONTAL; "spread" -> SPREAD
            "guided" -> GUIDED; else -> UNKNOWN }
    }
}
internal enum class ReaderHudPhase { TIMER_ARM, TIMER_FIRE, TIMER_CANCEL, TIMER_FINISH, WRITE, COMMITTED, ANIMATION, DRAW, ANIMATION_DISPOSED }
internal enum class ReaderHudWriter { NONE, PARENT_TAP, VERTICAL_TAP, PAGER_TAP, TOOLS_TOGGLE, TRANSFORM, TIMER }
internal enum class ReaderHudComponent { NONE, HEADER, DOCK }
internal enum class ReaderHudAnimation { NONE, PRE_ENTER, VISIBLE, POST_EXIT }

/** Typed scalar observations only; no source identity, content, path or URL is retained. */
internal data class ReaderHudSnapshot(
    val mode: ReaderHudMode,
    val logicalPage: Int,
    val physicalOffset: Int,
    val request: Long,
    val restoring: Boolean,
    val geometryReady: Boolean,
    val toolsOpen: Boolean,
    val hudVisible: Boolean,
    val translationActive: Boolean,
    val scale: Float,
    val panX: Float,
    val panY: Float,
    val readerInstance: Long = 0,
    val physicalPage: Int = logicalPage
)

internal data class ReaderHudObservation(
    val phase: ReaderHudPhase,
    val snapshot: ReaderHudSnapshot,
    val writer: ReaderHudWriter = ReaderHudWriter.NONE,
    val previousHudVisible: Boolean? = null,
    val previousToolsOpen: Boolean? = null,
    val timerSequence: Long = 0,
    val zoomDelta: Float? = null,
    val panDeltaX: Float? = null,
    val panDeltaY: Float? = null,
    val component: ReaderHudComponent = ReaderHudComponent.NONE,
    val animationCurrent: ReaderHudAnimation = ReaderHudAnimation.NONE,
    val animationTarget: ReaderHudAnimation = ReaderHudAnimation.NONE,
    val animationRunning: Boolean? = null
)

/** Independent of navigation diagnostics; absent unless the explicit QA listener is selected. */
internal object ReaderHudDiagnostics {
    private data class Subscription(val fixtures: Set<ReaderHudFixture>,
        val observer: (ReaderHudFixture, ReaderHudObservation) -> Unit,
        val instances: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong())
    @Volatile private var listener: Subscription? = null
    val enabled: Boolean get() = listener != null

    @Synchronized fun install(fixtures: Set<ReaderHudFixture>,
        observer: (ReaderHudFixture, ReaderHudObservation) -> Unit): AutoCloseable {
        val installed = Subscription(fixtures.toSet(), observer)
        listener = installed
        return AutoCloseable { synchronized(this) { if (listener === installed) listener = null } }
    }

    fun readerInstance(chapterId: String, title: String): Long {
        val installed = listener ?: return 0
        val selected = fixture(chapterId, title) ?: return 0
        if (selected !in installed.fixtures) return 0
        return installed.instances.updateAndGet { (it + 1).coerceAtMost(65535) }
    }

    fun observe(chapterId: String, title: String, event: ReaderHudObservation) {
        val installed = listener ?: return
        val fixture = fixture(chapterId, title) ?: return
        if (fixture !in installed.fixtures) return
        // Diagnostic failures never replace the actual reader gesture or timer outcome.
        runCatching { installed.observer(fixture, event) }
    }

    internal fun fixture(chapterId: String, title: String): ReaderHudFixture? = when {
        chapterId == "mode-qa" && title == "Reader mode QA" -> ReaderHudFixture.MODE
        HASH.matches(chapterId) && title == "Reader restoration QA" -> ReaderHudFixture.RESTORATION
        HASH.matches(chapterId) && title == "Reader geometry QA" -> ReaderHudFixture.GEOMETRY
        generated(chapterId, "document-", title, "Imported document ") -> ReaderHudFixture.DOCUMENT
        chapterId.startsWith("source-repair-") && UUID.matches(chapterId.removePrefix("source-repair-")) &&
            title == "Source repair" -> ReaderHudFixture.SOURCE_REPAIR
        generated(chapterId, "raster-", title, "Raster repair ") -> ReaderHudFixture.RASTER_REPAIR
        else -> null
    }

    private fun generated(id: String, prefix: String, title: String, titlePrefix: String): Boolean {
        if (!id.startsWith(prefix)) return false
        val suffix = id.removePrefix(prefix)
        return UUID.matches(suffix) && title == titlePrefix + suffix
    }
    private val HASH = Regex("[0-9a-f]{32}")
    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}

internal data class ReaderHudTraceRow(val sequence: Int, val uptimeMillis: Long,
    val fixture: ReaderHudFixture, val event: ReaderHudObservation)
internal data class ReaderHudTraceSnapshot(val rows: List<ReaderHudTraceRow>, val dropped: Int)

/** No IO or clock reads here. Actual writes stay visible, including duplicate callbacks. */
internal class ReaderHudTraceBuffer(private val capacity: Int = 256) {
    init { require(capacity in 1..256) }
    private val rows = ArrayList<ReaderHudTraceRow>(capacity)
    private var committed: Pair<ReaderHudFixture, ReaderHudSnapshot>? = null
    private var dropped = 0
    private var closed = false

    @Synchronized fun append(fixture: ReaderHudFixture, event: ReaderHudObservation, uptimeMillis: Long) {
        if (closed) return
        if (event.phase == ReaderHudPhase.COMMITTED) {
            val value = fixture to event.snapshot
            if (committed == value) return
            committed = value
        }
        if (rows.size >= capacity) { if (dropped < Int.MAX_VALUE) dropped++; return }
        rows += ReaderHudTraceRow(rows.size + 1, uptimeMillis.coerceAtLeast(0), fixture, event)
    }

    @Synchronized fun finish(): ReaderHudTraceSnapshot {
        closed = true
        return ReaderHudTraceSnapshot(rows.toList(), dropped)
    }
}
