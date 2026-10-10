package com.mangalens.core.translation.memory

import com.mangalens.core.translation.*
import com.mangalens.orez.OrezModelPin
import org.junit.Assert.*
import org.junit.Test

class CapturedSeriesMemoryPacketTest {
    @Test fun lexicalProjectionUsesExplicitEarlierReceiptsAndOriginlessUserGlossary() {
        val packet = packet()
        val selected = packet.relevant(0, "Hello Jin, my friend.")
        assertEquals(mapOf("Jin" to "जिन"), selected.glossary)
        assertEquals(listOf("नमस्ते, मित्र।"), selected.priorDialogue.map { it.text })
        assertEquals("prior", selected.priorDialogue.single().source.chapterId)
    }

    @Test fun unknownOrFutureExplicitOrderCannotBecomeEarlierDialogue() {
        for (order in listOf<Int?>(null, 1, 0)) {
            val packet = packet(order)
            assertTrue(packet.relevant(0, "Hello Jin, my friend.").priorDialogue.isEmpty())
            assertEquals(mapOf("Jin" to "जिन"), packet.relevant(0, "Hello Jin, my friend.").glossary)
        }
    }

    @Test fun linkRevisionProfileChangeAndPersonalRevisionEachChangeRealPromptAndCacheIdentity() {
        val original = packet()
        val changed = listOf(packet(revision = 2), packet(preferred = "जिन वू"), packet(personal = "नमस्ते, साथी।"))
        val request = TranslationRefinementRequest(true, pinnedModel = OrezModelPin("fixture", "a".repeat(64), 100), inputProfileRevision = "orez-localization-v2")
        for (next in changed) {
            assertNotEquals(original.sha256, next.sha256)
            val before = inputs(original, request); val after = inputs(next, request)
            assertNotEquals(prompt(before, request), prompt(after, request))
            assertNotEquals(CapturedMemoryRefinementPolicy.cacheStyle("pinned-style", original, 0, "Hello Jin, friend.", "current"),
                CapturedMemoryRefinementPolicy.cacheStyle("pinned-style", next, 0, "Hello Jin, friend.", "current"))
        }
        assertNotEquals(CapturedMemoryRefinementPolicy.cacheStyle("style", original, 0, "Hello.", "one"),
            CapturedMemoryRefinementPolicy.cacheStyle("style", original, 1, "Hello.", "one"))
        assertNotEquals(CapturedMemoryRefinementPolicy.cacheStyle("style", original, 0, "Hello.", "one"),
            CapturedMemoryRefinementPolicy.cacheStyle("style", original, 0, "Hello.", "two"))
    }

    @Test fun boundedCodecRoundTripKeepsCapturedMemoryAndRejectsTamperedTargetOrNativeOwner() {
        val original = packet()
        val restored = CapturedSeriesMemoryPacket.fromJson(original.toJson())
        assertEquals(original, restored); assertEquals(original.sha256, restored.sha256)
        assertTrue(original.toJson().toString().toByteArray().size <= CapturedSeriesMemoryPacket.MAX_BYTES)
        val target = original.toJson().put("targetLanguage", "en")
        assertTrue(runCatching { CapturedSeriesMemoryPacket.fromJson(target) }.isFailure)
        val owner = original.toJson()
        owner.getJSONArray("chapters").getJSONObject(1).getJSONArray("bubbles").getJSONObject(0)
            .getJSONObject("receipt").put("ownerRequestId", "another-owner")
        assertTrue(runCatching { CapturedSeriesMemoryPacket.fromJson(owner) }.isFailure)
    }

    @Test fun oldPacketCannotObserveLaterMutationsOfCallerOwnedLists() {
        val aliases = mutableListOf("Jin Woo")
        val glossary = mutableListOf(SeriesGlossaryTerm("term", "Jin", "जिन", "hi", aliases = aliases))
        val profile = SeriesMemoryProfile("series", "Fixture", glossary)
        val snapshots = mutableListOf(current(2, 1), prior())
        val captured = CapturedSeriesMemoryPacket.capture("current", "d".repeat(64), "hi", "c".repeat(64), 0, profile, snapshots, false)
        val hash = captured.sha256
        aliases.clear(); glossary.clear(); snapshots.clear()
        assertEquals(hash, captured.sha256)
        assertEquals(mapOf("Jin" to "जिन", "Jin Woo" to "जिन"), captured.relevant(0, "Jin Woo, hello.").glossary)
    }

