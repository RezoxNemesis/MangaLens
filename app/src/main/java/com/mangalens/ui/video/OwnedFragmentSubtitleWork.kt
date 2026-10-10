package com.mangalens.ui.video

import kotlinx.coroutines.*
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One process-owned producer, including its actual blocking read and cleanup return. */
internal class OwnedFragmentSubtitleWork(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cleanupFailure: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val active = AtomicReference<Owner?>(null)
    private class Resource(val value: Closeable, var attempted: Boolean = false)

    suspend fun <T> run(work: suspend (Owner) -> T): T {
        val capturedContext = currentCoroutineContext().minusKey(Job)
        return suspendCancellableCoroutine { continuation ->
            val owner = Owner()
            if (!active.compareAndSet(null, owner)) {
                continuation.resumeWithException(IOException(if (active.get()?.unproven == true)
                    "The previous subtitle source did not close safely. Restart the app before retrying."
                    else "The previous subtitle source is still finishing. Try again later."))
                return@suspendCancellableCoroutine
            }
            val requester = FragmentSubtitleRequester(continuation)
            continuation.invokeOnCancellation {
                requester.retire() // No retired UI continuation remains in the producer.
                owner.retire()
            }
            val registered = CompletableDeferred<Unit>()
            val job = scope.launch(capturedContext + scope.coroutineContext.minusKey(Job), start = CoroutineStart.UNDISPATCHED) {
                var result: T? = null
                var failure: Throwable? = null
                try { registered.await(); owner.checkActive(); result = work(owner); owner.checkActive() }
                catch (caught: Throwable) { failure = caught }
                finally {
                    withContext(NonCancellable) { owner.closeOwned() }
                    owner.producer.set(null)
                    if (!owner.unproven) check(active.compareAndSet(owner, null))
                    val problem = if (owner.unproven) IOException("Subtitle source cleanup could not be proven. Its source files were retained.") else failure
                    if (problem != null) requester.fail(problem)
                    else {
                        @Suppress("UNCHECKED_CAST")
                        requester.complete(result as T)
                    }
                }
            }
            owner.producer.set(job)
            registered.complete(Unit)
        }
    }

    internal inner class Owner {
        private val guard = Any()
        @Volatile private var retired = false
        @Volatile var unproven = false
            private set
        internal val producer = AtomicReference<Job?>(null)
        private val resources = mutableListOf<Resource>()
        private val unreleased = mutableListOf<Any>()
        private var nativeReleaseUnproven = false

        fun checkActive() { if (retired || unproven) throw CancellationException("Subtitle source producer was retired or could not close safely.") }
        fun own(value: Closeable) = synchronized(guard) { resources.add(Resource(value)) }
        fun retain(value: Any? = null) {
            synchronized(guard) { unproven = true; value?.let(unreleased::add) }
        }
        fun retainNativeConsumer(value: Any) {
            synchronized(guard) { nativeReleaseUnproven = true }
            retain(value)
        }
        fun close(value: Closeable) {
            val resource = synchronized(guard) {
                resources.firstOrNull { it.value === value }?.also {
                    check(!it.attempted) { "Subtitle source resource already attempted its sole close." }
                    it.attempted = true
                } ?: error("Subtitle source resource was not owned.")
            }
            try {
                value.close()
                synchronized(guard) { resources.remove(resource) }
            } catch (failure: Throwable) {
                retain(value)
                try { cleanupFailure(failure.javaClass.simpleName) } catch (_: Throwable) { retain() }
                throw failure
            }
        }
        internal fun retire() {
            synchronized(guard) { if (retired) return; retired = true }
            // Caller cancellation does not run cancellation/provider hooks on Main.
            scope.launch { producer.get()?.cancel(CancellationException("Subtitle source requester retired")) }
        }
        internal fun closeOwned() {
            // Only the actual producer calls this after its work has returned. Never force-close
            // a descriptor while MediaExtractor, a hash read, or its native consumer is pending.
            val remaining = synchronized(guard) { if (nativeReleaseUnproven) return; resources.filter { !it.attempted }.asReversed().map { it.value } }
            remaining.forEach { value ->
                // A wrapper's sole close can also acknowledge its separately registered anchor.
                val stillOwned = synchronized(guard) { resources.any { it.value === value && !it.attempted } }
                if (stillOwned) try { close(value) } catch (_: Throwable) { /* retain slot and actual handle */ }
            }
        }
    }
}

private class FragmentSubtitleRequester<T>(continuation: CancellableContinuation<T>) {
    private val requester = AtomicReference<CancellableContinuation<T>?>(continuation)
    fun retire() { requester.set(null) }
    fun complete(value: T) { requester.getAndSet(null)?.resume(value) }
    fun fail(failure: Throwable) { requester.getAndSet(null)?.resumeWithException(failure) }
}
