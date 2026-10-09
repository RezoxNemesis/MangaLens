package com.mangalens.oreznative

import java.util.ArrayDeque
import java.util.IdentityHashMap

/** Retain captured owner identities until physical close returns, independent of the live model map. */
internal class NativeMemoryReleaseCoordinator<Owner : Any>(private val onFailure: (Throwable) -> Unit = {}) {
    private class Release<Owner>(val owner: Owner, val close: () -> Unit)
    private val guard = Any()
    private val drainGuard = Any()
    private val pending = IdentityHashMap<Owner, Release<Owner>>()
    private val waiting = ArrayDeque<Release<Owner>>()
    @Volatile var lastFailure: Throwable? = null
        private set

    fun request(owners: List<Owner>, cancel: (Owner) -> Unit, close: (Owner) -> Unit,
        executor: ((() -> Unit) -> Unit)? = null) {
        val captured = owners.toList()
        captured.forEach { owner -> try { cancel(owner) } catch (failure: Exception) { report(failure) } }
        synchronized(guard) {
            captured.forEach { owner ->
                if (!pending.containsKey(owner)) {
                    val release = Release(owner) { close(owner) }
                    pending[owner] = release
                    waiting.addLast(release)
                }
            }
        }
        // Every callback drains the same cumulative pending set, so an executor may safely conflate callbacks.
        val release = { drain() }
        try {
            if (executor == null) Thread(release, "orez-memory-release").start()
            else executor(release)
        } catch (failure: Exception) {
            // Keep accepted owners for another trim; bypassing a failed executor could overlap a native peer.
            report(failure)
        }
    }

    private fun drain() = synchronized(drainGuard) {
        // One finite batch per physical boundary. New requests schedule another batch.
        val batch = synchronized(guard) { buildList { while (waiting.isNotEmpty()) add(waiting.removeFirst()) } }
        for (release in batch) {
            var completed = false
            try { release.close(); completed = true }
            catch (failure: Exception) { report(failure) }
            finally {
                synchronized(guard) {
                    if (completed) pending.remove(release.owner)
                    else waiting.addLast(release)
                }
            }
        }
    }

    private fun report(failure: Throwable) {
        lastFailure = failure
        runCatching { onFailure(failure) }
    }
}
