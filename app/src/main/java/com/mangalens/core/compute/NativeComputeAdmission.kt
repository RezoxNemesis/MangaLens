package com.mangalens.core.compute

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

/** Scheduling only: each feature still owns its source/model/generation preconditions. */
internal class NativeComputeAdmission(private val pollMs: Long = 25, private val maxWaiting: Int = 64) {
    enum class Priority { LIVE, INTERACTIVE, BACKGROUND }
    private class Ticket(val priority: Priority)
    private val guard = Any()
    private val waiting = ArrayList<Ticket>()
    private var active: Lease? = null
    init { require(pollMs > 0 && maxWaiting > 0) }

    inner class Lease internal constructor(val waited: Boolean) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) synchronized(guard) { if (active === this) active = null }
        }
    }

    suspend fun acquire(priority: Priority, current: () -> Boolean): Lease? {
        val caller = currentCoroutineContext()
        val ticket = Ticket(priority)
        synchronized(guard) {
            check(waiting.size < maxWaiting) { "Native compute queue is full. Retry when local work finishes." }
            waiting += ticket
        }
        var granted: Lease? = null
        var waited = false
        try {
            while (true) {
                caller.ensureActive()
                if (!current()) return null
                granted = synchronized(guard) {
                    if (active == null && waiting.minByOrNull { it.priority.ordinal } === ticket) {
                        waiting.remove(ticket)
                        Lease(waited).also { active = it }
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
        finally { synchronized(guard) { waiting.remove(ticket) } }
    }

    suspend fun <T> withLease(priority: Priority, current: () -> Boolean, block: suspend () -> T): T? {
        val lease = acquire(priority, current) ?: return null
        try {
            currentCoroutineContext().ensureActive()
            if (!current()) return null
            return block()
        } finally { lease.close() }
    }

    companion object { val shared = NativeComputeAdmission() }
}
