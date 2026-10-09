package com.mangalens.core.compute

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only cumulative module-owned memory releases belong here; feature requests keep their own fences. */
internal class NativeComputeMemoryRelease(
    private val admission: NativeComputeAdmission = NativeComputeAdmission.shared,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onFailure: (Throwable) -> Unit = { Log.w("MangaLens", "Native memory cleanup failed", it) }
) {
    // Each module callback drains its cumulative pending owners. Conflation loses no owner's close.
    private val pending = Channel<() -> Unit>(Channel.CONFLATED)
    init {
        scope.launch {
            for (release in pending) withContext(NonCancellable) {
                var lease: NativeComputeAdmission.Lease? = null
                try {
                    while (lease == null) {
                        try {
                            // FIFO with live requests, ahead of background; future live cannot starve cleanup.
                            lease = admission.acquire(NativeComputeAdmission.Priority.LIVE, ResourceWorkKind.CLEANUP) { true }
                        } catch (_: IllegalStateException) {
                            // A full feature queue must not drop accepted model cleanup or block Main.
                            delay(50)
                        }
                    }
                    // Cleanup intentionally has no source/generation precondition, and finishes on real return.
                    release()
                } catch (failure: Exception) {
                    runCatching { onFailure(failure) }
                } finally { lease?.close() }
            }
        }
    }

    fun schedule(release: () -> Unit) { check(pending.trySend(release).isSuccess) }

    companion object { val shared = NativeComputeMemoryRelease() }
}
