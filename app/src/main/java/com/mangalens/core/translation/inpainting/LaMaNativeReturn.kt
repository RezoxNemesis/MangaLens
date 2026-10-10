package com.mangalens.core.translation.inpainting

import kotlinx.coroutines.*

/** Cancellation requests native termination; it never proves that JNI has returned or permits early close. */
internal suspend fun <T> awaitLaMaNativeReturn(worker: Deferred<T>, terminate: () -> Unit,
    unproven: () -> LaMaNativeCloseUnproven?): T = try { worker.await() }
catch (cancelled: CancellationException) {
    try { terminate() } catch (_: Throwable) { /* Still await real completion; termination was not established. */ }
    withContext(NonCancellable) { try { worker.await() } catch (_: Throwable) { /* Retained cleanup state wins below. */ } }
    unproven()?.let { throw it }
    throw cancelled
}
