package com.mangalens.orez.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One finite startup snapshot, bounded batches, no repeated attempt of a failing control. */
object OrezControlRecoveryBatch {
    suspend fun run(capturedIds: List<String>, recover: suspend (String) -> Unit) {
        capturedIds.distinct().chunked(8).forEach { batch ->
            currentCoroutineContext().ensureActive()
            batch.forEach { id ->
                try { recover(id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Durable proof remains for an explicit retry. Continue to later IDs. */ }
            }
        }
    }
}
