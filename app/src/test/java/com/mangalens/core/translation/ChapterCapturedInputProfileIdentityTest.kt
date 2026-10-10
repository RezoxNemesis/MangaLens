package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import com.mangalens.orez.OrezModelPin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

/** Actual native/packet journals retain historical slots; unknown revisions never become ambient current. */
class ChapterCapturedInputProfileIdentityTest {
    private val historical = TranslationRefinementRequest(true, pinnedModel = OrezModelPin("fixture", "a".repeat(64), 100))
    private val v2 = historical.copy(inputProfileRevision = "orez-localization-v2")
    private val v3 = historical.copy(inputProfileRevision = "orez-localization-v3")

    @Test fun historicalNullRevisionUsesExactShippedV2CacheLookupBytesWithoutBecomingGenerationReady() {
        for (request in listOf(null, historical)) {
            val configuration = ChapterTranslationConfig("hi", localRefinement = true, refinementRequest = request)
            val base = configuration.style().memoryKey
            val old = base + (request?.let { ":refinement:" + TranslationRefinementPolicy.hash(TranslationRefinementRequestCodec.identity(it)).take(20) }
                ?: "") + ":localization:orez-localization-v2"
            assertArrayEquals(old.toByteArray(Charsets.UTF_8), CapturedMemoryRefinementPolicy.styleIdentity(base, configuration).toByteArray(Charsets.UTF_8))
            assertFalse(request?.let(TranslationRefinementPolicy::generationReady) == true)
        }
    }

    @Test fun explicitUnknownRevisionHasItsOwnCacheIdentityAndCannotBorrowHistoricalLookupOrRunGeneration() {
        val unknown = historical.copy(inputProfileRevision = "future-profile-v77")
        val original = config(historical)
        val changed = config(unknown)
        val oldKey = CapturedMemoryRefinementPolicy.styleIdentity(original.style().memoryKey, original)
        val changedKey = CapturedMemoryRefinementPolicy.styleIdentity(changed.style().memoryKey, changed)
        assertNotEquals(oldKey, changedKey)
        assertTrue(changedKey.endsWith(":localization:future-profile-v77"))
        assertFalse(TranslationRefinementPolicy.generationReady(unknown))
    }

