package com.mangalens.core.search.embedding

import com.mangalens.core.compute.NativeComputePrecondition
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The retained native producer keeps this scalar mailbox, never a retired screen callback. */
internal class SemanticNativeRequester(continuation: CancellableContinuation<SemanticNativePass>, current: () -> Boolean,
    completed: (Int) -> Unit, precondition: NativeComputePrecondition?) {
    private data class Delivery(val continuation: CancellableContinuation<SemanticNativePass>, val current: () -> Boolean,
        val completed: (Int) -> Unit, val precondition: NativeComputePrecondition?)
    private val guard = Any()
    private var delivery: Delivery? = Delivery(continuation, current, completed, precondition)
    fun retire() = synchronized(guard) { delivery = null }
    fun current(): Boolean = synchronized(guard) { delivery?.let { it.continuation.isActive && it.current() } == true }
    fun progress(count: Int) = synchronized(guard) { delivery?.let { if (it.continuation.isActive && it.current()) it.completed(count) } }
    suspend fun validate(waited: Boolean) {
        // This local exists only during the feature check, never across model parsing or inference.
        val validator = synchronized(guard) { delivery?.precondition }
        validator?.validate?.invoke(waited)
        if (!current()) throw CancellationException("The semantic request was retired.")
    }
    fun finish(result: SemanticNativePass?, failure: Throwable?) = synchronized(guard) {
        val target = delivery; delivery = null
        if (target?.continuation?.isActive == true) {
            if (failure != null) target.continuation.resumeWithException(failure)
            else if (target.current()) target.continuation.resume(requireNotNull(result))
            else target.continuation.resumeWithException(CancellationException("Semantic search changed before delivery."))
        }
    }
}

/** Strong references survive a failed close; admission stays reserved until process restart. */
internal class SemanticNativeCloseUnproven(val retained: List<Any>, cause: Throwable) : IllegalStateException(
    "The native English search session could not be confirmed closed. Restart MangaLens before more native work.", cause)