    @Test fun actualOptionalMemoryStaysWithinExistingPromptContextAndGlossaryBudgets() {
        val request = TranslationRefinementRequest(true, pinnedModel = OrezModelPin("fixture", "a".repeat(64), 100), inputProfileRevision = "orez-localization-v2")
        val packet = packet()
        val inputs = CapturedMemoryRefinementPolicy.inputs(packet, 0, "Hello Jin, friend.", "नमस्ते, मित्र।", "hi", request, "earlier ".repeat(560).take(4500))
        assertTrue(inputs.chapterContext.length <= 4500); assertTrue(inputs.glossary.size <= 16)
        assertTrue(TranslationRefinementPolicy.validInputs("Hello Jin, friend.", "नमस्ते, मित्र।", "hi", request.style,
            inputs.chapterContext, inputs.glossary, inputs.packetSha256))
        assertTrue(prompt(inputs, request).length <= 12000)
        assertTrue(prompt(inputs, request).contains("CAPTURED SERIES MEMORY SHA-256: ${packet.sha256}"))
    }

    @Test fun legacyNullPacketPreservesExactPreviousContextCacheKeyAndPromptText() {
        val request = TranslationRefinementRequest(false)
        val inputs = CapturedMemoryRefinementPolicy.inputs(null, 0, "Hello.", "नमस्ते।", "hi", request, "existing context")
        assertEquals("existing context", inputs.chapterContext); assertTrue(inputs.glossary.isEmpty()); assertNull(inputs.packetSha256)
        assertEquals("unchanged-style", CapturedMemoryRefinementPolicy.cacheStyle("unchanged-style", null, 0, "Hello.", "existing context"))
        assertEquals(TranslationRefinementPolicy.prompt("Hello.", "नमस्ते।", "hi", request.style, "existing context", emptyMap()),
            TranslationRefinementPolicy.prompt("Hello.", "नमस्ते।", "hi", request.style, inputs.chapterContext, inputs.glossary, inputs.packetSha256))
    }

    @Test fun romanTargetMemoryIsNotInventedAsHindiIntermediateEvidence() {
        val packet = CapturedSeriesMemoryPacket.capture("current", "d".repeat(64), "hi-latn", "c".repeat(64), 0,
            SeriesMemoryProfile("series", "Fixture", listOf(SeriesGlossaryTerm("term", "Jin", "Jin Woo", "hi-latn"))),
            listOf(current(2, 1)), false)
        assertEquals(mapOf("Jin" to "Jin Woo"), packet.relevant(0, "Hello Jin, friend.").glossary)
        val request = TranslationRefinementRequest(true, pinnedModel = OrezModelPin("fixture", "a".repeat(64), 100), inputProfileRevision = "orez-localization-v2")
        val inputs = CapturedMemoryRefinementPolicy.inputs(packet, 0, "Hello Jin, friend.", "नमस्ते, Jin।", "hi", request, "current actual context")
        assertTrue(inputs.glossary.isEmpty()); assertEquals("current actual context", inputs.chapterContext)
        assertTrue(inputs.targetProjectionOmitted)
        assertEquals(packet.sha256, inputs.packetSha256)
        val prompt = TranslationRefinementPolicy.prompt("Hello Jin, friend.", "नमस्ते, Jin।", "hi", request.style,
            inputs.chapterContext, inputs.glossary, inputs.packetSha256)
        assertFalse(prompt.contains("Jin => Jin Woo")); assertTrue(prompt.contains(packet.sha256))
    }

