package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN: synthetic original packet audits expose nonzero timestamp tail masking. */
class OriginalHlsObservedSpanTest {
    private val track = OriginalMediaTrack(0, "video/avc", "h264", 1920, 1080, durationUs = 4_000_000)
    private fun media(first: Long, end: Long, packet: Long = 40_000) = VerifiedOriginalMedia(listOf(track),
        mapOf(0 to EncodedTrackAudit(100, 1000, end, "synthetic", first, packet)))
    @Test fun nonzeroDecodeTimeCannotConcealThreeMissingSeconds() {
        val short = media(100_000_000, 101_000_000)
        OriginalMediaProbe.verifyTail(short, 4_000_000)
        assertTrue(runCatching { OriginalMediaProbe.verifyObservedSpan(short, track, 4_000_000) }.isFailure)
    }
    @Test fun fullSpanRetainsNonzeroTimestampAndSameExistingPacketTolerance() {
        OriginalMediaProbe.verifyObservedSpan(media(100_000_000, 104_000_000), track, 4_000_000)
        OriginalMediaProbe.verifyObservedSpan(media(100_000_000, 103_998_000), track, 4_000_000)
        assertTrue(runCatching { OriginalMediaProbe.verifyObservedSpan(media(100_000_000, 103_749_000, 2_000_000), track, 4_000_000) }.isFailure)
    }
    @Test fun arithmeticExtremesUseExactDifferenceAndInvalidDurationFailsClosed() {
        assertTrue(runCatching { OriginalMediaProbe.verifyObservedSpan(media(Long.MIN_VALUE, Long.MAX_VALUE), track, 4_000_000) }.isFailure)
        for (expected in listOf(0L, -1L, Long.MAX_VALUE)) assertTrue(runCatching { OriginalMediaProbe.verifyObservedSpan(media(0, 4_000_000), track, expected) }.isFailure)
        assertTrue(runCatching { OriginalMediaProbe.verifyObservedSpan(media(4_000_000, 0), track, 4_000_000) }.isFailure)
    }
    @Test fun absentHlsSpanDoesNotChangeExistingDashOrCombinedSourceGate() {
        OriginalMediaProbe.verifyObservedSpan(media(100_000_000, 101_000_000), track, null)
        assertTrue(runCatching { OriginalMediaProbe.verifyTail(media(0, 1_000_000), 4_000_000) }.isFailure)
    }
}
