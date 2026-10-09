package com.mangalens.orez

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** The model worker's transfer loop, separated from Android notification plumbing. */
internal object OrezModelTransferIO {
    private val writers = ConcurrentHashMap<String, Mutex>()

    /** A cancelled blocking read keeps its writer lease until input and output cleanup finishes. */
    suspend fun <T> withWriter(part: File, checkpoint: () -> Unit, action: suspend () -> T): T =
        writers.computeIfAbsent(part.canonicalPath) { Mutex() }.withLock {
            currentCoroutineContext().ensureActive()
            checkpoint()
            action()
        }

    /** Disconnect on a separate IO thread when cancellation arrives during a blocking read. */
    suspend fun <T> withConnection(abort: () -> Unit, action: suspend () -> T): T = coroutineScope {
        val disconnect = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable + Dispatchers.IO) { runCatching(abort) } }
        }
        try { action() }
        finally { disconnect.cancel() }
    }

    suspend fun copy(
        part: File,
        input: InputStream,
        offset: Long,
        total: Long,
        checkpoint: () -> Unit,
        clock: () -> Long,
        publish: suspend (Long) -> Unit
    ) {
        val operation = currentCoroutineContext()
        fun check() { operation.ensureActive(); checkpoint() }
        check()
        var done = offset
        var lastPublishBytes = done
        var lastPublishAt = clock()
        FileOutputStream(part, offset > 0L).use { output ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                check()
                val count = input.read(buffer)
                // An HTTP read can return a byte after WorkManager has stopped this worker.
                check()
                if (count < 0) break
                if (count == 0) continue
                if (done + count > total) throw OrezModelCompatibilityException("Model transfer exceeded expected size. The working model was kept.")
                output.write(buffer, 0, count)
                done += count
                val now = clock()
                if (done - lastPublishBytes >= 8L * 1024 * 1024 || now - lastPublishAt >= 1_000L || done >= total) {
                    check()
                    publish(done)
                    lastPublishBytes = done
                    lastPublishAt = now
                }
            }
            check()
            output.fd.sync()
        }
    }
}
