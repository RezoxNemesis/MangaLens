package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import com.mangalens.orez.OrezModelPin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** UNRUN actual profile/read-lease and pure prompt controls; a typed fixture pin is not loaded-model evidence. */
class ReaderBubbleRegionRefinementInputsTest {
    private val request = TranslationRefinementRequest(true, TranslationStyleProfile.NATURAL,
        OrezModelPin("captured-lite", "a".repeat(64), 1234), TranslationRefinementPolicy.INPUT_PROFILE_VERSION)

    @Test fun currentExactTargetProfileTermFitsPromptWithoutInventingAWholeChapterPacket() = runBlocking {
        withInspection { inspection, _ ->
            val inputs = ReaderBubbleRegionRefinementInputs.prepare(inspection, "Hello.", "नमस्ते।", "hi", request)
            assertEquals(mapOf("Hello" to "नमस्ते"), inputs.glossary); assertNull(inputs.packetSha256)
            assertFalse(inputs.targetProjectionOmitted)
            assertTrue(TranslationRefinementPolicy.validCapturedInputs("Hello.", "नमस्ते।", "hi", request,
                inputs.chapterContext, inputs.glossary, inputs.packetSha256))
        }
    }
    @Test fun aDifferentRefinementTargetCannotBorrowProfileWordingWithoutItsOwnEvidence() = runBlocking {
        withInspection { inspection, _ ->
            val inputs = ReaderBubbleRegionRefinementInputs.prepare(inspection, "Hello.", "namaste.", "hi-latn", request)
            assertTrue(inputs.glossary.isEmpty()); assertTrue(inputs.targetProjectionOmitted)
            assertFalse(inputs.chapterContext.contains("Saved native translation:")); assertNull(inputs.packetSha256)
        }
    }
    @Test fun changingTheProfileRetiresItsCapturedInspectionBeforeNativeDelivery() = runBlocking {
        withInspection { inspection, adapter ->
            assertNotNull(adapter.tryAcceptInspection(inspection))
            adapter.memory.upsertTerm("series-one", SeriesGlossaryTerm("another-term", "friend", "मित्र", "hi"))
            assertNull(adapter.tryAcceptInspection(inspection))
        }
    }
    @Test fun unrelatedAndWrongTargetTermsCannotEnterTheSelectedBubblePrompt() = runBlocking {
        withInspection { inspection, _ ->
            val inputs = ReaderBubbleRegionRefinementInputs.prepare(inspection, "Hello.", "नमस्ते।", "hi", request)
            assertFalse(inputs.glossary.containsKey("Power")); assertFalse(inputs.glossary.values.contains("Hello"))
            assertTrue(inputs.chapterContext.length <= 4500); assertTrue(inputs.glossary.size <= 16)
        }
    }

    private suspend fun withInspection(body: suspend (ReaderBubbleInspection, NativeMemoryPublicationAdapter) -> Unit) {
        NativeMemoryPublicationAdapterTest.Fixture().use { fixture ->
            val store = fixture.store(); val task = fixture.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(fixture.root, store, authority)
            adapter.memory.createSeries("Reader series", "series-one"); adapter.memory.associateChapter(fixture.chapter.id, "series-one", 0)
            adapter.memory.upsertTerm("series-one", SeriesGlossaryTerm("matching", "Hello", "नमस्ते", "hi"))
            adapter.memory.upsertTerm("series-one", SeriesGlossaryTerm("other-target", "Hello", "Hello", "en"))
            adapter.memory.upsertTerm("series-one", SeriesGlossaryTerm("unrelated", "Power", "शक्ति", "hi"))
            val inspection = requireNotNull(adapter.inspectSavedBubble(selected, 0, 0, task.pages.single().lettering.single()))
            body(inspection, adapter)
        }
    }
}
