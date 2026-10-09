package com.mangalens.core.translation.memory

import org.junit.Assert.*
import org.junit.Test

class SeriesMemoryQueryTest {
    private val links = listOf(MemoryChapterAssociation("earlier", "series-a", 1),
        MemoryChapterAssociation("current", "series-a", 2), MemoryChapterAssociation("future", "series-a", 3),
        MemoryChapterAssociation("other", "series-b", 1), MemoryChapterAssociation("unknown", "series-a", null))
    private val request = MemoryRetrievalRequest("current", 2, "Y-YEORUM! Sorry I'm so late?!", "hi", "faithful:pinA")
    private fun bubble(chapter: String, page: Int = 1, text: String = "Yeorum, do not worry.", target: String = "hi", config: String = "faithful:pinA") =
        MemoryIndexedBubble(MemoryPublicationReceipt(MemorySourceProof(chapter, page, "/private/chapters/$chapter.jpg", "a".repeat(64), 720, 9170,
            MemoryRegionBounds(200, 6150, 700, 6500)), "task-$chapter", "generation-$chapter", target, config, text, "येओरुम, चिंता मत करो।", seriesId = if (chapter == "other") "series-b" else "series-a"))
    private fun profile() = SeriesMemoryProfile("series-a", "Same display title", listOf(
        SeriesGlossaryTerm("name", "Yeorum", "येओरुम", "hi"),
        SeriesGlossaryTerm("world", "Akalifa", "अकलीफ़ा", "hi"),
        SeriesGlossaryTerm("later-name", "Yeorum's hidden identity", "later spoiler", "hi", origin = MemoryLocation("future", 1))))

    @Test fun retrievesOnlyRelevantNamesAndEarlierSameSeriesDialogue() {
        val result = SeriesMemoryQuery.relevant(request, links, profile(), listOf(bubble("earlier"), bubble("future"), bubble("other"), bubble("current", 1), bubble("current", 2)))
        assertEquals(mapOf("Yeorum" to "येओरुम"), result.glossary)
        assertEquals(setOf("earlier", "current"), result.priorDialogue.map { it.source.chapterId }.toSet())
        assertEquals(2, result.priorDialogue.size)
        assertTrue(result.priorDialogue.all { it.kind == MemorySearchKind.TRANSLATION && it.text == "येओरुम, चिंता मत करो।" })
    }

    @Test fun equalTitlesCannotLinkUnassociatedChapterOrWrongSeriesProfile() {
        val orphan = request.copy(chapterId = "unlinked")
        assertEquals(RelevantSeriesMemory(null, emptyMap(), emptyList()), SeriesMemoryQuery.relevant(orphan, links, profile(), listOf(bubble("earlier"))))
        assertEquals(RelevantSeriesMemory(null, emptyMap(), emptyList()), SeriesMemoryQuery.relevant(request, links,
            profile().copy(id = "series-b"), listOf(bubble("other"))))
    }

    @Test fun excludesFuturePageFutureChapterUnorderedChapterAndUnrelatedConfiguration() {
        val records = listOf(bubble("future"), bubble("current", 3), bubble("unknown"), bubble("earlier", target = "hi-latn"),
            bubble("earlier", config = "formal:pinB"), bubble("earlier", text = "Buy two cans of cola."))
        assertTrue(SeriesMemoryQuery.relevant(request, links, profile(), records).priorDialogue.isEmpty())
        val unknownCurrent = request.copy(chapterId = "unknown")
        assertTrue(SeriesMemoryQuery.relevant(unknownCurrent, links, profile(), listOf(bubble("earlier"))).priorDialogue.isEmpty())
    }

    @Test fun knownSourceOriginsMustPrecedeCurrentLocationInTheExactSeries() {
        val terms = listOf(SeriesGlossaryTerm("past", "Yeorum", "past", "hi", origin = MemoryLocation("earlier", 1)),
            SeriesGlossaryTerm("future", "Yeorum", "future", "hi", origin = MemoryLocation("future", 1)),
            SeriesGlossaryTerm("now", "Yeorum", "now", "hi", origin = MemoryLocation("current", 2)),
            SeriesGlossaryTerm("other", "Yeorum", "other", "hi", origin = MemoryLocation("other", 1)))
        val result = SeriesMemoryQuery.relevant(request, links, profile().copy(glossary = terms), emptyList())
        assertEquals(mapOf("Yeorum" to "past"), result.glossary)
    }

    @Test fun matchesWholeLatinNamesKeepsScriptMarksAndSupportsActualAliasPunctuation() {
        assertFalse(SeriesMemoryQuery.matches("Jingle bells", "Jin"))
        assertFalse(SeriesMemoryQuery.matches("UnJin", "Jin"))
        assertTrue(SeriesMemoryQuery.matches("Y-YEORUM!", "Yeorum"))
        assertTrue(SeriesMemoryQuery.matches("Ｊｉｎ, wait!", "Jin"))
        assertFalse(SeriesMemoryQuery.matches("कला", "कल"))
        assertTrue(SeriesMemoryQuery.matches("येओरुम, चिंता मत करो।", "येओरुम"))
        assertFalse(SeriesMemoryQuery.matches("Anything", ""))
    }

