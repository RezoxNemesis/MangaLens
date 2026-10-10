package com.mangalens.ui.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class SavedVideoProbeBusyException(cleanupFailed: Boolean) : IOException(
    if (cleanupFailed) "The previous saved-file access could not close safely. Restart the app before opening another saved video."
    else "The previous saved-file read is still finishing. Try again later."
)

internal class SavedVideoProbeCleanupException(cause: Throwable) : IOException(
    "The saved-file access could not close safely. Restart the app before opening another saved video.", cause
)

/** Scalar diagnostics contain neither saved URIs nor provider exception messages. */
internal data class SavedVideoCleanupFailure(val phase: Phase, val exceptionType: String) {
    enum class Phase { PROVIDER_CANCEL, HANDLE_CLOSE }
}

/**
 * One application-owned instance admits one producer until its real read and all cleanup return.
 * Caller cancellation never joins that producer or invokes a provider callback on the caller thread.
 */
internal class OwnedSavedVideoProbe(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val reportCleanupFailure: (SavedVideoCleanupFailure) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val active = AtomicReference<Owner?>(null)

    suspend fun <T> run(cancelProvider: () -> Unit = {}, read: suspend (Owner) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val owner = Owner(cancelProvider)
            if (!active.compareAndSet(null, owner)) {
                continuation.resumeWithException(SavedVideoProbeBusyException(active.get()?.cleanupFailed == true))
                return@suspendCancellableCoroutine
            }
            val requester = SavedVideoProbeRequester(continuation)
            // Retirement only changes short-lived state and enqueues independently owned I/O work.
            continuation.invokeOnCancellation {
                requester.retire() // A stuck provider retains ownership, never the retired UI.
                owner.retire()
            }
            scope.launch {
                var completion: (Throwable?) -> Unit = { cleanupFailure ->
                    requester.fail(
                        cleanupFailure?.let(::SavedVideoProbeCleanupException)
                            ?: CancellationException("Saved-video probe did not enter its read")
                    )
                }
                try {
                    owner.checkActive()
                    val answer = read(owner)
                    completion = { cleanupFailure ->
                        if (cleanupFailure == null) requester.complete(answer)
                        else requester.fail(SavedVideoProbeCleanupException(cleanupFailure))
                    }
                } catch (failure: Throwable) {
                    completion = { cleanupFailure ->
                        requester.fail(
                            cleanupFailure?.let(::SavedVideoProbeCleanupException) ?: failure
                        )
                    }
                } finally {
                    owner.producerReturned(completion)
                }
            }
        }

    internal inner class Owner(private val cancelProvider: () -> Unit) {
        private val lock = Any()
        @Volatile private var retired = false
        @Volatile internal var cleanupFailed = false
            private set
        private var producerFinished = false
        private var finalized = false
        private var cancelClaimed = false
        private var closeClaimed = false
        private var handleWasRegistered = false
        private var registeredHandle: AutoCloseable? = null
        private var handle: AutoCloseable? = null
        private var duplicateHandle: AutoCloseable? = null
        private var cleanupInFlight = 0
        private var firstCleanupFailure: Throwable? = null
        private var completion: ((Throwable?) -> Unit)? = null

        fun checkActive() {
            if (retired) throw CancellationException("Saved-video probe was retired")
        }

        /** The production descriptor is registered immediately after open, before any read. */
        fun own(value: AutoCloseable) {
            val registration = synchronized(lock) {
                if (handleWasRegistered) {
                    // A caller bug must not leak a newly delivered handle or close the first twice.
                    if (registeredHandle === value) throw IllegalStateException("Saved-video probe registered the same handle twice")
                    duplicateHandle = value
                    cleanupInFlight++
                    false to value
                } else {
                    handleWasRegistered = true
                    registeredHandle = value
                    handle = value
                    true to if (retired) claimCloseLocked() else null
                }
            }
            if (!registration.first) {
                runCleanup(SavedVideoCleanupFailure.Phase.HANDLE_CLOSE) {
                    value.close()
                    synchronized(lock) { duplicateHandle = null }
                }
                throw IllegalStateException("Saved-video probe already registered its read handle")
            }
            // A late handle is closed on this producer's I/O thread before control returns to read.
            registration.second?.let { closeHandle(it) }
            checkActive()
        }

        /** Complete the real close before the final fresh row check and metadata publication. */
        fun closeReadHandle() {
            val closeNow = synchronized(lock) { claimCloseLocked() }
            closeNow?.let { closeHandle(it) }
            checkActive()
            val failure = synchronized(lock) { firstCleanupFailure }
            if (failure != null) throw SavedVideoProbeCleanupException(failure)
        }

        internal fun retire() {
            val work = synchronized(lock) {
                if (finalized || retired) return
                retired = true
                val cancel = if (!cancelClaimed) {
                    cancelClaimed = true
                    cleanupInFlight++
                    true
                } else false
                cancel to claimCloseLocked()
            }
            // Finite separate jobs let a blocked cancel callback and close settle independently.
            // Both retain this owner's sole admission slot until their actual return.
            if (work.first) scope.launch {
                runCleanup(SavedVideoCleanupFailure.Phase.PROVIDER_CANCEL, cancelProvider)
            }
            work.second?.let { value -> scope.launch { closeHandle(value) } }
        }

        internal fun producerReturned(deliver: (Throwable?) -> Unit) {
            val closeNow = synchronized(lock) { claimCloseLocked() }
            closeNow?.let { closeHandle(it) }
            val settled = synchronized(lock) {
                producerFinished = true
                completion = deliver
                settleLocked()
            }
            settled?.invoke()
        }

        private fun claimCloseLocked(): AutoCloseable? {
            val value = handle ?: return null
            if (closeClaimed) return null
            closeClaimed = true
            cleanupInFlight++
            return value
        }

        private fun closeHandle(value: AutoCloseable) {
            runCleanup(SavedVideoCleanupFailure.Phase.HANDLE_CLOSE) {
                value.close()
                synchronized(lock) { if (handle === value) handle = null }
            }
        }

        private fun runCleanup(phase: SavedVideoCleanupFailure.Phase, action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                recordFailure(failure)
                try {
                    reportCleanupFailure(SavedVideoCleanupFailure(phase, failure.javaClass.simpleName))
                } catch (reportFailure: Throwable) {
                    // An observer failure is retained too; it cannot discard the handle or slot.
                    recordFailure(reportFailure)
                }
            } finally {
                val settled = synchronized(lock) {
                    cleanupInFlight--
                    check(cleanupInFlight >= 0)
                    settleLocked()
                }
                settled?.invoke()
            }
        }

        private fun recordFailure(failure: Throwable) = synchronized(lock) {
            cleanupFailed = true
            if (firstCleanupFailure == null) firstCleanupFailure = failure
            else if (firstCleanupFailure !== failure) firstCleanupFailure?.addSuppressed(failure)
        }

        /** Retained failures require an honest restart; a close attempt never loses a handle. */
        private fun settleLocked(): (() -> Unit)? {
            if (!producerFinished || cleanupInFlight != 0 || finalized) return null
            finalized = true
            val failure = firstCleanupFailure
            if (failure == null) check(active.compareAndSet(this, null))
            val deliver = checkNotNull(completion)
            completion = null // A retained failed handle must not retain a retired UI coroutine.
            return { deliver(failure) }
        }
    }
}

