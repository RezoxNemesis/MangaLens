package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test

class OriginalMediaCompletionPolicyTest {
    @Test fun intentionallySilentSourceCannotClaimVerifiedAudio() {
        val note = OriginalMediaCompletionPolicy.note(720, 10_000, null, true, false)
        assertFalse(note.contains("both track tails"))
        assertTrue(note.contains("no audio track"))
    }
    @Test fun completionCannotInventFullProviderHighestAccess() {
        val note = OriginalMediaCompletionPolicy.note(1080, 10_000,
            OriginalMediaSelection(maximumReportedHeight = 2160, client = "ios"), false)
        assertTrue(note.contains("original encoded streams"))
        assertTrue(note.contains("fallback extractor client"))
        assertTrue(note.contains("higher representation"))
        assertTrue(note.contains("accessibility is unverified"))
        assertTrue(note.contains("no compatible decoder"))
        assertFalse(note.contains("highest accessible"))
    }

    @Test fun explicitCeilingAndVerifiedOriginalFactsStayReadable() {
        val note = OriginalMediaCompletionPolicy.note(720, 1080, OriginalMediaSelection(client = "default"), true)
        assertTrue(note.contains("720p"))
        assertTrue(note.contains("below the 1080p ceiling"))
        assertFalse(note.contains("fallback"))
        assertFalse(note.contains("no compatible decoder"))
    }
}
