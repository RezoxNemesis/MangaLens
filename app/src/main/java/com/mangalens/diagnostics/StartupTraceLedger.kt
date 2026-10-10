package com.mangalens.diagnostics

import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReferenceArray

/** Fixed names only: diagnostics must never accept chapter, URL, preference or error text. */
internal enum class StartupStage(val span: Boolean) {
    APPLICATION_ATTACHED(false), APPLICATION_CREATE(true), RESOURCE_MONITOR_INSTALL(true),
    RECOVERY_ENQUEUE(true), ACTIVITY_CREATE(true), ACTIVITY_WINDOW_SETUP(true),
    CONTENT_INSTALLED(false), VM_LOOKUP(true), VM_PREFERENCES_OPEN(true), VM_OCR_PREFERENCES_OPEN(true),
    VM_LIBRARY_CREATE(true), VM_REPOSITORY_CREATE(true), VM_STATIC_ACQUIRER_CREATE(true),
    VM_MEDIA_RESOLVER_CREATE(true), VM_PREFERENCES_READ(true), VM_OCR_CONFIGURATION_READ(true),
    CONTENT_COMPOSITION_COMMITTED(false), WINDOW_FIRST_NONZERO_LAYOUT(false), WINDOW_DRAW_ENTERED(false),
    LIBRARY_IO_STARTED(false), LIBRARY_IO_FINISHED(false),
    TRANSLATION_JOURNAL_IO_STARTED(false), TRANSLATION_JOURNAL_IO_FINISHED(false)
}

internal data class StartupClockSample(val elapsedNs: Long, val threadCpuNs: Long, val threadId: Int)
internal enum class StartupTracePhase { START, END, EVENT }
internal data class StartupScalarRecord(val stage: StartupStage, val phase: StartupTracePhase,
    val sample: StartupClockSample, val started: StartupClockSample? = null, val completed: Boolean? = null) {
    val elapsedDurationNs: Long? get() = started?.let { (sample.elapsedNs - it.elapsedNs).takeIf { duration -> duration >= 0 } }
    // CPU subtraction across a suspended/thread-switched operation would be false evidence.
    val cpuDurationNs: Long? get() = started?.takeIf { it.threadId == sample.threadId }
        ?.let { (sample.threadCpuNs - it.threadCpuNs).takeIf { duration -> duration >= 0 } }
}

/** At most two records per fixed stage, claimed/emitted once even under concurrent IO completion. */
internal class StartupTraceLedger {
    internal class Span internal constructor(internal val owner: StartupTraceLedger,
        internal val stage: StartupStage, internal val started: StartupClockSample)
    private val claimed = AtomicIntegerArray(StartupStage.entries.size)
    private val records = AtomicReferenceArray<StartupScalarRecord?>(StartupStage.entries.size * 2)
    private val emitted = AtomicIntegerArray(StartupStage.entries.size * 2)

    fun isClaimed(stage: StartupStage): Boolean = claimed.get(stage.ordinal) != 0

    fun begin(stage: StartupStage, sample: StartupClockSample): Span? {
        if (!stage.span || !claimed.compareAndSet(stage.ordinal, 0, 1)) return null
        records.set(stage.ordinal * 2, StartupScalarRecord(stage, StartupTracePhase.START, sample))
        return Span(this, stage, sample)
    }
    fun end(span: Span, sample: StartupClockSample, completed: Boolean): Boolean =
        span.owner === this && records.compareAndSet(span.stage.ordinal * 2 + 1, null,
            StartupScalarRecord(span.stage, StartupTracePhase.END, sample, span.started, completed))

    fun mark(stage: StartupStage, sample: StartupClockSample): Boolean {
        if (stage.span || !claimed.compareAndSet(stage.ordinal, 0, 1)) return false
        records.set(stage.ordinal * 2, StartupScalarRecord(stage, StartupTracePhase.EVENT, sample))
        return true
    }

    fun drainUnemitted(): List<StartupScalarRecord> = buildList {
        for (index in 0 until records.length()) {
            val record = records.get(index) ?: continue
            if (emitted.compareAndSet(index, 0, 1)) add(record)
        }
    }
}
