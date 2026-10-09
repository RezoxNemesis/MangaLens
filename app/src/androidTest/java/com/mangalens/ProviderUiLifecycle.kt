package com.mangalens

import java.util.IdentityHashMap

/** Test-only ownership of accepted launches, including creation after a failed setup/close. */
internal class ProviderUiLifecycle<A : Any> {
    enum class State { CREATED, STARTED, RESUMED, PAUSED, STOPPED, DESTROYED }
    private data class Record(val epoch: Long, val state: State)
    private var latest = 0L
    private var closed = false
    private val pending = linkedSetOf<Long>()
    private val owned = IdentityHashMap<A, Record>()

    @Synchronized fun beginLaunch(): Long {
        check(!closed) { "Provider harness is closed" }
        return (++latest).also { pending += it }
    }

    /** Returns whether this owned activity must finish; unrelated activities never enter this map. */
    @Synchronized fun observe(activity: A, epoch: Long, state: State): Boolean {
        require(epoch in 1..latest)
        pending.remove(epoch)
        if (state == State.DESTROYED) {
            owned.remove(activity)
            return false
        }
        owned[activity] = Record(epoch, state)
        return closed || epoch != latest
    }

    @Synchronized fun resumed(): A? = if (closed) null else owned.entries
        .filter { it.value.epoch == latest && it.value.state == State.RESUMED }.singleOrNull()?.key

    @Synchronized fun retired(): List<A> = owned.entries.filter { it.value.epoch != latest }.map { it.key }
    @Synchronized fun launchFailed(epoch: Long) { pending.remove(epoch) }
    @Synchronized fun close(): List<A> { closed = true; return owned.keys.toList() }
    @Synchronized fun canDetach(): Boolean = closed && pending.isEmpty() && owned.isEmpty()
}
