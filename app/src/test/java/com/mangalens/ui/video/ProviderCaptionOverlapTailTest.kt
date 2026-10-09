package com.mangalens.ui.video

import com.mangalens.orez.agent.*
import org.junit.Assert.*
import org.junit.Test

class ProviderCaptionOverlapTailTest {
    @Test fun completeOverlappingDocumentAcrossBatchesUsesItsActualMaximumCueEnd() = CaptionFixture().use { f ->
        val body = buildString {
            append("WEBVTT\n\n")
            for (index in 0..64) {
                val start = index * 100L
                val end = if (index == 0) 20_000L else start + 1000
                append(stamp(start)).append(" --> ").append(stamp(end)).append("\nDo not open the door.\n\n")
            }
        }
        val document = ProviderCaptionParser.parse(body.toByteArray(), f.track.format, f.inventory.expectedDurationMs)
        val store = f.store(); val task = store.start(f.source, f.config, ownerRequestId = "orez-overlap-proof")
        val receipt = providerReceipt(task, f.track, document)
        val saved = requireNotNull(store.checkpointProviderDocument(task.id, task.generation, receipt,
            providerCaptionWindows(document, f.track.language)))
        assertEquals(2, saved.windows.size)
        assertTrue(saved.windows.first().endMs > saved.windows.last().endMs)
        saved.windows.forEach { window -> window.sourceCues.indices.forEach { index ->
            assertTrue(store.checkpointProviderTarget(task.id, task.generation, window.index, receipt,
                SubtitleTranslatedCue(index, window.sourceCues[index].text)))
        } }
        val completed = requireNotNull(store.finish(task.id, task.generation))
        assertEquals("A genuine overlapping provider document lost its earlier maximum end", receipt.lastEndMs, completed.processedMs)
        assertEquals(65, completed.cues.size)
        val descriptor = OrezMediaSelection(f.source.source.uri, f.source.source.cacheKey, f.source.source.label,
            f.source.source.headers, resolutionId = f.source.source.sourceResolutionId,
            expectedDurationUs = 60_000_000, providerCaptions = f.inventory)
        OrezSubtitleTools.verifyCompleted(OrezSubtitleNativeEvidence.receipt(completed, descriptor.sourceId, f.directory, descriptor))
    }
    private fun stamp(ms: Long) = "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
}
