package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class OriginalPacketAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun packet(index: Int, pts: String, hash: String = "a", size: Int = 50) =
        "stream_index=$index|pts_time=$pts|duration_time=0.040000|size=$size|data_hash=SHA256:${hash.repeat(64)}"

    private fun timedTrack(index: Int, rows: List<String>): EncodedTrackAudit =
        OriginalPacketAudit(setOf(index), timingReceipts = mapOf(index to temporary.newFile())).use { audit ->
            rows.forEach(audit::add)
            audit.finish().getValue(index)
        }

    @Test fun encodedPayloadFingerprintIgnoresPermittedTimestampRebase() {
        val first = OriginalPacketAudit(setOf(0))
        val rebased = OriginalPacketAudit(setOf(4))
        first.add(packet(0, "0.000000")); first.add(packet(0, "1.000000", "b"))
        rebased.add(packet(4, "0.007000")); rebased.add(packet(4, "1.007000", "b"))
        assertEquals(first.finish().getValue(0).payloadFingerprint, rebased.finish().getValue(4).payloadFingerprint)
        assertEquals(2L, first.finish().getValue(0).samples)
        assertEquals(100L, first.finish().getValue(0).encodedBytes)
    }

    @Test fun changedPayloadMissingSamplesAndReorderedSamplesCannotClaimOriginal() {
        fun digest(rows: List<String>): EncodedTrackAudit {
            val audit = OriginalPacketAudit(setOf(0)); rows.forEach(audit::add)
            return audit.finish().getValue(0)
        }
        val original = digest(listOf(packet(0, "0", "a"), packet(0, "1", "b")))
        for (changed in listOf(
            digest(listOf(packet(0, "0", "a"), packet(0, "1", "c"))),
            digest(listOf(packet(0, "0", "a"))),
            digest(listOf(packet(0, "0", "b"), packet(0, "1", "a")))
        )) assertFalse(original.hasSamePayload(changed))
    }

    @Test fun tailsAndHashesStayIndependentForVideoAndAudio() {
        val audit = OriginalPacketAudit(setOf(0, 1))
        audit.add(packet(0, "59.960000", "a")); audit.add(packet(1, "1.000000", "b"))
        val result = audit.finish()
        assertEquals(60_000_000L, result.getValue(0).lastSampleEndUs)
        assertEquals(1_040_000L, result.getValue(1).lastSampleEndUs)
        assertTrue(MediaCompletenessPolicy.hasCompleteTail(60_000_000, result.getValue(0).lastSampleEndUs))
        assertFalse(MediaCompletenessPolicy.hasCompleteTail(60_000_000, result.getValue(1).lastSampleEndUs))
    }

    @Test fun negativePrimingPacketsAreRetainedAndBFramePtsCanBeOutOfOrder() {
        val audit = OriginalPacketAudit(setOf(0))
        audit.add(packet(0, "-0.007000")); audit.add(packet(0, "1.000000")); audit.add(packet(0, "0.500000"))
        val result = audit.finish().getValue(0)
        assertEquals(3L, result.samples)
        assertEquals(1_040_000L, result.lastSampleEndUs)
    }

    @Test fun invalidOrHashlessPacketsFailVerificationInsteadOfBeingCounted() {
        for (line in listOf(packet(0, "NaN"), packet(0, "1e30"), packet(0, "1", size = 0),
            packet(0, "1").replace("data_hash=SHA256:", "untrusted_hash="), packet(9, "1"))) {
            assertTrue(runCatching { OriginalPacketAudit(setOf(0)).add(line) }.isFailure)
        }
        assertTrue(runCatching { OriginalPacketAudit(setOf(0)).finish() }.isFailure)
    }

    @Test fun uniformContainerRebaseRetainsAudioVideoTimingButIndependentShiftDoesNot() {
        fun track(index: Int, first: String, last: String): EncodedTrackAudit {
            return timedTrack(index, listOf(packet(index, first), packet(index, last, "b")))
        }
        val video = track(0, "0.000000", "7.960000")
        val audio = track(1, "-0.007000", "7.953000")
        assertTrue(OriginalMediaProbe.retainsTrackTiming(video, track(3, "0.007000", "7.967000"),
            audio, track(4, "0.000000", "7.960000")))
        assertFalse(OriginalMediaProbe.retainsTrackTiming(video, track(3, "0.007000", "7.967000"),
            audio, track(4, "0.250000", "8.210000")))
        assertFalse(OriginalMediaProbe.retainsTrackTiming(video, track(3, "0.007000", "8.967000"),
            audio, track(4, "0.000000", "7.960000")))
    }

    @Test fun unchangedEndpointsCannotHideAnInteriorPacketTimestampOrDurationChange() {
        fun track(middlePts: String = "4.000000", middleDuration: String = "0.040000"): EncodedTrackAudit {
            return timedTrack(0, listOf(packet(0, "0.000000", "a"),
                packet(0, middlePts, "b").replace("duration_time=0.040000", "duration_time=$middleDuration"),
                packet(0, "7.960000", "c")))
        }
        val original = track()
        for (changed in listOf(track(middlePts = "6.000000"), track(middleDuration = "2.040000"))) {
            assertTrue(original.hasSamePayload(changed))
            assertEquals(original.firstSampleUs, changed.firstSampleUs)
            assertEquals(original.lastSampleEndUs, changed.lastSampleEndUs)
            assertFalse("Interior packet timing must be verified even when both endpoints agree",
                OriginalMediaProbe.retainsTrackTiming(original, changed, original, original))
        }
    }

    @Test fun everyPacketMayShareARebaseWithTickRoundingIncludingOutOfOrderPts() {
        val original = timedTrack(0, listOf(packet(0, "-0.007000", "a"), packet(0, "1.000000", "b"),
            packet(0, "0.500000", "c"), packet(0, "7.960000", "d")))
        val rebased = timedTrack(4, listOf(packet(4, "0.000000", "a"), packet(4, "1.008000", "b"),
            packet(4, "0.506000", "c"), packet(4, "7.967000", "d")))
        assertTrue(original.hasSamePayload(rebased))
        assertTrue(OriginalMediaProbe.retainsTrackTiming(original, rebased, original, rebased))
    }

    @Test fun missingOrTruncatedTimingReceiptsCannotPassAndCancellationStillPropagates() {
        val original = timedTrack(0, listOf(packet(0, "0", "a"), packet(0, "1", "b")))
        val result = timedTrack(4, listOf(packet(4, "0", "a"), packet(4, "1", "b")))
        assertFalse(OriginalMediaProbe.retainsTrackTiming(original.copy(timingReceipt = null), result, original, result))
        val cancellation = InterruptedException("fixture cancellation")
        assertSame(cancellation, runCatching {
            OriginalMediaProbe.retainsTrackTiming(original, result, original, result) { throw cancellation }
        }.exceptionOrNull())
        val deadline = MediaResolutionTimeoutException(1)
        assertSame(deadline, runCatching {
            OriginalMediaProbe.retainsTrackTiming(original, result, original, result) { throw deadline }
        }.exceptionOrNull())
        result.timingReceipt!!.writeBytes(ByteArray(16))
        assertFalse(OriginalMediaProbe.retainsTrackTiming(original, result, original, result))
    }
}
