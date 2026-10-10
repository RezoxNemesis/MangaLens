package com.mangalens.core.search.embedding

import com.mangalens.core.compute.NativeComputePrecondition
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SemanticNativeRequesterTest {
    @Test fun heldProducerCancellationClearsUiContinuationAndCallbacks() = runTest {
        lateinit var mailbox: SemanticNativeRequester; var progress = 0
        val caller = async { suspendCancellableCoroutine<SemanticNativePass> { continuation ->
            mailbox = SemanticNativeRequester(continuation, { true }, { progress++ }, null)
            continuation.invokeOnCancellation { mailbox.retire() }
        } }
        runCurrent(); caller.cancel(); runCurrent()
        assertFalse(mailbox.current()); mailbox.progress(1); assertEquals(0, progress)
        val field = mailbox.javaClass.getDeclaredField("delivery").also { it.isAccessible = true }
        assertNull("A blocked independent producer must retain no retired UI owner", field.get(mailbox))
        mailbox.finish(SemanticNativePass(emptyList(), true), null)
        assertTrue(caller.isCancelled)
    }
    @Test fun changedGenerationNeverReceivesLateProgressOrResult() = runTest {
        lateinit var mailbox: SemanticNativeRequester; var current = true; var progress = 0
        val caller = async { suspendCancellableCoroutine<SemanticNativePass> { continuation -> mailbox = SemanticNativeRequester(continuation, { current }, { progress++ }, null) } }
        runCurrent(); current = false; mailbox.progress(1)
        assertEquals(0, progress); mailbox.finish(SemanticNativePass(emptyList(), true), null)
        runCurrent(); assertTrue(caller.isCancelled)
    }
    @Test fun preconditionIsRecheckedAfterAnAdmissionWait() = runTest {
        lateinit var mailbox: SemanticNativeRequester; var checks = 0; var sawWait = false
        val caller = async { suspendCancellableCoroutine<SemanticNativePass> { continuation -> mailbox = SemanticNativeRequester(continuation, { true }, {}, NativeComputePrecondition { waited -> checks++; sawWait = sawWait || waited }) } }
        runCurrent(); mailbox.validate(true); mailbox.validate(false)
        assertEquals(2, checks); assertTrue(sawWait)
        caller.cancel(); runCurrent()
        assertThrows(CancellationException::class.java) { runBlocking { mailbox.validate(false) } }
        assertEquals(2, checks)
    }
    @Test fun successfulDeliveryConsumesRequesterExactlyOnce() = runTest {
        lateinit var mailbox: SemanticNativeRequester
        val caller = async { suspendCancellableCoroutine<SemanticNativePass> { continuation -> mailbox = SemanticNativeRequester(continuation, { true }, {}, null) } }
        runCurrent(); val answer = SemanticNativePass(emptyList(), true); mailbox.finish(answer, null)
        assertSame(answer, caller.await()); assertFalse(mailbox.current()); mailbox.finish(null, IllegalStateException("late"))
    }
}
