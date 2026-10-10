package com.mangalens.core.translation.inpainting

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Authored UNRUN scheduling ownership controls. Deferred producers are not actual JNI engine runs. */
class LaMaNativeReturnTest {
    @Test fun cancellationRequestsTerminationButCannotFinishUntilTheRealProducerReturns() = runBlocking {
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val terminate = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
        val worker = producerScope.async { entered.complete(Unit); release.await(); "pixels" }
        val request = async { started.complete(Unit); awaitLaMaNativeReturn(worker, { terminate.complete(Unit) }, { null }) }
        try {
            withTimeout(2000) { started.await(); entered.await() }; request.cancel(); withTimeout(2000) { terminate.await() }
            assertFalse(request.isCompleted); assertFalse(worker.isCompleted)
            release.complete(Unit); withTimeout(2000) { request.join() }; assertTrue(worker.isCompleted)
        } finally { release.complete(Unit); withContext(NonCancellable) { request.join(); worker.join() }; producerScope.cancel() }
    }
    @Test fun failedTerminationRequestStillWaitsForActualReturn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val release = CompletableDeferred<Unit>(); val attempted = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
        val worker = scope.async { release.await(); 1 }
        val request = async { started.complete(Unit); awaitLaMaNativeReturn(worker, { attempted.complete(Unit); throw IllegalStateException("terminate unavailable") }, { null }) }
        try {
            withTimeout(2000) { started.await() }; request.cancel(); withTimeout(2000) { attempted.await() }; assertFalse(request.isCompleted)
            release.complete(Unit); withTimeout(2000) { request.join() }
        } finally { release.complete(Unit); withContext(NonCancellable) { request.join(); worker.join() }; scope.cancel() }
    }
    @Test fun terminationErrorCannotBypassTheActualProducerReturn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>(); val attempted = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
        val worker = scope.async { release.await(); 1 }
        val request = async {
            started.complete(Unit)
            awaitLaMaNativeReturn(worker, { attempted.complete(Unit); throw AssertionError("termination failed") }, { null })
        }
        try {
            withTimeout(2000) { started.await() }; request.cancel(); withTimeout(2000) { attempted.await() }
            assertFalse(request.isCompleted); assertFalse(worker.isCompleted)
            release.complete(Unit); withTimeout(2000) { request.join() }; assertTrue(worker.isCompleted)
        } finally { release.complete(Unit); withContext(NonCancellable) { request.join(); worker.join() }; scope.cancel() }
    }
    @Test fun actualCleanupFailureRetainsItsOwnerInsteadOfBeingHiddenByCancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val release = CompletableDeferred<Unit>(); val attempted = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
        val retained = Any(); val failure = LaMaNativeCloseUnproven(listOf(retained), retained, IllegalStateException("close"))
        val unsafe = AtomicReference<LaMaNativeCloseUnproven?>(); val observed = CompletableDeferred<Throwable>()
        val worker = scope.async { release.await(); unsafe.set(failure); throw failure }
        val request = launch {
            started.complete(Unit)
            try { awaitLaMaNativeReturn(worker, { attempted.complete(Unit) }, unsafe::get) }
            catch (problem: Throwable) { observed.complete(problem) }
        }
        try {
            withTimeout(2000) { started.await() }; request.cancel(); withTimeout(2000) { attempted.await() }; release.complete(Unit)
            assertSame(failure, withTimeout(2000) { observed.await() }); assertSame(retained, failure.retained.single())
        } finally { release.complete(Unit); withContext(NonCancellable) { request.join(); worker.join() }; scope.cancel() }
    }
}
