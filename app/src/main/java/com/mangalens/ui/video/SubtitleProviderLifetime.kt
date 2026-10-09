package com.mangalens.ui.video

import com.mangalens.core.compute.NativeComputePrecondition
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** ML Kit callbacks own the provider until return, even after their caller stops awaiting. */
internal class SubtitleProviderLifetime(private val release: () -> Unit) : AutoCloseable {
    private val guard = Any()
    private var active = 0
    private var closing = false
    private var released = false

    suspend fun <T> run(call: suspend () -> T): T {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        synchronized(guard) {
            check(!closing) { "The subtitle translator is closed." }
            active++
        }
        val admission = Any()
        var admitted = false
        val pending = cleanup.async(caller[NativeComputePrecondition] ?: EmptyCoroutineContext, start = CoroutineStart.UNDISPATCHED) {
            try {
                providerLane.withLock {
                    // Queued calls stopped before native admission do no late work.
                    synchronized(admission) { caller.ensureActive(); admitted = true }
                    call()
                }
            } finally {
                synchronized(guard) { active--; releaseIfIdle() }
            }
        }
        return try { pending.await() }
        catch (cancelled: CancellationException) {
            synchronized(admission) { if (!admitted) pending.cancel() }
            throw cancelled
        }
    }
    override fun close() = synchronized(guard) { closing = true; releaseIfIdle() }
    private fun releaseIfIdle() {
        if (closing && active == 0 && !released) { released = true; release() }
    }
    companion object {
        private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val providerLane = Mutex()
    }
}
