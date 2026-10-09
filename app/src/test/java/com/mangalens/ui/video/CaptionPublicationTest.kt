package com.mangalens.ui.video

import org.junit.Assert.*
import org.junit.Test

class CaptionPublicationTest {
    @Test fun returningFromThePickerCannotAttachToADifferentVideo() {
        val gate = CaptionPublication()
        val original = gate.capture(7L, "content://videos/first")
        assertFalse(gate.accepts(original, 8L, "content://videos/second"))
        assertFalse(gate.accepts(original, 7L, "content://videos/second"))
    }

    @Test fun newerSelectionSupersedesAStillRunningTranslationOnTheSameVideo() {
        val gate = CaptionPublication()
        val old = gate.capture(7L, "content://videos/first")
        val replacement = gate.capture(7L, "content://videos/first")
        assertFalse(gate.accepts(old, 7L, "content://videos/first"))
        assertTrue(gate.accepts(replacement, 7L, "content://videos/first"))
    }

    @Test fun headersOrSignedSourceRefreshInvalidateACapturedVideoRevision() {
        val gate = CaptionPublication()
        val captured = gate.capture(7L, "https://media.invalid/video")
        assertFalse(gate.accepts(captured, 8L, "https://media.invalid/video"))
    }

    @Test fun cancellationIsTerminalForThatSelection() {
        val gate = CaptionPublication()
        val captured = gate.capture(7L, "content://videos/first")
        gate.invalidate()
        assertFalse(gate.accepts(captured, 7L, "content://videos/first"))
    }

    @Test fun restoredPickerRequestRetainsItsCapturedSelectionAndRevision() {
        val gate = CaptionPublication()
        val captured = gate.capture(7L, "content://videos/first")
        val restored = requireNotNull(restoreCaptionTicket(captured.savedFields().toList()))
        assertEquals(captured, restored)
        assertTrue(gate.accepts(restored, 7L, "content://videos/first"))
        assertFalse(gate.accepts(restored, 8L, "content://videos/second"))
        gate.capture(7L, "content://videos/first")
        assertFalse(gate.accepts(restored, 7L, "content://videos/first"))
    }

    @Test fun malformedOrUnboundedSavedStateCannotCreateARequest() {
        for (fields in listOf(emptyList(), listOf("1", "7"), listOf("0", "7", "content://videos/first"),
            listOf("1", "-1", "content://videos/first"), listOf("1", "7", ""),
            listOf("1", "7", "x".repeat(8193)), listOf("9223372036854775808", "7", "content://videos/first"))) {
            assertNull(restoreCaptionTicket(fields))
        }
    }
}
