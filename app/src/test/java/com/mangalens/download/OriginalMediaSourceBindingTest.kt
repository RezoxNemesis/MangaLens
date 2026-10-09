package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test

class OriginalMediaSourceBindingTest {
    private val tuple = ResolvedMediaLink("https://video.fixture.invalid/selected?synthetic=one", "video/webm", "Youtube",
        headers = mapOf("Cookie" to "synthetic-video=one"), audioUrl = "https://audio.fixture.invalid/selected",
        audioHeaders = mapOf("Cookie" to "synthetic-audio=two"), expectedDurationUs = 8_000_000,
        originalSelection = OriginalMediaSelection(videoCodec = "vp9", audioCodec = "opus"))

    @Test fun savedCompleteTupleCannotAttachToNewVideoOrDifferentSignature() {
        for (newVideo in listOf("https://video.fixture.invalid/new-source",
            "https://video.fixture.invalid/selected?synthetic=two")) {
            val failure = runCatching { requireBoundMediaSource(newVideo, tuple) }.exceptionOrNull()
            assertTrue("New video must not inherit old audio/headers/duration/codec facts", failure is IllegalStateException)
            assertFalse(failure!!.message.orEmpty().contains("https://"))
            assertFalse(failure.message.orEmpty().contains("synthetic"))
        }
    }

    @Test fun acceptedSourceKeepsOneCompleteSnapshotAndLegacyAbsenceStillWorks() {
        assertSame(tuple, requireBoundMediaSource(tuple.url, tuple))
        assertNull(requireBoundMediaSource("https://fixture.invalid/legacy.mp4", null, receiptRequired = false))
    }

    @Test fun aMissingResolvedPairCannotBecomeAnApparentlySilentVideo() {
        val failure = runCatching { requireBoundMediaSource(tuple.url, null) }.exceptionOrNull()
        assertTrue("A resolved transfer without its captured audio/header receipt must fail closed",
            failure is IllegalStateException)
        assertFalse(failure!!.message.orEmpty().contains("https://"))
    }

    @Test fun onlyAnUnmarkedLegacyRowMayRequestWholeSourceShapeProofWithoutAReceipt() {
        val legacy = DownloadEntity("legacy", tuple.url, "Fixture", "video/webm")
        assertFalse(legacy.requiresBoundMediaReceipt())
        for (resolved in listOf(legacy.copy(provider = "Youtube"),
            legacy.copy(sourcePageUrl = "https://fixture.invalid/source"),
            legacy.copy(requestedHeight = DownloadQuality.BEST.height))) {
            assertTrue(resolved.requiresBoundMediaReceipt())
        }
    }
}