    @Test fun missingFieldKeepsExactOriginalTenFieldIdentityAndHistoricalStableTaskIdAfterColdRead() = runBlocking {
        val oldIdentity = listOf(historical.enabled.toString(), historical.style.id, historical.style.name, historical.style.instruction,
            historical.style.preserveHonorifics.toString(), historical.style.preserveNames.toString(), historical.style.naturalDialogue.toString(),
            historical.pinnedModel!!.modelId, historical.pinnedModel!!.sha256, historical.pinnedModel!!.bytes.toString())
            .joinToString("|") { "${it.length}:$it" }
        assertEquals(oldIdentity, TranslationRefinementRequestCodec.identity(historical))
        val encoded = TranslationRefinementRequestCodec.encode(historical)
        assertFalse(encoded.has("inputProfileRevision"))
        assertEquals(historical, TranslationRefinementRequestCodec.decode(encoded))
        assertEquals(historical, TranslationRefinementRequestCodec.decode(JSONObject(encoded.toString()).put("inputProfileRevision", JSONObject.NULL)))
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val config = config(historical)
            val task = f.store().start(f.chapter, config, ownerRequestId = "historical:owner")
            val before = f.journal(task.id).readBytes()
            val cold = f.store()
            assertEquals(task.id, cold.get(task.id)!!.id)
            assertEquals(historical, cold.get(task.id)!!.config.refinementRequest)
            assertArrayEquals(before, f.journal(task.id).readBytes())
            assertFalse(TranslationRefinementPolicy.generationReady(historical))
        }
    }

    @Test fun explicitV2V3AndUnknownRevisionAreSeparateDurableIdentitiesWithoutDroppingUnknownMetadata() {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val unknown = historical.copy(inputProfileRevision = "future-profile-v77")
            val native = f.store()
            val tasks = listOf(historical, v2, v3, unknown).map { native.start(f.chapter, config(it)) }
            assertEquals(4, tasks.map { it.id }.distinct().size)
            val cold = f.store()
            tasks.forEach { task -> assertEquals(task.config, cold.get(task.id)!!.config) }
            assertEquals(unknown, TranslationRefinementRequestCodec.decode(TranslationRefinementRequestCodec.encode(unknown)))
            assertFalse(TranslationRefinementPolicy.generationReady(unknown))
            for (bad in listOf("", "x".repeat(65), "profile\nforged", "../profile")) {
                assertTrue(runCatching { TranslationRefinementRequestCodec.encode(historical.copy(inputProfileRevision = bad)) }.isFailure)
            }
        }
    }

    @Test fun pausedV2ColdResumeAndOwnedReplayRetainCapturedRevisionWithoutCallingNewV3Factory() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val native = f.store(); val task = native.start(f.chapter, config(v2), ownerRequestId = "captured:v2")
            native.pause(task.id, task.generation)
            val cold = f.store(); val reopened = cold.refresh(task.id, task.generation)!!
            val resumed = cold.resume(reopened.id, reopened.generation)!!
            assertEquals(v2, resumed.config.refinementRequest)
            assertNotEquals(task.generation, resumed.generation)
            var captures = 0
            val replay = ChapterRefinementCapturePolicy.forStart(config(v2).copy(refinementRequest = null), f.chapter.id,
                task.ownerRequestId, false, listOf(resumed)) { captures++; v3 }
            assertEquals(0, captures); assertEquals(resumed.config, replay)
            val explicit = ChapterRefinementCapturePolicy.forStart(config(v2).copy(refinementRequest = null), f.chapter.id,
                "new:v3", false, listOf(resumed)) { captures++; v3 }
            assertEquals(1, captures); assertEquals(v3, explicit.refinementRequest)
        }
    }

    @Test fun changingOnlyCapturedRevisionCannotReuseTheV2PacketInARealNativeStart() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val native = f.store(); val memory = SeriesMemoryStore(f.root)
            memory.createSeries("Explicit captured series", "series")
            memory.associateChapter(f.chapter.id, "series", 0)
            memory.upsertTerm("series", SeriesGlossaryTerm("term", "Jin", "जिन", "hi"))
            val original = config(v2)
            val packet = NativeGenerationMemoryCapture(memory, native).capture(f.chapter, original, 0)!!
            val before = f.source.readBytes()
            assertTrue(runCatching { native.start(f.chapter, original.copy(refinementRequest = v3, memoryPacket = packet)) }.isFailure)
            assertTrue(native.states.value.isEmpty())
            val accepted = native.start(f.chapter, original.copy(memoryPacket = packet), ownerRequestId = "packet:v2")
            val cold = f.store()
            assertEquals(v2, cold.get(accepted.id)!!.config.refinementRequest)
            assertEquals(packet.sha256, cold.get(accepted.id)!!.config.memoryPacket!!.sha256)
            assertArrayEquals(before, f.source.readBytes())
        }
    }

    @Test fun orderedGlossaryPairInputsAndCapturedV2BudgetStayExactAfterPacketCodecRoundTrip() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val native = f.store(); val memory = SeriesMemoryStore(f.root)
            memory.createSeries("Explicit captured series", "series"); memory.associateChapter(f.chapter.id, "series", 0)
            memory.upsertTerm("series", SeriesGlossaryTerm("a", "Jin Woo", "जिन वू", "hi", aliases = listOf("Jinwoo", "Jin-Woo")))
            memory.upsertTerm("series", SeriesGlossaryTerm("b", "Jin", "जिन", "hi"))
            val packet = NativeGenerationMemoryCapture(memory, native).capture(f.chapter, config(v2), 0)!!
            val decoded = CapturedSeriesMemoryPacket.fromJson(JSONObject(packet.toJson().toString()))
            val source = "Jin Woo meets Jinwoo, Jin-Woo and Jin."
            val first = CapturedMemoryRefinementPolicy.inputs(packet, 0, source, "जिन वू आता है।", "hi", v2, "context")
            val second = CapturedMemoryRefinementPolicy.inputs(decoded, 0, source, "जिन वू आता है।", "hi", v2, "context")
            assertEquals(first.glossary.entries.map { it.toPair() }, second.glossary.entries.map { it.toPair() })
            assertEquals(listOf("Jin Woo", "Jinwoo", "Jin-Woo", "Jin"), first.glossary.keys.toList())
            assertEquals(TranslationRefinementPolicy.capturedPrompt(source, "जिन वू आता है।", "hi", v2, first.chapterContext, first.glossary, first.packetSha256),
                TranslationRefinementPolicy.capturedPrompt(source, "जिन वू आता है।", "hi", v2, second.chapterContext, second.glossary, second.packetSha256))
            assertTrue(TranslationRefinementPolicy.validCapturedInputs(source, "जिन वू आता है।", "hi", v2, first.chapterContext, first.glossary, first.packetSha256))
        }
    }

    private fun config(request: TranslationRefinementRequest) = ChapterTranslationConfig("hi", localRefinement = true, refinementRequest = request)
}
