package com.mangalens.core.translation.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Authored controls only: no test runner has executed this packet. */
class MemoryGlossaryMetadataTest {
    private fun environment(block: suspend (File, SeriesMemoryStore) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("glossary-metadata-control").toFile()
        try { block(root, SeriesMemoryStore(root)) } finally { root.deleteRecursively() }
    }
    private suspend fun term(memory: SeriesMemoryStore, series: String = "series-a", id: String = "term-a",
        source: String = "Sword", preferred: String = "तलवार", aliases: List<String> = listOf("ＳＷＯＲＤ")) {
        memory.createSeries("Actual ${series}", series)
        memory.upsertTerm(series, SeriesGlossaryTerm(id, source, preferred, "hi", aliases = aliases, updatedAt = 7))
    }
    @Test fun actualSourcePreferredAndCompatibilityAliasMatchWithoutChapterOrNativeRecords() = environment { root, memory ->
        term(memory)
        listOf("sword", "तलवार", "ＳＷＯＲＤ").forEach { query ->
            val hit = memory.searchGlossaryMetadata(query).hits.single()
            assertEquals("series-a", hit.read.profile.id)
            assertEquals("term-a", hit.term.id)
            assertNull(hit.term.origin)
            assertNull(hit.term.originSourceSha256)
        }
        assertFalse(File(root, "reader_memory/chapters").exists())
        assertFalse(File(root, "chapter_translations").exists())
    }
    @Test fun aliasesDoNotInventSourceOriginsAndInspectionDoesNotRewriteJournal() = environment { root, memory ->
        term(memory, aliases = listOf("blade"))
        val file = File(root, "reader_memory/series/series-a.json")
        val before = file.readBytes()
        val hit = memory.searchGlossaryMetadata("blade").hits.single()
        assertEquals(listOf("blade"), hit.term.aliases)
        assertTrue(hit.read.tryDeliver { it.glossary.single() == hit.term })
        assertArrayEquals(before, file.readBytes())
    }
    @Test fun equalTitlesAndEqualTermIdsKeepActualDistinctSeriesIdentity() = environment { _, memory ->
        term(memory)
        term(memory, series = "series-b", preferred = "दूसरी तलवार")
        val hits = memory.searchGlossaryMetadata("sword").hits
        assertEquals(setOf("series-a", "series-b"), hits.map { it.read.profile.id }.toSet())
        assertEquals(2, hits.size)
    }
    @Test fun replacedProfileRejectsOldDecodedValuesAtDelivery() = environment { _, memory ->
        term(memory)
        val old = memory.prepareProfileRead("series-a")!!
        memory.upsertTerm("series-a", old.profile.glossary.single().copy(preferred = "changed", updatedAt = 9))
        var accepted = false
        assertFalse(old.tryDeliver { accepted = true; true })
        assertFalse(accepted)
        assertEquals("changed", memory.prepareProfileRead("series-a")!!.profile.glossary.single().preferred)
    }
    @Test fun removedTermIsAbsentFromCurrentPreparedProfileAndOldLeaseIsRetired() = environment { _, memory ->
        term(memory)
        val captured = memory.searchGlossaryMetadata("sword").hits.single()
        memory.removeTerm("series-a", captured.term.id)
        assertFalse(captured.read.tryDeliver { true })
        assertTrue(memory.prepareProfileRead("series-a")!!.profile.glossary.isEmpty())
        assertTrue(memory.searchGlossaryMetadata("sword").hits.isEmpty())
    }
    @Test fun tombstonedSeriesCannotSupplyResultsOrCurrentProfile() = environment { _, memory ->
        term(memory)
        val old = memory.prepareProfileRead("series-a")!!
        memory.removeSeries("series-a")
        assertNull(memory.prepareProfileRead("series-a"))
        assertFalse(old.tryDeliver { true })
        assertTrue(memory.searchGlossaryMetadata("sword").hits.isEmpty())
    }
    @Test fun invalidQueryAndIdentityFailBeforeAnyJournalCreation() = environment { root, memory ->
        listOf("", "  ", "unsafe\nquery", "x".repeat(161)).forEach { query ->
            var rejected = false
            try { memory.searchGlossaryMetadata(query) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
        var rejected = false
        try { memory.prepareProfileRead("../outside") } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }
    @Test fun moreThanSixteenActualTermMatchesAreHonestlyIncomplete() = environment { _, memory ->
        memory.createSeries("Current series", "many")
        (0..16).forEach { memory.upsertTerm("many", SeriesGlossaryTerm("term-$it", "needle-$it", "value-$it", "en")) }
        val result = memory.searchGlossaryMetadata("needle")
        assertEquals(16, result.hits.size)
        assertTrue(result.incomplete)
        assertEquals(1, result.profilesScanned)
    }
    @Test fun profilePassStopsAt128AndDoesNotPretendALaterMatchIsAbsent() = environment { _, memory ->
        (0..128).forEach { memory.createSeries("Series $it", "profile-%03d".format(it)) }
        memory.upsertTerm("profile-128", SeriesGlossaryTerm("later", "beyond", "later value", "en"))
        val result = memory.searchGlossaryMetadata("beyond")
        assertEquals(128, result.profilesScanned)
        assertTrue(result.incomplete)
        assertTrue(result.hits.isEmpty())
        assertEquals("beyond", memory.prepareProfileRead("profile-128")!!.profile.glossary.single().source)
    }
    @Test fun actualReadsIncludingMalformedProfilesAreChargedWithoutRewriting() = environment { root, memory ->
        val directory = File(root, "reader_memory/series").apply { mkdirs() }
        val broken = File(directory, "broken.json").apply { writeBytes(ByteArray(512 * 1024) { 'x'.code.toByte() }) }
        val before = broken.readBytes()
        val result = memory.searchGlossaryMetadata("needle")
        assertTrue(result.incomplete)
        assertEquals(before.size.toLong(), result.receivedBytes)
        assertTrue(result.hits.isEmpty())
        assertArrayEquals(before, broken.readBytes())
    }
    @Test fun largeLegitimateProfilesKeepActualReadsWithinOne16MiBPass() = environment { root, memory ->
        val directory = File(root, "reader_memory/series").apply { mkdirs() }
        val terms = (0 until 200).map { index -> SeriesGlossaryTerm("term-$index", "ordinary-$index", "spelling-$index", "en",
            aliases = (0 until 8).map { "a$it" + "x".repeat(230) }, updatedAt = 1) }
        (0 until 48).forEach { index ->
            val profile = SeriesMemoryProfile("profile-%03d".format(index), "Large current profile $index", terms)
            profile.validate()
            val bytes = SeriesMemoryCodec.profile(profile)
            assertTrue(bytes.size <= 1024 * 1024)
            File(directory, profile.id + ".json").writeBytes(bytes)
        }
        val result = memory.searchGlossaryMetadata("absent-needle")
        assertTrue(result.incomplete)
        assertTrue(result.receivedBytes <= 16L * 1024 * 1024)
        assertTrue(result.receivedBytes > 15L * 1024 * 1024)
        assertTrue(result.hits.isEmpty())
    }
    @Test fun symbolicProfileNeverDeliversExternalDecodedValues() = environment { root, memory ->
        val external = Files.createTempFile("external-glossary", ".json").toFile()
        try {
            val profile = SeriesMemoryProfile("escaped", "External profile", listOf(SeriesGlossaryTerm("term", "needle", "value", "en")))
            external.writeBytes(SeriesMemoryCodec.profile(profile))
            val directory = File(root, "reader_memory/series").apply { mkdirs() }
            Files.createSymbolicLink(File(directory, "escaped.json").toPath(), external.toPath())
            assertNull(memory.prepareProfileRead("escaped"))
            val result = memory.searchGlossaryMetadata("needle")
            assertTrue(result.incomplete)
            assertTrue(result.hits.isEmpty())
            assertEquals(0L, result.receivedBytes)
        } finally { external.delete() }
    }
    @Test fun unrelatedFilesystemNoiseCannotMakeTheInventoryWalkUnbounded() = environment { root, memory ->
        val directory = File(root, "reader_memory/series").apply { mkdirs() }
        (0 until 1_025).forEach { File(directory, "unrelated-$it.tmp").writeText("noise") }
        val result = memory.searchGlossaryMetadata("needle")
        assertTrue(result.incomplete)
        assertEquals(0, result.profilesScanned)
        assertEquals(0L, result.receivedBytes)
        assertTrue(result.hits.isEmpty())
    }
    @Test fun alreadyCancelledCallerCannotPublishProfileOrMetadataBatch() = environment { _, memory ->
        term(memory)
        val canceled = Job().apply { cancel() }
        var accepted = false
        try { withContext(canceled) { memory.searchGlossaryMetadata("sword"); accepted = true } }
        catch (_: CancellationException) { }
        assertFalse(accepted)
    }
}
