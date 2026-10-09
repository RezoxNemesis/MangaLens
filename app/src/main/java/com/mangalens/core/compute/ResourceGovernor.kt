package com.mangalens.core.compute

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Platform reports only. Null means unavailable; battery temperature is never a CPU thermal report. */
internal data class ResourceSignals(
    val memory: MemoryPressure? = null,
    val thermal: ThermalPressure? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val powerSave: Boolean? = null
)

internal enum class MemoryPressure { NORMAL, LOW, CRITICAL }
internal enum class ThermalPressure { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }
internal enum class ResourceWorkKind { LIVE, INTERACTIVE, BACKGROUND, CLEANUP }
internal enum class ResourcePressure { NORMAL, ELEVATED, CRITICAL }
internal data class ResourceDecision(val pressure: ResourcePressure, val delayMs: Long = 0, val reason: String? = null)
internal data class ResourceSnapshot(val pressure: ResourcePressure, val signals: ResourceSignals, val reason: String?)

/** Recoverable boundary stop, distinct from model/source failures and from explicit user pauses. */
internal class ResourcePausedException(message: String) : IllegalStateException(message)

/** Pure process-wide policy. It neither cancels an active native call nor changes a task's model or identity. */
internal class ResourceGovernor(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val recoveryMs: Long = 15_000,
    private val backgroundRestMs: Long = 2_000
) {
    private var signals = ResourceSignals()
    private var pressure = ResourcePressure.NORMAL
    private var recoveryStarted: Long? = null
    private var recoveryTarget: ResourcePressure? = null
    private var criticalReason: String? = null
    private var lastBackgroundEnd: Long? = null
    init { require(recoveryMs >= 0 && backgroundRestMs >= 0) }

    @Synchronized fun update(observed: ResourceSignals) {
        signals = ResourceSignals(observed.memory ?: signals.memory, observed.thermal ?: signals.thermal,
            observed.batteryPercent ?: signals.batteryPercent, observed.charging ?: signals.charging,
            observed.powerSave ?: signals.powerSave)
        settle(clockMs())
    }

    @Synchronized fun decision(kind: ResourceWorkKind, queuedAtMs: Long = clockMs()): ResourceDecision {
        val now = clockMs()
        settle(now)
        if (kind == ResourceWorkKind.CLEANUP) return ResourceDecision(ResourcePressure.NORMAL)
        if (pressure == ResourcePressure.CRITICAL) return ResourceDecision(pressure, reason = criticalReason)
        val rest = if (pressure == ResourcePressure.ELEVATED && kind == ResourceWorkKind.BACKGROUND) {
            val since = maxOf(queuedAtMs, lastBackgroundEnd ?: queuedAtMs)
            (backgroundRestMs - (now - since).coerceAtLeast(0)).coerceAtLeast(0)
        } else 0
        return ResourceDecision(pressure, rest)
    }

    private fun settle(now: Long) {
        val raw = when {
            signals.memory == MemoryPressure.CRITICAL ||
                (signals.thermal?.ordinal ?: -1) >= ThermalPressure.CRITICAL.ordinal -> ResourcePressure.CRITICAL
            signals.memory == MemoryPressure.LOW ||
                (signals.thermal?.ordinal ?: -1) >= ThermalPressure.MODERATE.ordinal ||
                signals.powerSave == true ||
                (signals.batteryPercent?.let { it in 0..15 } == true && signals.charging == false) -> ResourcePressure.ELEVATED
            else -> ResourcePressure.NORMAL
        }
        if (raw.ordinal >= pressure.ordinal) {
            pressure = raw
            recoveryStarted = null
            recoveryTarget = null
            if (raw == ResourcePressure.CRITICAL) criticalReason = if (signals.memory == MemoryPressure.CRITICAL)
                "Waiting for device memory; saved progress will resume automatically."
            else "Waiting for the device to cool; saved progress will resume automatically."
        } else {
            if (recoveryTarget != raw || recoveryStarted?.let { now < it } == true) {
                recoveryTarget = raw
                recoveryStarted = now
            }
            if (now - (recoveryStarted ?: now) >= recoveryMs) {
                pressure = raw
                recoveryStarted = null
                recoveryTarget = null
                criticalReason = null
            }
        }
    }

    @Synchronized fun completed(kind: ResourceWorkKind) {
        if (kind == ResourceWorkKind.BACKGROUND) lastBackgroundEnd = clockMs()
    }

    internal fun nowMs(): Long = clockMs()

    @Synchronized fun snapshot(): ResourceSnapshot {
        settle(clockMs())
        return ResourceSnapshot(pressure, signals, if (pressure == ResourcePressure.CRITICAL) criticalReason else null)
    }

    /** Final non-suspending boundary; admission already owns this work's duty-cycle wait. */
    fun requireNativeEntry(kind: ResourceWorkKind) {
        val entry = decision(kind)
        if (entry.pressure == ResourcePressure.CRITICAL)
            throw ResourcePausedException(entry.reason ?: "Waiting for device resources; saved progress will resume automatically.")
    }

    suspend fun awaitBoundary(kind: ResourceWorkKind, current: () -> Boolean): Boolean {
        val queuedAt = nowMs()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!current()) return false
            val decision = decision(kind, queuedAt)
            if (decision.pressure == ResourcePressure.CRITICAL && kind != ResourceWorkKind.CLEANUP)
                throw ResourcePausedException(decision.reason ?: "Waiting for device resources; saved progress will resume automatically.")
            if (decision.delayMs <= 0) return true
            delay(minOf(decision.delayMs, 100))
        }
    }
}

internal object ResourceGovernorRuntime { val shared = ResourceGovernor() }