/** Delivery can be detached immediately while the independent producer still owns its cleanup. */
private class SavedVideoProbeRequester<T>(receiver: CancellableContinuation<T>) {
    private val receiver = AtomicReference<CancellableContinuation<T>?>(receiver)
    fun retire() { receiver.set(null) }
    fun complete(value: T) { receiver.getAndSet(null)?.resume(value) }
    fun fail(failure: Throwable) { receiver.getAndSet(null)?.resumeWithException(failure) }
}

/**
 * Android's AssetFileDescriptor stream closes its underlying ParcelFileDescriptor. Choose one
 * owned close target: the stream after successful creation, or the descriptor if creation fails.
 */
internal class OwnedSavedVideoAssetHandle(
    private val descriptor: AutoCloseable,
    private val createStream: () -> InputStream,
    private val checkActive: () -> Unit
) : AutoCloseable {
    private val lock = java.lang.Object()
    private var creating = false
    private var readStarted = false
    private var closeClaimed = false
    private var stream: InputStream? = null

    fun readFirstByte(): Boolean {
        checkActive()
        synchronized(lock) {
            if (closeClaimed) throw CancellationException("Saved-video asset was retired before its read")
            check(!readStarted) { "Saved-video asset already started its first-byte read" }
            readStarted = true
            creating = true
        }
        val input = try {
            createStream()
        } catch (failure: Throwable) {
            synchronized(lock) { creating = false; lock.notifyAll() }
            throw failure
        }
        synchronized(lock) {
            stream = input
            creating = false
            lock.notifyAll()
            if (closeClaimed) throw CancellationException("Saved-video asset was retired during stream creation")
        }
        checkActive() // Creation may have returned after this owner's cancellation handler ran.
        return input.read() >= 0
    }

    override fun close() {
        val target = synchronized(lock) {
            if (closeClaimed) return
            closeClaimed = true
            // This wait occurs only on the owner's I/O producer/cleanup task, never its caller.
            // A late factory result remains owned and determines the one real close target.
            while (creating) lock.wait()
            stream ?: descriptor
        }
        target.close() // No fallback descriptor close after a stream-close failure.
    }
}
