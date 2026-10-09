package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test

class OriginalMediaTailTest {
    private fun audio(endUs: Long, packetDurationUs: Long = 21_333) = VerifiedOriginalMedia(
        listOf(OriginalMediaTrack(0, "audio/mp4a-latm", "aac", durationUs = 8_000_000)),
        mapOf(0 to EncodedTrackAudit(300, 12345, endUs, "fixture", -21_333, packetDurationUs)))

    @Test fun nearTailTruncationCannotClaimACompleteOriginalAudioTrack() {
        val failure = runCatching { OriginalMediaProbe.verifyTail(audio(6_040_000), 8_000_000) }.exceptionOrNull()
        assertTrue("Almost two missing seconds from eight-second audio must fail original-tail admission",
            failure is IllegalStateException)
    }

    @Test fun containerTickRoundingDoesNotRejectAnOtherwiseCompleteTail() {
        OriginalMediaProbe.verifyTail(audio(7_998_000), 8_000_000)
        OriginalMediaProbe.verifyTail(audio(8_000_000), 8_000_000)
    }

    @Test fun lowFrameRatePacketAllowanceIsBoundedAndCannotRestoreTheAdaptiveTwoSecondFloor() {
        OriginalMediaProbe.verifyTail(audio(7_916_667, 83_333), 8_000_000)
        assertTrue(runCatching { OriginalMediaProbe.verifyTail(audio(6_040_000, 2_000_000), 8_000_000) }.isFailure)
        assertTrue(runCatching { OriginalMediaProbe.verifyTail(audio(7_749_000, 2_000_000), 8_000_000) }.isFailure)
    }
}
