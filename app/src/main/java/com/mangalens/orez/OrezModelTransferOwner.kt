package com.mangalens.orez

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

/** Immediate memory barrier for Pause commands waiting on durable IO. */
internal class OrezModelTransferPauseFence {
    private val requested = AtomicLong(0)
    private val acknowledged = AtomicLong(0)
    val isPaused: Boolean get() = requested.get() > acknowledged.get()
    fun request(): Long = requested.incrementAndGet()
    fun acknowledge(epoch: Long) {
        require(epoch in 0..requested.get())
        acknowledged.accumulateAndGet(epoch) { previous, next -> maxOf(previous, next) }
    }
}

internal data class OrezModelTransferIdentity(
    val id: String?,
    val active: Boolean,
    val pendingEnqueue: Boolean = false,
    val tier: String? = null,
    val workId: String? = id
)

internal enum class OrezModelTransferRecovery { NONE, REQUEUE_PENDING, MISSING_WORK }

internal fun OrezModelTransferIdentity.recovery(knownWorkIds: Set<String>): OrezModelTransferRecovery {
    if (!active || id == null || workId in knownWorkIds) return OrezModelTransferRecovery.NONE
    return if (pendingEnqueue && tier != null) OrezModelTransferRecovery.REQUEUE_PENDING
        else OrezModelTransferRecovery.MISSING_WORK
}

/** Guards the worker's durable identity and status mutations under one short process lock. */
internal class OrezModelTransferOwner<Mutation>(
    private val lock: Any,
    private val read: () -> OrezModelTransferIdentity,
    private val write: (OrezModelTransferIdentity, Mutation) -> Unit,
    private val noMutation: Mutation
) {
    fun begin(id: String, mutation: Mutation = noMutation) = synchronized(lock) {
        require(id.isNotBlank())
        write(OrezModelTransferIdentity(id, true), mutation)
    }

    fun adopt(id: String, workId: String, tier: String, mutation: Mutation) = synchronized(lock) {
        write(OrezModelTransferIdentity(id, true, tier = tier, workId = workId), mutation)
    }

    fun beginIfIdle(id: String, mutation: Mutation): Boolean = synchronized(lock) {
        if (read().active) false else { begin(id, mutation); true }
    }

    fun prepare(id: String, tier: String, mutation: Mutation): Boolean = synchronized(lock) {
        if (read().active) false else {
            write(OrezModelTransferIdentity(id, true, pendingEnqueue = true, tier = tier), mutation)
            true
        }
    }

    fun enqueued(id: String, mutation: Mutation = noMutation): Boolean = synchronized(lock) {
        if (!isCurrent(id)) false else {
            write(read().copy(pendingEnqueue = false), mutation)
            true
        }
    }

    fun pause(mutation: Mutation = noMutation) = synchronized(lock) {
        write(read().copy(active = false, pendingEnqueue = false), mutation)
    }

    fun identity(): OrezModelTransferIdentity = synchronized(lock) { read() }
    fun isCurrent(id: String): Boolean = synchronized(lock) { read().let { it.id == id && it.active } }
    fun checkpoint(id: String) {
        if (!isCurrent(id)) throw CancellationException("OREZ model transfer was paused or replaced.")
    }

    fun update(id: String, mutation: Mutation): Boolean = synchronized(lock) {
        if (!isCurrent(id)) false else { write(read(), mutation); true }
    }

    fun finish(id: String, mutation: Mutation): Boolean = synchronized(lock) {
        if (!isCurrent(id)) false else {
            write(read().copy(active = false, pendingEnqueue = false), mutation)
            true
        }
    }
}
