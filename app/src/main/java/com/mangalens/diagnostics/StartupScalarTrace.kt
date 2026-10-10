package com.mangalens.diagnostics

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.mangalens.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only, bounded scalar capture. No log, file, preference, model or network access
 * until a QA launch opts in. Early app/provider-bracket samples remain in memory only.
 * Draw/layout/commit events are not the ActivityManager Displayed oracle.
 */
internal object StartupScalarTrace {
    const val OPT_IN_EXTRA = "mangalens.qa.startup_trace"
    private val ledger = StartupTraceLedger()
    private val enabled = AtomicBoolean()
    val isEnabled: Boolean get() = BuildConfig.DEBUG && enabled.get()

    fun enableForQa(optIn: Boolean) {
        if (BuildConfig.DEBUG && optIn) {
            enabled.set(true)
            emitAvailable()
        }
    }

    fun begin(stage: StartupStage): StartupTraceLedger.Span? {
        if (!BuildConfig.DEBUG || !stage.span || ledger.isClaimed(stage)) return null
        return ledger.begin(stage, sample()).also { emitAvailable() }
    }
    fun end(span: StartupTraceLedger.Span?, completed: Boolean = true) {
        if (span != null) {
            ledger.end(span, sample(), completed)
            emitAvailable()
        }
    }
    fun <T> measure(stage: StartupStage, operation: () -> T): T {
        val span = begin(stage)
        var completed = false
        return try { operation().also { completed = true } }
        finally { end(span, completed) }
    }
    fun markOnce(stage: StartupStage) {
        if (BuildConfig.DEBUG && !stage.span && !ledger.isClaimed(stage)) {
            ledger.mark(stage, sample())
            emitAvailable()
        }
    }

    private fun sample() = StartupClockSample(SystemClock.elapsedRealtimeNanos(), Debug.threadCpuTimeNanos(), Process.myTid())
    private fun emitAvailable() {
        if (!isEnabled) return
        val pid = Process.myPid()
        ledger.drainUnemitted().forEach { record ->
            val point = record.sample
            val span = record.started?.let { start ->
                " elapsed_start_ns=${start.elapsedNs} elapsed_duration_ns=${record.elapsedDurationNs} " +
                    "cpu_duration_ns=${record.cpuDurationNs} same_thread=${start.threadId == point.threadId} completed=${record.completed}"
            }.orEmpty()
            Log.i("MangaLensStartup", "source=${BuildConfig.SOURCE_SHA} pid=$pid tid=${point.threadId} " +
                "main_thread=${point.threadId == pid} stage=${record.stage.name} phase=${record.phase.name} " +
                "elapsed_ns=${point.elapsedNs} thread_cpu_ns=${point.threadCpuNs}$span")
        }
    }
}
