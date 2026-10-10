package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Test

class OrezGenerationTraceTest {
    @Test fun deadlineSnapshotIncludesQueueAndNativeEntryWithoutRestartingTheAttemptClock() {
        var now = 0L
        val trace = OrezGenerationTrace { now }
        now = 2000
        trace.mark(OrezGenerationPhase.VERIFY_MODEL)
        now = 2500
        trace.mark(OrezGenerationPhase.WAIT_GENERATION_ADMISSION)
        now = 7000
        trace.mark(OrezGenerationPhase.NATIVE_GENERATE)
        now = 8000
        val timedOut = trace.snapshot("TIMED_OUT")
        assertEquals(8000, timedOut.elapsedMs)
        assertEquals("NATIVE_GENERATE", timedOut.phase)
        assertEquals(2000L, timedOut.phaseMs["WAIT_OPERATION"])
        assertEquals(4500L, timedOut.phaseMs["WAIT_GENERATION_ADMISSION"])
        assertEquals(1000L, timedOut.phaseMs["NATIVE_GENERATE"])
        now = 8050
        val returned = trace.snapshot("NATIVE_RETURN_CANCELLED")
        assertEquals(8050, returned.elapsedMs)
        assertEquals(8000, timedOut.elapsedMs)
        assertNull(timedOut.formattedInputSha256)
    }

    @Test fun separateOwnerTraceCannotBorrowAnEarlierPromptHash() {
        var now = 0L
        val first = OrezGenerationTrace { now }
        first.captureInput(OrezLocalizationProfile.formattedPrompt("SOURCE: Mina"))
        now = 10
        val second = OrezGenerationTrace { now }
        assertNull(second.snapshot("WAITING").formattedInputSha256)
        assertNotNull(first.snapshot("WAITING").formattedInputSha256)
    }
}
