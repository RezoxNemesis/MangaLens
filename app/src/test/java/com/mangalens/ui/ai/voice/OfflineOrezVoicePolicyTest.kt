package com.mangalens.ui.ai.voice

import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN. These prove contracts, not microphone/native device performance. */
class OfflineOrezVoicePolicyTest {
    @Test fun installedTinyRequiresExactFullPinNotMerelySmallSize() {
        assertTrue(OfflineOrezVoicePolicy.acceptsModel(77_691_713L,
            "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"))
        assertFalse(OfflineOrezVoicePolicy.acceptsModel(77_691_712L, OfflineOrezVoicePolicy.TINY_SHA256))
        assertFalse(OfflineOrezVoicePolicy.acceptsModel(77_691_713L, "a".repeat(64)))
        assertFalse(OfflineOrezVoicePolicy.acceptsModel(150_000_000L, OfflineOrezVoicePolicy.TINY_SHA256))
    }
    @Test fun transcriptNeverInventsTextOrConvertsTheOriginalLanguage() {
        assertNull(OfflineOrezVoicePolicy.transcript(listOf(" ", "")))
        assertEquals("नमस्ते Aiko 42", OfflineOrezVoicePolicy.transcript(listOf(" नमस्ते ", "Aiko 42")))
        assertNull(OfflineOrezVoicePolicy.transcript(listOf("x".repeat(4_001))))
    }
    @Test fun pcmIsMono16kBoundedToFifteenSecondsAndStopsAtCapacity() {
        val audio = BoundedVoicePcm()
        val block = ShortArray(1_024) { if (it % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE }
        while (!audio.full) audio.append(block, block.size)
        assertEquals(240_000, audio.size)
        audio.append(block, block.size)
        assertEquals(240_000, audio.size)
        val samples = audio.snapshot()
        assertEquals(-1f, samples[0], 0f)
        assertEquals(32_767f / 32_768f, samples[1], 0f)
        audio.clear()
        assertTrue(samples.all { it == 0f })
        assertEquals(0, audio.size)
    }
    @Test fun returningToSameDraftDoesNotRevalidateAnOldRecording() {
        val capture = VoiceDraftCapture("route-G1", 3L, "ask", 7L)
        assertTrue(capture.matches("route-G1", 3L, "ask", 7L, resumed = true))
        assertFalse(capture.matches("route-G1", 5L, "ask", 7L, resumed = true))
        assertFalse(capture.matches("route-G2", 3L, "ask", 7L, resumed = true))
        assertFalse(capture.matches("route-G1", 3L, "ask", 8L, resumed = true))
        assertFalse(capture.matches("route-G1", 3L, "ask", 7L, resumed = false))
    }
    @Test fun acceptedSpeechPreservesTheCapturedDraftAndRefusesOversizeInsertion() {
        assertEquals("Research Aiko 42", OfflineOrezVoicePolicy.mergeDraft("Research", "Aiko 42"))
        assertEquals("Research\nAiko", OfflineOrezVoicePolicy.mergeDraft("Research\n", "Aiko"))
        assertNull(OfflineOrezVoicePolicy.mergeDraft("x".repeat(8_000), "one more"))
    }
    @Test fun outputIncludesOnlyOptedInInstalledOfflineVoices() {
        val local = OfflineVoiceChoice("local-hi", "hi-IN", false)
        val remote = OfflineVoiceChoice("remote-hi", "hi-IN", true)
        assertEquals(listOf(local), OfflineOrezVoicePolicy.offlineVoices(listOf(remote, local)))
        assertTrue(OfflineOrezVoicePolicy.offlineVoices(listOf(remote)).isEmpty())
    }
    @Test fun speechTextIsExplicitBoundedAndExcludesMetadataSections() {
        assertEquals("Hello", OfflineOrezVoicePolicy.spokenText("Hello\n\nSources:\nhttps://example.org", 4_000))
        assertNull(OfflineOrezVoicePolicy.spokenText(" ", 4_000))
        assertNull(OfflineOrezVoicePolicy.spokenText("x".repeat(4_001), 4_000))
    }
}
