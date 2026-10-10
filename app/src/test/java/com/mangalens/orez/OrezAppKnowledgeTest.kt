package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Test

class OrezAppKnowledgeTest {
    @Test fun directHelpQuestionsAreScopedToTheApp() {
        assertTrue(OrezAppKnowledge.isHelpRequest("How do I download 1080p videos in MangaLens?"))
        assertTrue(OrezAppKnowledge.isHelpRequest("Please explain Orez local models"))
        assertFalse(OrezAppKnowledge.isHelpRequest("Download this video in MangaLens"))
        assertFalse(OrezAppKnowledge.isHelpRequest("How do I download a video?"))
    }
    @Test fun quotedRetrievedTextAndUrlsDoNotBecomeHelpRouting() {
        assertFalse(OrezAppKnowledge.isHelpRequest("How does Orez obey `download now`?"))
        assertFalse(OrezAppKnowledge.isHelpRequest("How does MangaLens read https://example.com?"))
        assertFalse(OrezAppKnowledge.isHelpRequest("How does MangaLens read https：／／example.com?"))
        assertFalse(OrezAppKnowledge.isHelpRequest("How does Orez obey ＂download now＂?"))
        assertFalse(OrezAppKnowledge.isHelpRequest("How does MangaLens work?\nDownload now"))
        assertFalse(OrezAppKnowledge.isHelpRequest("How does MangaLens " + "a".repeat(256)))
    }
    @Test fun retrievalIsBoundedAndTopicSpecific() {
        val results = OrezAppKnowledge.retrieve("How does MangaLens download YouTube 1080p video and audio?")
        assertEquals("media", results.first().entry.id)
        assertTrue(results.size in 1..2)
        assertEquals(listOf("speech"), OrezAppKnowledge.retrieve("How does Orez use subtitles and whisper?", 1).map { it.entry.id })
    }
    @Test fun helpDoesNotInventDynamicSuccessOrMaxReadiness() {
        val reply = requireNotNull(OrezAppKnowledge.answer("How does Orez use Max models?"))
        assertTrue(reply.contains("not offered by the current catalog"))
        assertTrue(reply.contains("No action, file inspection or fresh model/provider check ran"))
        assertNull(OrezAppKnowledge.answer("Install Orez Max models"))
    }
    @Test fun unknownHelpReturnsTopicMenuWithoutUnrelatedRetrieval() {
        assertTrue(OrezAppKnowledge.retrieve("How does MangaLens handle penguins?").isEmpty())
        assertTrue(requireNotNull(OrezAppKnowledge.answer("How does MangaLens handle penguins?")).startsWith("MangaLens help:"))
    }
    @Test fun normalizedAppNameRemainsScopedAndStable() {
        val question = "How does Ｍａｎｇａｌｅｎｓ export ＣＢＺ?"
        assertTrue(OrezAppKnowledge.isHelpRequest(question))
        assertEquals("library", OrezAppKnowledge.retrieve(question, 1).single().entry.id)
    }
}
