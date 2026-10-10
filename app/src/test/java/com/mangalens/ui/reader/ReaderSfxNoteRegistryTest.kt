package com.mangalens.ui.reader

import com.mangalens.core.translation.memory.MemorySfxPresentation
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN. Main-composition metadata/resource separation; no source/native receipt fixture. */
class ReaderSfxNoteRegistryTest {
    private fun source(page: Int, text: String, current: () -> Boolean = { true }) = object : ReaderSfxNoteSource {
        override fun noteGroup(pageIndex: Int) = if (pageIndex != page || !current()) null else
            ReaderSfxNoteGroup(page, listOf(ReaderSfxNoteRow(2, MemorySfxPresentation.ALONGSIDE, text, null,
                ReaderSfxOriginalReadiness.UNAVAILABLE)))
    }
    @Test fun unregisteringAComposedImageRemovesItsDisplayMetadata() {
        val registry = ReaderSfxNoteRegistry(); val source = source(37, "current")
        registry.register(source); assertEquals(1, registry.visible(setOf(37)).size)
        registry.unregister(source); assertTrue(registry.visible(setOf(37)).isEmpty()); registry.close()
    }
    @Test fun exactDuplicateRegistrationCannotDisplaceOtherCurrentMetadata() {
        val registry = ReaderSfxNoteRegistry(); val old = source(37, "old"); val fresh = source(37, "fresh")
        registry.register(old); registry.register(fresh); registry.register(old)
        assertEquals("fresh", registry.visible(setOf(37)).single().rows.single().translated); registry.close()
    }
    @Test fun fixedRegistryCapKeepsTheNewestComposedVisiblePage() {
        val registry = ReaderSfxNoteRegistry()
        for (page in 0..ReaderSfxNotePolicy.MAX_SESSIONS) registry.register(source(page, "page$page"))
        assertTrue(registry.visible(setOf(0)).isEmpty())
        assertEquals("page16", registry.visible(setOf(16)).single().rows.single().translated); registry.close()
    }
    @Test fun closedOrRetiredMetadataCannotRepublishIntoTheFixedReaderHost() {
        val registry = ReaderSfxNoteRegistry(); var current = true; val source = source(37, "current") { current }
        registry.register(source); current = false; assertTrue(registry.visible(setOf(37)).isEmpty())
        registry.close(); current = true; registry.register(source); assertTrue(registry.visible(setOf(37)).isEmpty())
    }
}
