package com.mangalens.core.events

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Bounded, lossy wakeups only. No observer callback runs inside a publisher. */
class AppEventBus(private val bufferCapacity: Int = 8, private val maxSubscriptions: Int = 32,
    private val duplicateCapacity: Int = 64) {
    private val guard = Any()
    private val subscriptions = LinkedHashSet<Subscription>()
    init {
        require(bufferCapacity in 1..8) { "Event buffer exceeds the eight-hint limit" }
        require(maxSubscriptions in 1..32) { "Event subscribers exceed the thirty-two limit" }
        require(duplicateCapacity in 1..64) { "Event duplicate cache exceeds the sixty-four limit" }
    }
    fun subscribe(filter: AppEventFilter): Subscription =
        checkNotNull(trySubscribe(filter)) { "Event subscription limit reached" }

    /** Optional hints must never prevent the durable native work when slots are full. */
    fun trySubscribe(filter: AppEventFilter): Subscription? = synchronized(guard) {
        if (subscriptions.size >= maxSubscriptions) return@synchronized null
        Subscription(filter).also { subscriptions += it }
    }

    /** Returns the number of current queues accepting a hint, never an effect receipt. */
    fun publish(event: AppEvent): Int = synchronized(guard) {
        subscriptions.count { it.enqueue(event) }
    }

    inner class Subscription internal constructor(private val filter: AppEventFilter) : AutoCloseable {
        private val queue = Channel<AppEvent>(bufferCapacity, BufferOverflow.DROP_OLDEST)
        private val recent = LinkedHashSet<AppEvent>()
        private val collecting = AtomicBoolean()
        val events: Flow<AppEvent> = flow {
            check(collecting.compareAndSet(false, true)) { "An event subscription has one flow collector" }
            try { for (event in queue) emit(event) }
            finally { close() }
        }
        internal fun enqueue(event: AppEvent): Boolean {
            if (!filter.accepts(event) || !recent.add(event)) return false
            if (recent.size > duplicateCapacity) recent.iterator().let { it.next(); it.remove() }
            return queue.trySend(event).isSuccess
        }
        fun poll(): AppEvent? = queue.tryReceive().getOrNull()
        internal suspend fun receive(): AppEvent = queue.receive()
        override fun close() = synchronized(guard) {
            subscriptions.remove(this)
            recent.clear()
            queue.cancel()
        }
    }
}

object AppEvents {
    val bus = AppEventBus()
    private val resourceOccurrence = AtomicLong()
    private fun occurrence() = resourceOccurrence.updateAndGet { if (it == Long.MAX_VALUE) 1 else it + 1 }
    fun memoryPressure(level: MemoryPressureLevel) { bus.publish(AppEvent.MemoryPressure(level, occurrence())) }
    fun modelReady(verifiedSha256: String) {
        bus.publish(AppEvent.ModelReady(ModelEventIdentity.sha256(verifiedSha256), occurrence()))
    }
}
