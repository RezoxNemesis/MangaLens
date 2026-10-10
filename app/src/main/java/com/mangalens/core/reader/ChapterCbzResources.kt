package com.mangalens.core.reader

import java.io.IOException
import java.util.IdentityHashMap

/**
 * Registered once with the application producer before any file opens. Private resources close
 * only on their producer; cancellation may close SAF independently but waits for private return.
 * A failed close retains the handle and makes the final registered close fail, retaining admission.
 */
internal class ChapterCbzResources : AutoCloseable {
    private class Handle(val value: AutoCloseable) { var claimed = false; var complete = false; var failure: Throwable? = null }
    private val guard = java.lang.Object()
    private val privateHandles = IdentityHashMap<AutoCloseable, Handle>()
    private var provider: Handle? = null
    private var firstFailure: Throwable? = null
    private var privateFailure: Throwable? = null
    private var privateStarted = false
    private var privateFinished = false
    private var closeStarted = false
    private var closeComplete = false

    fun beginPrivateWork() = synchronized(guard) {
        if (closeStarted || privateFinished) throw kotlinx.coroutines.CancellationException("CBZ export retired before private work.")
        privateStarted = true
    }
    fun <T : AutoCloseable> ownPrivate(value: T): T {
        val late = synchronized(guard) {
            check(!privateHandles.containsKey(value))
            privateHandles[value] = Handle(value)
            if (!privateFinished) privateStarted = true
            privateFinished
        }
        if (late) { closePrivate(value); throw IOException("Private export work already returned.") }
        return value
    }
    fun <R : AutoCloseable, T> usePrivate(value: R, read: (R) -> T): T {
        ownPrivate(value)
        try { return read(value) } finally { closePrivate(value) }
    }
    fun closePrivate(value: AutoCloseable) {
        val handle = synchronized(guard) { privateHandles[value] } ?: return
        closeOnce(handle, true)
    }
    fun ownProvider(value: AutoCloseable) {
        val late = synchronized(guard) { check(provider == null); provider = Handle(value); closeStarted || privateFinished }
        if (late) closeProvider()
    }
    fun closeProvider() {
        val handle = synchronized(guard) { provider } ?: return
        closeOnce(handle, false)
    }
    /** True only once every currently held private descriptor/ZIP resource actually closed. */
    fun privateReleaseProven(): Boolean = synchronized(guard) { privateHandles.isEmpty() && privateFailure == null }
    fun finishPrivateWork() = synchronized(guard) { privateFinished = true; guard.notifyAll() }

    private fun closeOnce(handle: Handle, privateResource: Boolean) {
        val claimed = synchronized(guard) {
            if (handle.claimed) {
                while (!handle.complete) guard.wait()
                handle.failure?.let { throw IOException("CBZ resource close could not be proven.", it) }
                false
            } else { handle.claimed = true; true }
        }
        if (!claimed) return
        var failure: Throwable? = null
        try { handle.value.close() } catch (caught: Throwable) { failure = caught }
        synchronized(guard) {
            handle.failure = failure; handle.complete = true
            if (failure == null && privateResource) privateHandles.remove(handle.value)
            if (failure != null) {
                if (firstFailure == null) firstFailure = failure
                if (privateResource && privateFailure == null) privateFailure = failure
            }
            guard.notifyAll()
        }
        failure?.let { throw IOException("CBZ resource close could not be proven.", it) }
    }
    override fun close() {
        val claimed = synchronized(guard) {
            if (closeStarted) { while (!closeComplete) guard.wait(); false }
            else { closeStarted = true; if (!privateStarted) { privateFinished = true; guard.notifyAll() }; true }
        }
        if (!claimed) { synchronized(guard) { firstFailure }?.let { throw IOException("CBZ cleanup is unproven.", it) }; return }
        try {
            // The provider close may unblock actual output IO. Private FDs stay producer-owned.
            try { closeProvider() } catch (_: IOException) { /* retained below */ }
            synchronized(guard) { while (!privateFinished) guard.wait() }
            // An open returning after cancellation registers/settles its late provider first.
            try { closeProvider() } catch (_: IOException) { /* retained below */ }
            val remaining = synchronized(guard) { privateHandles.keys.toList() }
            remaining.forEach { try { closePrivate(it) } catch (_: IOException) { /* retained below */ } }
        } finally { synchronized(guard) { closeComplete = true; guard.notifyAll() } }
        synchronized(guard) { firstFailure }?.let { throw IOException("CBZ cleanup is unproven. Restart before another export.", it) }
    }
}
