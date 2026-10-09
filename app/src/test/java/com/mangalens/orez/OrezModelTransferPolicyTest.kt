package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OrezModelTransferPolicyTest {
    @Test fun ignoredRangeRestartsOnlyForAValidFullResponse() {
        assertEquals(0L, OrezModelTransferPolicy.responseOffset(200, 40L, null, 100L, 100L))
        rejected { OrezModelTransferPolicy.responseOffset(200, 40L, null, 60L, 100L) }
    }
    @Test fun resumeChecksRangeStartEndTotalAndAnnouncedBodySize() {
        assertEquals(40L, OrezModelTransferPolicy.responseOffset(206, 40L, "bytes 40-99/100", 60L, 100L))
        rejected { OrezModelTransferPolicy.responseOffset(206, 40L, "bytes 41-99/100", 59L, 100L) }
        rejected { OrezModelTransferPolicy.responseOffset(206, 40L, "bytes 40-109/110", 70L, 100L) }
        rejected { OrezModelTransferPolicy.responseOffset(206, 40L, "bytes 40-99/100", 61L, 100L) }
        rejected { OrezModelTransferPolicy.responseOffset(206, 40L, "bytes 40-20/100", -1L, 100L) }
        rejected { OrezModelTransferPolicy.responseOffset(206, 40L, null, 60L, 100L) }
    }
    @Test fun chunkedTransfersCanResumeFromAValidatedRange() {
        assertEquals(0L, OrezModelTransferPolicy.responseOffset(200, 0L, null, -1L, 100L))
        assertEquals(40L, OrezModelTransferPolicy.responseOffset(206, 40L, "bytes 40-99/100", -1L, 100L))
        assertEquals(40L, OrezModelTransferPolicy.responseOffset(206, 40L, "bytes 40-59/100", 20L, 100L))
    }
    @Test fun malformedOrUnsuccessfulResponsesPreserveSavedPartial() {
        rejected { OrezModelTransferPolicy.responseOffset(403, 40L, null, 60L, 100L) }
        rejected { OrezModelTransferPolicy.responseOffset(206, 40L, "bytes 999999999999999999999-99/100", -1L, 100L) }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid response must not append to the saved partial") }
        catch (_: IOException) { }
    }
}
