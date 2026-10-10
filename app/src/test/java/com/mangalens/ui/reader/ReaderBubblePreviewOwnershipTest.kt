package com.mangalens.ui.reader

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

/** Real held FD lifecycle; no Android bitmap/Compose disposal scheduling claim. */
class ReaderBubblePreviewOwnershipTest {
    @Test fun unclaimedDismissalClosesTheHeldOriginalDescriptorOnce() = resource { file, input ->
        val ownership = ReaderBubblePreviewOwnership(input)
        ownership.abandon(); ownership.abandon(); ownership.disposeUi()
        assertFalse(input.channel.isOpen); assertNull(ownership.claim()); assertTrue(file.isFile)
    }
    @Test fun displayedPreviewRemainsUsableUntilUiDisposalAfterDismissal() = resource { _, input ->
        val ownership = ReaderBubblePreviewOwnership(input)
        assertSame(input, ownership.claim())
        ownership.abandon()
        assertTrue(input.channel.isOpen); assertEquals(83, input.read())
        ownership.disposeUi(); ownership.disposeUi(); ownership.abandon()
        assertFalse(input.channel.isOpen)
    }
    @Test fun aPreviewCannotBeClaimedByTwoComposedImages() = resource { _, input ->
        val ownership = ReaderBubblePreviewOwnership(input)
        assertSame(input, ownership.claim()); assertNull(ownership.claim())
        ownership.disposeUi(); assertNull(ownership.claim()); assertFalse(input.channel.isOpen)
    }
    @Test fun earlyUiDisposalDoesNotStealAnUnclaimedResourceFromItsController() = resource { _, input ->
        val ownership = ReaderBubblePreviewOwnership(input)
        ownership.disposeUi(); assertTrue(input.channel.isOpen)
        ownership.abandon(); assertFalse(input.channel.isOpen)
    }
    private fun resource(test: (File, FileInputStream) -> Unit) {
        val file = Files.createTempFile("bubble-preview-ownership", ".bin").toFile().apply { writeText("Saved original") }
        FileInputStream(file).use { input -> try { test(file, input) } finally { file.delete() } }
    }
}