    @Test fun lexicalSearchIncludesGenuineTranslatedAndEditedSourceValuesWithEvidence() {
        val receipt = bubble("earlier", text = "From thel6the war\\aALaa").receipt
        val edited = MemoryIndexedBubble(receipt, MemoryCorrection(receipt, listOf(MemoryCorrectionRevision(1,
            MemoryCorrectionEdit("From the other world: 'Akalifa'!", "दूसरी दुनिया से: अकलीफ़ा!"), 20))))
        val hits = SeriesMemoryQuery.search("Akalifa", listOf(edited))
        assertEquals(listOf(MemorySearchKind.CORRECTED_OCR), hits.map { it.kind })
        assertEquals(receipt.source, hits.single().source)
        assertEquals(1, hits.single().revision)
        assertEquals(listOf(MemorySearchKind.CORRECTED_TRANSLATION), SeriesMemoryQuery.search("दूसरी दुनिया", listOf(edited)).map { it.kind })
        assertEquals(listOf(MemorySearchKind.OCR), SeriesMemoryQuery.search("thel6the", listOf(edited)).map { it.kind })
        assertEquals(listOf(MemorySearchKind.TRANSLATION), SeriesMemoryQuery.search("चिंता मत करो", listOf(edited)).map { it.kind })
    }

    @Test fun boundsContextAndSearchWithoutDiscardingStoredHistory() {
        val records = (1..100).map { bubble("earlier", it, "Yeorum " + "dialogue ".repeat(300)) }
        val result = SeriesMemoryQuery.relevant(request, links, profile(), records)
        assertTrue(result.priorDialogue.size <= 8)
        assertTrue(result.priorDialogue.sumOf { it.text.length } <= 4096)
        assertTrue(SeriesMemoryQuery.search("Yeorum", records).size <= 32)
        assertEquals(100, records.size)
    }

    @Test fun invalidGeometryOrReceiptCannotBeAdvertisedAsSourceProof() {
        val receipt = bubble("earlier").receipt
        assertThrows(IllegalArgumentException::class.java) { receipt.copy(source = receipt.source.copy(bounds = MemoryRegionBounds(-1, 0, 20, 30))).validate() }
        assertThrows(IllegalArgumentException::class.java) { receipt.copy(outputPath = "/private/output.png").validate() }
        assertNotEquals(receipt.bubbleId, receipt.copy(configurationIdentity = "faithful:pinB").bubbleId)
        assertEquals(receipt.bubbleId, receipt.copy(generation = "new-generation").bubbleId)
    }
    @Test fun eastAsianNamesRemainRetrievableInsideUnspacedDialogueAndScriptTransitions() {
        assertTrue(SeriesMemoryQuery.matches("井上さん、心配しないで。", "井上"))
        assertTrue(SeriesMemoryQuery.matches("연우는 걱정하지 마.", "연우"))
        assertTrue(SeriesMemoryQuery.matches("Yeorum은 늦었어!", "Yeorum"))
        assertFalse(SeriesMemoryQuery.matches("Jingle bells", "Jin"))
    }

    @Test fun glossaryPacketFitsPinnedRefinementBoundsWithoutTruncatingPreferredTerms() {
        val terms = (0 until 32).map { SeriesGlossaryTerm("term$it", "Yeorum$it", "p".repeat(256), "hi") }
        val query = request.copy(sourceText = terms.joinToString(" ") { it.source })
        val result = SeriesMemoryQuery.relevant(query, links, profile().copy(glossary = terms), emptyList())
        assertTrue(result.glossary.isNotEmpty())
        assertTrue(result.glossary.size <= 16)
        assertTrue(result.glossary.entries.sumOf { it.key.length + it.value.length } <= 2048)
        assertTrue(result.glossary.values.all { it.length == 256 })
        assertThrows(IllegalArgumentException::class.java) { terms.first().copy(preferred = "p".repeat(257)).validate() }
    }

    @Test fun explicitlyRecordedAliasMapsTheActualSourceSpellingWithoutInventingAName() {
        val term = SeriesGlossaryTerm("alias", "Yeorum", "येओरुम", "hi", aliases = listOf("Yeo-Rum"))
        val result = SeriesMemoryQuery.relevant(request.copy(sourceText = "Yeo-Rum, wait!"), links, profile().copy(glossary = listOf(term)), emptyList())
        assertEquals(mapOf("Yeo-Rum" to "येओरुम"), result.glossary)
    }

}
