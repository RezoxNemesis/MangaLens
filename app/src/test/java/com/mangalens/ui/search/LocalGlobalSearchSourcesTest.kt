package com.mangalens.ui.search

import com.mangalens.core.translation.memory.*
import com.mangalens.ui.video.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Authored controls only; actual metadata stores supply read leases rather than invented receipts. */
class LocalGlobalSearchSourcesTest {
    private fun entry(index: Int, title: String = "ＳＷＯＲＤ video", kind: RecentVideoKind = RecentVideoKind.ONLINE) =
        RecentVideoEntry(RecentVideoSource(kind, if (kind == RecentVideoKind.ONLINE) "https://video.test/watch/$index" else "content://videos/document/$index"),
            title, 30_000, 120_000, true, index.toLong())
    private suspend fun search(query: String, recent: RecentVideoHistoryState? = null, glossary: MemoryGlossaryMetadataBatch? = null) =
        LocalGlobalSearch.search(query, emptyList(), null, { _, _ -> emptyList() }, { _, _, _ -> emptyList() }, false, recent, glossary)
    @Test fun durableRecentMetadataIsFoundByUnicodeTitleWithoutDownloadRows() = runBlocking {
        val captured = entry(1)
        val result = search("sword", RecentVideoHistoryState(listOf(captured), false))
        val hit = result.hits.single()
        assertEquals(LocalSearchKind.VIDEO, hit.kind)
        assertEquals(captured.key, hit.recentVideoKey)
        assertEquals(captured.source, hit.recentVideoSource)
        assertNull(hit.downloadId)
        assertNull(hit.browserUrl)
        assertNull(hit.chapterId)
        assertTrue(hit.snippet.contains("30 seconds"))
    }
    @Test fun recentLocalResultRetainsOpaqueKeyAndCannotMasqueradeAsBrowserOrDownloadOpen() = runBlocking {
        val captured = entry(1, kind = RecentVideoKind.LOCAL)
        val hit = search("sword", RecentVideoHistoryState(listOf(captured), false)).hits.single()
        assertEquals(captured.key, hit.recentVideoKey)
        assertNull(hit.downloadId)
        assertNull(hit.browserUrl)
        assertNull(hit.messageId)
    }
    @Test fun recentHistorySearchStaysWithinExistingFortyRecordsAndSixteenMatches() = runBlocking {
        val result = search("sword", RecentVideoHistoryState((1..40).map { entry(it) }, false))
        assertEquals(16, result.hits.size)
        assertEquals(16, result.hits.map { it.key }.distinct().size)
    }
    @Test fun sourcePageQueryTokensAreNotSearchableDisplayMetadata() = runBlocking {
        val captured = entry(1, title = "ordinary title").copy(source = RecentVideoSource(RecentVideoKind.ONLINE,
            "https://video.test/watch/1?token=secret-needle"))
        assertTrue(search("secret-needle", RecentVideoHistoryState(listOf(captured), false)).hits.isEmpty())
        assertEquals(1, search("video.test", RecentVideoHistoryState(listOf(captured), false)).hits.size)
    }
    @Test fun coldAndFailedHistoryRemainVisibleAsSourceLimitations() = runBlocking {
        assertTrue(search("sword", RecentVideoHistoryState()).recentVideosLoading)
        val failed = search("sword", RecentVideoHistoryState(listOf(entry(1)), false, "read fault"))
        assertFalse(failed.recentVideosLoading)
        assertTrue(failed.recentVideosStorageError)
        assertEquals(1, failed.hits.size)
    }
    @Test fun invalidQueryIsRejectedBeforeAnyDatabaseCallback() = runBlocking {
        var callbacks = 0
        var rejected = false
        try { LocalGlobalSearch.search("bad\nquery", emptyList(), null,
            { _, _ -> callbacks++; emptyList() }, { _, _, _ -> callbacks++; emptyList() }, true) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(0, callbacks)
    }
    @Test fun actualGlossaryPreferredAliasAndProfileIdentitySurviveGlobalMetadataDelivery() = runBlocking {
        val root = Files.createTempDirectory("global-glossary-real").toFile()
        try {
            val memory = SeriesMemoryStore(root)
            memory.createSeries("Actual explicit series", "actual-series")
            memory.upsertTerm("actual-series", SeriesGlossaryTerm("term-one", "blade", "तलवार", "hi", aliases = listOf("ＳＷＯＲＤ")))
            val result = search("sword", glossary = memory.searchGlossaryMetadata("sword"))
            val hit = result.hits.single()
            assertEquals(LocalSearchKind.GLOSSARY, hit.kind)
            val match = requireNotNull(hit.glossary)
            assertEquals("actual-series", match.read.profile.id)
            assertEquals("term-one", match.term.id)
            assertNull(match.term.origin)
            assertNull(hit.chapterId)
            assertTrue(result.tryDeliver { it.hits.single() === hit })
        } finally { root.deleteRecursively() }
    }
    @Test fun oneChangedActualProfileBlocksOldMultiProfileResultDelivery() = runBlocking {
        val root = Files.createTempDirectory("global-glossary-retired").toFile()
        try {
            val memory = SeriesMemoryStore(root)
            listOf("series-a", "series-b").forEach { id ->
                memory.createSeries("Identical title", id)
                memory.upsertTerm(id, SeriesGlossaryTerm("term", "needle", "value", "en"))
            }
            val result = search("needle", glossary = memory.searchGlossaryMetadata("needle"))
            assertEquals(2, result.hits.size)
            memory.removeSeries("series-b")
            var delivered = false
            assertFalse(result.tryDeliver { delivered = true; true })
            assertFalse(delivered)
        } finally { root.deleteRecursively() }
    }
    @Test fun incompleteGlossaryPassIsNotAdvertisedAsCompleteGlobalCoverage() = runBlocking {
        val root = Files.createTempDirectory("global-glossary-cap").toFile()
        try {
            val memory = SeriesMemoryStore(root)
            memory.createSeries("Terms", "actual-series")
            (0..16).forEach { memory.upsertTerm("actual-series", SeriesGlossaryTerm("term-$it", "needle-$it", "value", "en")) }
            val result = search("needle", glossary = memory.searchGlossaryMetadata("needle"))
            assertEquals(16, result.hits.size)
            assertTrue(result.glossaryIncomplete)
        } finally { root.deleteRecursively() }
    }
}
