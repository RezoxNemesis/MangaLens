package com.mangalens.ui.video

import com.mangalens.core.compute.NativeComputeAdmission
import com.mangalens.core.compute.checkNativeComputePrecondition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

internal interface NativeLiveSpeechInvocation<T> {
    fun infer(): T
    fun cancel()
    fun finish()
}

internal sealed interface NativeLiveSpeechOutcome<out T> {
    data class Completed<T>(val value: T) : NativeLiveSpeechOutcome<T>
    data object TimedOut : NativeLiveSpeechOutcome<Nothing>
    data object Invalidated : NativeLiveSpeechOutcome<Nothing>
}

/** A live window's budget includes queueing and retains native ownership through actual cleanup. */
internal class NativeLiveSpeechBudget(
    private val budgetMs: Long,
    private val clock: () -> Long,
    private val pollMs: Long = 50L
) {
    init { require(budgetMs > 0 && pollMs > 0) }

    suspend fun <T> run(
        mutex: Mutex,
        isCurrent: () -> Boolean,
        admission: NativeComputeAdmission = NativeComputeAdmission.shared,
        open: (permit: () -> Boolean) -> NativeLiveSpeechInvocation<T>?
    ): NativeLiveSpeechOutcome<T> {
        val caller = currentCoroutineContext()
        val started = clock()
        fun stopped(): NativeLiveSpeechOutcome<Nothing>? = when {
            !isCurrent() -> NativeLiveSpeechOutcome.Invalidated
            clock() - started >= budgetMs -> NativeLiveSpeechOutcome.TimedOut
            else -> null
        }

        // Waiting for load/reload must not make an expired window enter JNI later.
        // The same mutex still serializes every native load/infer/free operation.
        val owner = Any()
        var waitedForEngine = false
        while (true) {
            caller.ensureActive()
            stopped()?.let { return it }
            if (mutex.tryLock(owner)) break
            waitedForEngine = true
            delay(pollMs)
        }

        var compute: NativeComputeAdmission.Lease? = null
        try {
            caller.ensureActive()
            stopped()?.let { return it }
            compute = admission.acquire(NativeComputeAdmission.Priority.LIVE) { caller.isActive && stopped() == null }
                ?: run { caller.ensureActive(); return stopped() ?: NativeLiveSpeechOutcome.Invalidated }
            val waitedForCompute = compute.waited || waitedForEngine
            caller.ensureActive()
            stopped()?.let { return it }
            return withContext(NonCancellable) {
                supervisorScope {
                    val invocation = AtomicReference<NativeLiveSpeechInvocation<T>?>(null)
                    val pending = async(Dispatchers.IO) {
                        caller.ensureActive()
                        stopped()?.let { return@async it }
                        checkNativeComputePrecondition(waitedForCompute)
                        caller.ensureActive()
                        stopped()?.let { return@async it }
                        val call = open { caller.isActive && stopped() == null }
                            ?: return@async NativeLiveSpeechOutcome.Invalidated
                        invocation.set(call)
                        try {
                            caller.ensureActive()
                            stopped()?.let { return@async it }
                            NativeLiveSpeechOutcome.Completed(call.infer())
                        } finally {
                            try { call.finish() }
                            finally { invocation.compareAndSet(call, null) }
                        }
                    }
                    // IO and NonCancellable keep this monitor alive while the caller is cancelled
                    // or native blocks. Repeated abort covers JNI resetting its flag on entry.
                    val monitor = launch(Dispatchers.IO) {
                        while (!pending.isCompleted) {
                            if (!caller.isActive || stopped() != null) {
                                runCatching { invocation.get()?.cancel() }
                            }
                            delay(pollMs)
                        }
                    }
                    try {
                        val result = pending.await()
                        caller.ensureActive()
                        stopped() ?: result
                    } finally {
                        monitor.cancelAndJoin()
                    }
                }
            }
        } finally { compute?.close(); mutex.unlock(owner) }
    }
}
