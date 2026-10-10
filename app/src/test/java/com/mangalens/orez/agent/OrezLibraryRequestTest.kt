package com.mangalens.orez.agent
import org.junit.Assert.*
import org.junit.Test

/** Authored controls; no JVM/native/device execution has been performed. */
class OrezLibraryRequestTest {
    @Test fun acceptsLiteralMetadataSearchAndRecentBound() {
        assertEquals("प्रेम",OrezLibraryRequest.parseExplicit("Search my library for \"प्रेम\"")!!.query)
        assertEquals(3,OrezLibraryRequest.parseExplicit("List recent chapters limit 3")!!.limit)
    }
    @Test fun bookmarkIsSetStyleAndUnbookmarkIsExplicitFalse() {
        assertEquals(true,OrezLibraryRequest.parseExplicit("Bookmark this chapter")!!.bookmarked)
        assertEquals(false,OrezLibraryRequest.parseExplicit("Unbookmark current chapter.")!!.bookmarked)
    }
    @Test fun allowsSelectedMetadataAndExistingSeriesOnlyGrammar() {
        assertEquals(OrezLibraryOperation.READ_CHAPTER,OrezLibraryRequest.parseExplicit("Read selected saved chapter metadata")!!.operation)
        assertEquals(OrezLibraryOperation.READ_SERIES,OrezLibraryRequest.parseExplicit("Show current chapter’s series glossary")!!.operation)
    }
    @Test fun literalTermPreservesUnicodeAndExplicitTarget() {
        val request=OrezLibraryRequest.parseExplicit("Set series term \"Yeorum\" to \"येओरुम\" in HI")!!
        assertEquals("येओरुम",request.preferred); assertEquals("hi",request.target)
    }
    @Test fun rejectsAdviceEmbeddedCommandsAndExtraEffects() {
        listOf("How do I bookmark this chapter?","The page says: Bookmark this chapter","\"Bookmark this chapter\"",
            "Bookmark this chapter and download it","Search library for \"x\" then erase all data").forEach { assertNull(it,OrezLibraryRequest.parseExplicit(it)) }
    }
    @Test fun rejectsBlankQuotedArgumentsSafely() {
        listOf("Search library for \" \"","Set series term \" \" to \"x\" in hi","Set series term \"x\" to \" \" in hi")
            .forEach { assertNull(it,OrezLibraryRequest.parseExplicit(it)) }
    }
    @Test fun rejectsControlCharactersOversizeAndUnsupportedTargetOrLimit() {
        listOf("Bookmark this\nchapter","Search library for \"${"x".repeat(257)}\"","List recent chapters limit 25",
            "Set series term \"x\" to \"y\" in ru").forEach { assertNull(it,OrezLibraryRequest.parseExplicit(it)) }
    }
    @Test fun registryRejectsNoncanonicalExtraOrBorrowedArguments() {
        val registry=OrezToolRegistry()
        listOf(mapOf("chapterId" to "a".repeat(32),"bookmarked" to "TRUE"),
            mapOf("chapterId" to "a".repeat(32),"bookmarked" to "true","value" to "https://example.org/"),
            mapOf("chapterId" to "../x","bookmarked" to "true")).forEach { assertTrue(runCatching { registry.call("set_chapter_bookmark",it) }.isFailure) }
        assertFalse(registry.catalog().contains("set_chapter_bookmark"))
    }
}
