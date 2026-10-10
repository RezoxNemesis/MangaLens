package com.mangalens.download

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class MediaResolutionTimeoutException(timeoutMs: Long) : IOException(
    "Media resolution timed out after ${(timeoutMs + 999L) / 1_000L} seconds. Retry or open the source page."
)

/** Each request owns its deadline and cleanup; a cancelled request cannot stop another request. */
internal class MediaResolutionSession(val timeoutMs: Long, internal val privateFileOwner: DownloadPrivateFileOwner? = null) {
    private val fileCleanupUnproven = AtomicBoolean(false)
    internal fun retainPrivateFiles() { fileCleanupUnproven.set(true) }
    internal fun privateFilesReleased(): Boolean = !fileCleanupUnproven.get()
    val deadlineNanos: Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    private val lock = Any()
    private var stopped: Exception? = null
    private var nextCleanupId = 0L
    private val cleanup = linkedMapOf<Long, () -> Unit>()

    fun checkActive() {
        synchronized(lock) { stopped }?.let { throw it }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Media resolution cancelled")
        if (System.nanoTime() - deadlineNanos >= 0L) throw MediaResolutionTimeoutException(timeoutMs)
    }

    fun remainingMillis(): Long {
        checkActive()
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(1L)
    }

    fun isStopped(): Boolean = synchronized(lock) { stopped != null } ||
        System.nanoTime() - deadlineNanos >= 0L

    fun onCancel(action: () -> Unit): AutoCloseable {
        val id = synchronized(lock) {
            if (stopped == null) (++nextCleanupId).also { cleanup[it] = action } else null
        }
        if (id == null) MediaResolutionCleanup.dispatch(action)
        return AutoCloseable { if (id != null) synchronized(lock) { cleanup.remove(id) } }
    }

    fun cancel(cause: Exception = InterruptedException("Media resolution cancelled")) {
        val actions = synchronized(lock) {
            if (stopped != null) return
            stopped = cause
            cleanup.values.toList().also { cleanup.clear() }
        }
        actions.forEach { MediaResolutionCleanup.dispatch(it) }
    }
}

/** Blocking native cleanup must never run on the UI thread or the deadline scheduler. */
private object MediaResolutionCleanup {
    private val workers = ThreadPoolExecutor(
        3, 3, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { task -> Thread(task, "mangalens-media-cleanup").apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }

    fun dispatch(action: () -> Unit): Boolean = try {
        workers.execute { runCatching(action) }
        true
    } catch (_: java.util.concurrent.RejectedExecutionException) {
        false
    }
}

/**
 * A coroutine timeout around runInterruptible still waits for an uncooperative native call.
 * This bridge completes independently, interrupts the worker, and cancels its tracked resources.
 * The fixed pool and finite queue also bound abandoned native work if initialization ignores IO
 * cancellation. Queue time is part of the same deadline.
 */
internal object MediaResolutionRunner {
    private val workers = ThreadPoolExecutor(
        3, 3, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "mangalens-media-resolution").apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }
    internal val timer = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "mangalens-media-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    suspend fun <T> run(timeoutMs: Long, privateFileOwner: DownloadPrivateFileOwner? = null, work: (MediaResolutionSession) -> T): T {
        require(timeoutMs in 1L..300_000L) { "Media resolution timeout must be between 1 ms and 5 minutes." }
        val reservation = privateFileOwner?.let(DownloadPrivateFileOwners::reserve)
        return suspendCancellableCoroutine { continuation ->
            val session = MediaResolutionSession(timeoutMs, privateFileOwner)
            val ownerState = java.util.concurrent.atomic.AtomicInteger(0) // queued / running / returned
            val requester = AtomicReference<kotlinx.coroutines.CancellableContinuation<T>?>(continuation)
            fun settleQueuedOwner() {
                if (ownerState.compareAndSet(0, 2)) reservation?.close()
            }
            val completed = AtomicBoolean(false)
            val timeout = AtomicReference<ScheduledFuture<*>?>(null)
            val task = FutureTask<Unit> {
                if (!ownerState.compareAndSet(0, 1)) return@FutureTask
                var lease: DownloadPrivateFileOwners.Lease? = null
                val result = try {
                    runCatching {
                        lease = privateFileOwner?.let(DownloadPrivateFileOwners::acquire)
                        session.checkActive()
                        work(session).also { session.checkActive() }
                    }
                } finally {
                    session.cancel()
                    if (!session.privateFilesReleased()) lease?.retain()
                    try { lease?.close() } finally { reservation?.close(); ownerState.set(2) }
                }
                if (completed.compareAndSet(false, true)) {
                    timeout.get()?.cancel(false)
                    requester.getAndSet(null)?.resumeWith(result)
                }
            }
            fun stop(cause: Exception) {
                task.cancel(true)
                workers.remove(task)
                settleQueuedOwner()
                session.cancel(cause)
            }
            timeout.set(timer.schedule({
                if (completed.compareAndSet(false, true)) {
                    val failure = MediaResolutionTimeoutException(timeoutMs)
                    // Resume before cleanup so a native cleanup cannot extend the user deadline.
                    requester.getAndSet(null)?.resumeWith(Result.failure(failure))
                    stop(failure)
                }
            }, timeoutMs, TimeUnit.MILLISECONDS))
            continuation.invokeOnCancellation {
                requester.set(null)
                if (completed.compareAndSet(false, true)) {
                    timeout.get()?.cancel(false)
                    stop(InterruptedException("Media resolution cancelled"))
                }
            }
            if (completed.get()) timeout.get()?.cancel(false)
            else try {
                workers.execute(task)
                // Cancellation can happen between the active check and enqueue.
                if (task.isCancelled) workers.remove(task)
            } catch (failure: java.util.concurrent.RejectedExecutionException) {
                if (completed.compareAndSet(false, true)) {
                    timeout.get()?.cancel(false)
                    stop(failure)
                    requester.getAndSet(null)?.resumeWith(Result.failure(IOException("Other media requests are still stopping. Retry shortly.", failure)))
                }
            }
        }
    }
}

/** Repeated cancellation covers the race where native process registration follows cancellation. */
internal class MediaProcessGuard(
    private val session: MediaResolutionSession,
    private val processId: String,
    private val timeoutMs: Long,
    private val destroy: (String) -> Boolean
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val cleanupPending = AtomicBoolean(false)
    private val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    private fun queueStop() {
        if (!cleanupPending.compareAndSet(false, true)) return
        val accepted = MediaResolutionCleanup.dispatch {
            try { destroy(processId) } finally { cleanupPending.set(false) }
        }
        if (!accepted) cleanupPending.set(false)
    }
    private fun stopIfOpen() { if (!closed.get()) queueStop() }
    private val cancellation = session.onCancel { stopIfOpen() }
    private val watcher = MediaResolutionRunner.timer.scheduleAtFixedRate({
        if (session.isStopped() || System.nanoTime() - deadlineNanos >= 0L) stopIfOpen()
    }, 0L, 100L, TimeUnit.MILLISECONDS)

    fun checkActive() {
        session.checkActive()
        if (System.nanoTime() - deadlineNanos >= 0L) throw MediaResolutionTimeoutException(timeoutMs)
        if (closed.get()) throw InterruptedException("Media process attempt already closed")
    }

    fun <T> run(block: () -> T): T {
        checkActive()
        try { return block().also { checkActive() } }
        catch (failure: Exception) {
            try { checkActive() }
            catch (stopped: Exception) {
                if (stopped !== failure) stopped.addSuppressed(failure)
                throw stopped
            }
            throw failure
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            watcher.cancel(false)
            cancellation.close()
            queueStop()
        }
    }
}
