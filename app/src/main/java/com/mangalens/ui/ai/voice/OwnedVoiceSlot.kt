package com.mangalens.ui.ai.voice

import java.util.concurrent.atomic.AtomicBoolean

/** Application-owned capacity; no UI requester, callback, Context or coroutine is held here. */
internal class OwnedVoiceSlot {
    class Owner internal constructor(val token: Long) {
        private val attached = AtomicBoolean(true)
        private val stopped = AtomicBoolean(false)
        private val retiring = AtomicBoolean(false)
        val requesterAttached get() = attached.get()
        val stopRequested get() = stopped.get()
        val retired get() = retiring.get()
        internal fun stop() { stopped.set(true) }
        internal fun retire() {
            attached.set(false) // Detach before cancellation/retirement becomes observable.
            retiring.set(true)
            stopped.set(true)
        }
    }
    private val guard = Any()
    private var next = 0L
    private var current: Owner? = null
    fun acquire(): Owner? = synchronized(guard) {
        if (current != null) null else Owner(++next).also { current = it }
    }
    fun isCurrent(owner: Owner): Boolean = synchronized(guard) { current === owner && owner.requesterAttached && !owner.retired }
    fun requestStop(token: Long) = synchronized(guard) { current?.takeIf { it.token == token }?.stop(); Unit }
    fun retire(token: Long) = synchronized(guard) { current?.takeIf { it.token == token }?.retire(); Unit }
    fun releaseAfterCleanup(owner: Owner, proven: Boolean): Boolean = synchronized(guard) {
        if (current !== owner || !proven) false else { current = null; true }
    }
}