    @Test fun mismatchedTargetOmissionFitsAddedHashIntoAnExactlyFullPreviouslyValidPrompt() {
        val packet = CapturedSeriesMemoryPacket.capture("current", "d".repeat(64), "hi-latn", "c".repeat(64), 0,
            SeriesMemoryProfile("series", "Fixture"), listOf(current(2, 1)), false)
        val request = TranslationRefinementRequest(true, TranslationStyleProfile.custom("Preserve actual dialogue. ".repeat(60)), pinnedModel = OrezModelPin("fixture", "a".repeat(64), 100), inputProfileRevision = "orez-localization-v2")
        val source = "Hello Jin. ".repeat(400).take(3999)
        val draft = "नमस्ते। ".repeat(600).take(3999)
        // Empty context uses the template's explanatory placeholder; one real character fixes that shape exactly.
        val probePrompt = TranslationRefinementPolicy.prompt(source, draft, "hi", request.style, "X", emptyMap())
        val contextLength = 12000 - probePrompt.length + 1
        assertTrue(contextLength in 1..4500)
        val originalContext = "X".repeat(contextLength)
        assertTrue(TranslationRefinementPolicy.validInputs(source, draft, "hi", request.style, originalContext, emptyMap()))
        assertEquals(12000, TranslationRefinementPolicy.prompt(source, draft, "hi", request.style, originalContext, emptyMap()).length)
        val inputs = CapturedMemoryRefinementPolicy.inputs(packet, 0, source, draft, "hi", request, originalContext)
        assertTrue(inputs.targetProjectionOmitted); assertEquals(packet.sha256, inputs.packetSha256)
        assertTrue(inputs.glossary.isEmpty()); assertTrue(originalContext.startsWith(inputs.chapterContext))
        assertTrue(inputs.chapterContext.length < originalContext.length)
        assertTrue(TranslationRefinementPolicy.validInputs(source, draft, "hi", request.style, inputs.chapterContext, inputs.glossary, inputs.packetSha256))
        assertTrue(TranslationRefinementPolicy.prompt(source, draft, "hi", request.style, inputs.chapterContext, inputs.glossary, inputs.packetSha256).length <= 12000)
    }

    @Test fun missingOrUnknownCapturedProfileCannotAdmitMemoryAsProviderInput() {
        val packet = packet()
        for (revision in listOf<String?>(null, "orez-localization-v99")) {
            val request = TranslationRefinementRequest(true, pinnedModel = OrezModelPin("fixture", "a".repeat(64), 100), inputProfileRevision = revision)
            assertFalse(TranslationRefinementPolicy.generationReady(request))
            val inputs = CapturedMemoryRefinementPolicy.inputs(packet, 0, "Hello Jin, friend.", "नमस्ते, मित्र।", "hi", request, "actual context")
            assertTrue(inputs.glossary.isEmpty()); assertEquals("", inputs.chapterContext)
            assertEquals("Logical packet identity remains captured even when generation must restart", packet.sha256, inputs.packetSha256)
            assertFalse(TranslationRefinementPolicy.validCapturedInputs("Hello Jin, friend.", "नमस्ते, मित्र।", "hi", request,
                inputs.chapterContext, inputs.glossary, inputs.packetSha256))
        }
    }

    private fun packet(order: Int? = 2, revision: Long = 1, preferred: String = "जिन", personal: String = "नमस्ते, मित्र।") =
        CapturedSeriesMemoryPacket.capture("current", "d".repeat(64), "hi", "c".repeat(64), 0,
            SeriesMemoryProfile("series", "Fixture", listOf(SeriesGlossaryTerm("term", "Jin", preferred, "hi"))),
            listOf(current(order, revision), prior(personal)), false)
    private fun current(order: Int?, revision: Long) = MemoryChapterSnapshot("current", MemoryChapterAssociation("current", "series", order), emptyList(), false, revision)
    private fun prior(text: String = "नमस्ते, मित्र।"): MemoryChapterSnapshot {
        val receipt = MemoryPublicationReceipt(MemorySourceProof("prior", 0, "/fixture/chapters/source.png", "a".repeat(64), 1000, 400,
            MemoryRegionBounds(100, 100, 900, 300)), "task", "generation", "hi", "c".repeat(64), "Hello, friend.", "नमस्ते।",
            "/fixture/chapter_translations/task/output.png", "b".repeat(64), "series", 1, "owner", 1, 1)
        val revision = MemoryCorrectionRevision(1, MemoryCorrectionEdit(translated = text), 100)
        return MemoryChapterSnapshot("prior", MemoryChapterAssociation("prior", "series", 1),
            listOf(MemoryIndexedBubble(receipt, MemoryCorrection(receipt, listOf(revision)), 1)), false, 1)
    }
    private fun inputs(packet: CapturedSeriesMemoryPacket, request: TranslationRefinementRequest) =
        CapturedMemoryRefinementPolicy.inputs(packet, 0, "Hello Jin, friend.", "नमस्ते, मित्र।", "hi", request, "current")
    private fun prompt(inputs: CapturedMemoryRefinementInputs, request: TranslationRefinementRequest) =
        TranslationRefinementPolicy.prompt("Hello Jin, friend.", "नमस्ते, मित्र।", "hi", request.style,
            inputs.chapterContext, inputs.glossary, inputs.packetSha256)
}
