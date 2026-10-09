package com.mangalens.ui.video

/** Uses the engine's handle guard to keep delayed aborts tied to one native invocation. */
internal class NativeLiveSpeechHandles(
    private val handleGuard: Any,
    private val currentHandle: () -> Long,
    private val abort: (Long) -> Unit
) {
    class Invocation internal constructor(val handle: Long) {
        internal var active = true // Only read or written while holding handleGuard.
    }
    private var active: Invocation? = null

    fun begin(handle: Long): Invocation = synchronized(handleGuard) {
        check(handle != 0L && currentHandle() == handle) { "Speech handle changed before native admission" }
        check(active == null) { "Speech inference is already active" }
        Invocation(handle).also { active = it }
    }

    fun cancel(invocation: Invocation) = synchronized(handleGuard) {
        if (active === invocation && invocation.active && currentHandle() == invocation.handle) {
            abort(invocation.handle)
        }
    }

    fun finish(invocation: Invocation) = synchronized(handleGuard) {
        invocation.active = false
        if (active === invocation) active = null
    }
}

/** Generation changes and final cue/timeout/error publication share the engine's handle guard. */
internal fun publishLiveSpeechWindow(handleGuard: Any, isCurrent: () -> Boolean, update: () -> Unit): Boolean =
    synchronized(handleGuard) {
        if (!isCurrent()) false else { update(); true }
    }
