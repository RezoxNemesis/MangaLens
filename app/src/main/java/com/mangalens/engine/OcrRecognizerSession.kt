package com.mangalens.engine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One page owns at most one native recognizer. Sequential reads of the same script
 * retain its model; a script switch or failed read releases it before another opens.
 * The process callback must await native completion, including on cancellation.
 */
internal class OcrRecognizerSession<R>(
    private val open: (String) -> R,
    private val dispose: (R) -> Unit
) {
    private var closed = false
    private var active: R? = null
    private var activeScript: String? = null

    suspend fun <T> read(script: String, process: suspend (R) -> T): T {
        check(!closed)
        currentCoroutineContext().ensureActive()
        if (activeScript != script) {
            release()
            active = open(script)
            activeScript = script
        }
        val resource = checkNotNull(active)
        return try {
            currentCoroutineContext().ensureActive()
            process(resource)
        } catch (failure: Throwable) {
            try { release() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun close() {
        if (closed) return
        closed = true
        release()
    }

    private fun release() {
        val previous = active
        active = null
        activeScript = null
        if (previous != null) dispose(previous)
    }
}
