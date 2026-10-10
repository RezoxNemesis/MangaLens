package com.mangalens.core.compute

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

/** Scheduling only: each feature still owns its source/model/generation preconditions. */
internal class NativeComputeAdmission(private val pollMs: Long = 25, private val maxWaiting: Int = 64,
    private val governor: ResourceGovernor? = null, private val foregroundFairnessMs: Long = 10_000) {
    enum class Priority { LIVE, INTERACTIVE, BACKGROUND }
    private class Ticket(val priority: Priority, val kind: ResourceWorkKind, val queuedAt: Long) {
        // Read and set only under guard; later grants cannot erase observed contention.
        var observedContention = false
    }
    private val guard = Any()
    private val waiting = ArrayList<Ticket>()
    private var pendingCleanupRegistrations = 0
    private var active: Lease? = null
    init {
        require(pollMs > 0 && maxWaiting in 1..64) { "Native compute permits at most 64 waiting requests." }
        require(foregroundFairnessMs >= 0)
    }

    inner class Lease internal constructor(val waited: Boolean, private val kind: ResourceWorkKind) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) synchronized(guard) {
                if (active === this) { governor?.completed(kind); active = null }
            }
        }
    }

    suspend fun acquire(priority: Priority, kind: ResourceWorkKind = priority.workKind(), current: () -> Boolean): Lease? {
        val caller = currentCoroutineContext()
        val ticket = Ticket(priority, kind, governor?.nowMs() ?: System.nanoTime() / 1_000_000)
        var granted: Lease? = null
        var waited = false
        var reservedCleanupCapacity = false
        try {
            while (true) {
                val registered = synchronized(guard) {
                    if (waiting.size < maxWaiting && (pendingCleanupRegistrations == 0 || reservedCleanupCapacity)) {
                        if (reservedCleanupCapacity) {
                            pendingCleanupRegistrations--
                            reservedCleanupCapacity = false
                        }
                        // Preserve contention observed before current() can block or release
                        // another owner; a later first grant must not erase that wait boundary.
                        ticket.observedContention = active != null || waiting.isNotEmpty()
                        waiting += ticket
                        true
                    } else {
                        check(kind == ResourceWorkKind.CLEANUP) {
                            "Native compute queue is full. Retry when local work finishes."
                        }
                        // An accepted close still owns its native handle. Retain that caller
                        // without registering a sixty-fifth ticket or letting new work steal its slot.
                        if (!reservedCleanupCapacity) {
                            pendingCleanupRegistrations++
                            reservedCleanupCapacity = true
                        }
                        false
                    }
                }
                if (registered) break
                caller.ensureActive()
                if (!current()) return null
                waited = true
                delay(pollMs)
            }
            while (true) {
                caller.ensureActive()
                if (!current()) return null
                val resources = governor?.decision(kind, ticket.queuedAt)
                if (resources?.pressure == ResourcePressure.CRITICAL)
                    throw ResourcePausedException(resources.reason ?: "Waiting for device resources; saved progress will resume automatically.")
                granted = synchronized(guard) {
                    val now = governor?.nowMs() ?: System.nanoTime() / 1_000_000
                    val eligible = waiting.filter { candidate ->
                        val decision = governor?.decision(candidate.kind, candidate.queuedAt)
                        decision == null || (decision.pressure != ResourcePressure.CRITICAL && decision.delayMs == 0L)
                    }
                    val next = eligible.minByOrNull { candidate ->
                        if (candidate.kind == ResourceWorkKind.CLEANUP) Priority.LIVE.ordinal
                        else if (candidate.priority == Priority.BACKGROUND && now - candidate.queuedAt >= foregroundFairnessMs)
                            Priority.INTERACTIVE.ordinal
                        else candidate.priority.ordinal
                    }
                    if (active == null && next === ticket) {
                        waiting.remove(ticket)
                        // A registered predicate may be held across this whole lease's lifetime.
                        waiting.forEach { it.observedContention = true }
                        Lease(waited || ticket.observedContention, kind).also { active = it }
                    } else null
                }
                if (granted != null) {
                    caller.ensureActive()
                    if (!current()) { granted.close(); return null }
                    return granted
                }
                waited = true
                delay(pollMs)
            }
        } catch (failure: Throwable) { granted?.close(); throw failure }
        finally {
            synchronized(guard) {
                waiting.remove(ticket)
                if (reservedCleanupCapacity) pendingCleanupRegistrations--
            }
        }
    }

    suspend fun <T> withLease(priority: Priority, current: () -> Boolean, block: suspend () -> T): T? {
        val lease = acquire(priority, current = current) ?: return null
        try {
            currentCoroutineContext().ensureActive()
            if (!current()) return null
            return block()
        } finally { lease.close() }
    }

    companion object {
        val shared = NativeComputeAdmission(governor = ResourceGovernorRuntime.shared)
        private fun Priority.workKind() = when (this) {
            Priority.LIVE -> ResourceWorkKind.LIVE
            Priority.INTERACTIVE -> ResourceWorkKind.INTERACTIVE
            Priority.BACKGROUND -> ResourceWorkKind.BACKGROUND
        }
    }
}
