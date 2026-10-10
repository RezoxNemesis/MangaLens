package com.mangalens.core.translation.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Authored before the outside candidate; execution is held by Root and is UNRUN. */
class MemoryLexicalHintIndexTest {
    @Test fun lexicalBoundariesAndScriptsUseTheExistingQueryPolicy() {
        val index = MemoryLexicalHintIndex("a".repeat(64), listOf(
            hint("Shankar knows the sword technique."), hint("शंकर ने तलवार उठाई।", bubble = "c".repeat(64)),
            hint("剣技を見た。", bubble = "d".repeat(64)), hint("ＫＥＮＪＩ left.", bubble = "e".repeat(64))), false)
        assertEquals(1, index.find(" sword technique ").hints.size)
        assertTrue(index.find("Shank").hints.isEmpty())
        assertEquals(1, index.find("शंकर").hints.size)
        assertTrue(index.find("शं").hints.isEmpty())
        assertEquals(1, index.find("剣技").hints.size)
        assertEquals(1, index.find("Kenji").hints.size)
    }

    @Test fun queryAndReturnLimitsDoNotInventUnlimitedScope() {
        val index = MemoryLexicalHintIndex("a".repeat(64), (0 until 40).map { hint("Sword technique", bubble = it.toString().padStart(64, '0')) }, false)
        assertEquals(32, index.find("sword").hints.size)
        assertTrue(index.find("sword").incomplete)
        for (query in listOf("", " ", "x".repeat(257), "sword\u0000")) assertNull(runCatching { index.find(query) }.getOrNull())
        assertNull(runCatching { index.find("sword", 33) }.getOrNull())
    }

    @Test fun fieldKindsAndLinkRevisionRemainDistinctTypedHints() {
        val one = hint("sword")
        val corrected = one.copy(kind = MemorySearchKind.CORRECTED_OCR, editRevision = 3, associationRevision = 7)
        val index = MemoryLexicalHintIndex("a".repeat(64), listOf(one, corrected), false)
        val found = index.find("sword").hints
        assertEquals(2, found.size)
        assertEquals(setOf(MemorySearchKind.OCR, MemorySearchKind.CORRECTED_OCR), found.map { it.kind }.toSet())
        assertEquals(setOf(0, 3), found.map { it.editRevision }.toSet())
        assertNotEquals(found[0].id, found[1].id)
    }

    @Test fun coldCacheRoundTripDoesNotCreateSourceOrOutputProof() = runBlocking {
        fixture { root, journal ->
            val source = File(root, "chapters/source.jpg").apply { parentFile.mkdirs(); writeText("untouched source") }
            val output = File(root, "chapter_translations/output.png").apply { parentFile.mkdirs(); writeText("untouched output") }
            val before = mapOf(source to source.readBytes(), output to output.readBytes(), journal to journal.readBytes())
            val first = MemoryLexicalHintStore(root).find("sword")
            assertEquals(1, first.hints.size)
            val cache = File(root, "reader_memory/lexical/index.json")
            assertTrue(cache.isFile)
            val cacheBytes = cache.readBytes()
            val cold = MemoryLexicalHintStore(root).find("SWORD")
            assertEquals(first.hints, cold.hints)
            assertArrayEquals(cacheBytes, cache.readBytes())
            before.forEach { (file, bytes) -> assertArrayEquals(file.path, bytes, file.readBytes()) }
            val serialized = cache.readText()
            assertFalse(serialized.contains(source.path)); assertFalse(serialized.contains(output.path))
            assertFalse(serialized.contains("sourceSha")); assertFalse(serialized.contains("generation"))
        }
    }

    @Test fun authoritativeAtomicJournalReplacementRefreshesWarmHintText() = runBlocking {
        fixture { root, journal ->
            val store = MemoryLexicalHintStore(root)
            assertEquals(1, store.find("sword").hints.size)
            val replacement = File(journal.parentFile, "replacement.pending").apply { writeBytes(journalBytes("Shield technique")) }
            Files.move(replacement.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            assertTrue(store.find("sword").hints.isEmpty())
            assertEquals(1, store.find("shield").hints.size)
        }
    }

    @Test fun corruptAndUnknownSchemaCacheRebuildsOnlyFromCurrentJournals() = runBlocking {
        fixture { root, _ ->
            MemoryLexicalHintStore(root).find("sword")
            val cache = File(root, "reader_memory/lexical/index.json")
            for (invalid in listOf("not json", "{\"version\":999,\"rows\":[]}", "{\"version\":1,\"inventory\":\"forged\",\"rows\":[]}")) {
                cache.writeText(invalid)
                assertEquals(1, MemoryLexicalHintStore(root).find("sword").hints.size)
            }
        }
    }

    @Test fun pendingCacheAndJournalFilesNeverBecomeCommittedHintAuthority() = runBlocking {
        fixture { root, journal ->
            File(journal.parentFile, journal.name + ".pending-test").writeBytes(journalBytes("Forged echo"))
            val lexical = File(root, "reader_memory/lexical").apply { mkdirs() }
            File(lexical, "index.json.pending-test").writeText("Forged echo")
            val store = MemoryLexicalHintStore(root)
            assertTrue(store.find("echo").hints.isEmpty())
            assertEquals(1, store.find("sword").hints.size)
        }
    }

    @Test fun corruptRemovedAndUnlinkedSeriesJournalsCannotAcquireNewSeriesIdentity() = runBlocking {
        fixture { root, journal ->
            val corrupt = File(journal.parentFile, "b".repeat(32) + ".json").apply { writeText("bad journal") }
            val result = MemoryLexicalHintStore(root).find("sword")
            assertEquals(1, result.hints.size); assertTrue(result.incomplete)
            assertNull(result.hints.single().seriesId)
            journal.writeBytes(SeriesMemoryCodec.chapter(MemoryChapterJournal("a".repeat(32), removed = true)))
            assertTrue(MemoryLexicalHintStore(root).find("sword", forceRefresh = true).hints.isEmpty())
            assertTrue(corrupt.isFile)
        }
    }

    @Test fun warmInventoryCacheMayKeepOnlyAHintUntilAnExplicitRefreshReadsChangedBytes() = runBlocking {
        fixture { root, journal ->
            val store = MemoryLexicalHintStore(root)
            assertEquals(1, store.find("sword").hints.size)
            val oldSize = journal.length().toInt(); val oldTime = Files.getLastModifiedTime(journal.toPath())
            journal.writeBytes(ByteArray(oldSize) { 'x'.code.toByte() })
            Files.setLastModifiedTime(journal.toPath(), oldTime)
            assertEquals("A warm index is only a hint and does not decode every unchanged-stamp journal", 1, store.find("sword").hints.size)
            val fresh = store.find("sword", forceRefresh = true)
            assertTrue(fresh.hints.isEmpty()); assertTrue(fresh.incomplete)
        }
    }

    @Test fun schemaRejectsDuplicateAndOversizeHintRows() {
        val one = hint("sword")
        assertNull(runCatching { MemoryLexicalHintIndex("a".repeat(64), listOf(one, one), false) }.getOrNull())
        assertNull(runCatching { MemoryLexicalHintIndex("a".repeat(64), (0..8192).map { one.copy(bubbleId = it.toString().padStart(64, '0')) }, false) }.getOrNull())
        assertNull(runCatching { one.copy(normalizedText = "x".repeat(4097)).validate() }.getOrNull())
    }

    @Test fun failedPreparedReplacementPreservesThePreviousCommittedCache() = runBlocking {
        fixture { root, journal ->
            val first = MemoryLexicalHintStore(root); first.find("sword")
            val cache = File(root, "reader_memory/lexical/index.json"); val before = cache.readBytes()
            journal.writeBytes(journalBytes("Shield technique"))
            val failingWriter = MemoryJournalWriter { file, bytes ->
                AtomicMemoryJournalWriter.prepare(file, bytes).let { actual -> object : PreparedMemoryJournal {
                    override fun commit() { throw java.io.IOException("held replacement fault") }
                    override fun close() = actual.close()
                } }
            }
            assertTrue(runCatching { MemoryLexicalHintStore(root, failingWriter).find("shield", forceRefresh = true) }.isFailure)
            assertArrayEquals(before, cache.readBytes())
            assertEquals(1, MemoryLexicalHintStore(root).find("shield", forceRefresh = true).hints.size)
        }
    }

    @Test fun oversizedInputJournalIsSkippedWithoutTreatingItAsTextAuthority() = runBlocking {
        fixture { root, journal ->
            File(journal.parentFile, "b".repeat(32) + ".json").writeBytes(ByteArray(2 * 1024 * 1024 + 1) { 1 })
            val found = MemoryLexicalHintStore(root).find("sword")
            assertEquals(1, found.hints.size); assertTrue(found.incomplete)
        }
    }

    @Test fun derivedEntryLimitReturnsOnlyBoundedHintsAndAnHonestIncompleteFlag() = runBlocking {
        val root = Files.createTempDirectory("lexical-entry-bound").toFile()
        try {
            val folder = File(root, "reader_memory/chapters").apply { mkdirs() }
            for (chapter in 0 until 9) {
                val id = chapter.toString(16).padStart(32, '0')
                val records = (0 until 256).map { ordinal ->
                    val source = MemorySourceProof(id, 0, "/managed/$id.jpg", "c".repeat(64), 100, 100, MemoryRegionBounds(1, 1, 90, 90))
                    val receipt = MemoryPublicationReceipt(source, "b".repeat(32), "d".repeat(32), "hi", "config $ordinal", "Sword source", "Sword native")
                    val correction = MemoryCorrection(receipt, listOf(MemoryCorrectionRevision(1, MemoryCorrectionEdit("Sword corrected", "Sword personal"), 1)))
                    MemoryIndexedBubble(receipt, correction)
                }
                val bytes = SeriesMemoryCodec.chapter(MemoryChapterJournal(id, bubbles = records))
                assertTrue(bytes.size <= 2 * 1024 * 1024)
                File(folder, "$id.json").writeBytes(bytes)
            }
            val found = MemoryLexicalHintStore(root).find("sword")
            assertEquals(32, found.hints.size); assertTrue(found.incomplete)
            val cache = MemoryLexicalHintCodec.decode(File(root, "reader_memory/lexical/index.json").readBytes())
            assertEquals(8192, cache.hints.size); assertTrue(cache.incomplete)
        } finally { root.deleteRecursively() }
    }

    private fun hint(text: String, bubble: String = "b".repeat(64)) = MemoryLexicalHint("a".repeat(32), bubble,
        MemorySearchKind.OCR, 0, null, 0, memoryTextKey(text))
    private suspend fun fixture(body: suspend (File, File) -> Unit) {
        val root = Files.createTempDirectory("lexical-index").toFile()
        try {
            val journal = File(root, "reader_memory/chapters/" + "a".repeat(32) + ".json").apply { parentFile.mkdirs(); writeBytes(journalBytes("Sword technique")) }
            body(root, journal)
        } finally { root.deleteRecursively() }
    }
    private fun journalBytes(text: String): ByteArray {
        val source = MemorySourceProof("a".repeat(32), 7, "/managed/source.jpg", "c".repeat(64), 100, 100, MemoryRegionBounds(1, 1, 90, 90))
        val receipt = MemoryPublicationReceipt(source, "b".repeat(32), "d".repeat(32), "hi", "captured config", text)
        return SeriesMemoryCodec.chapter(MemoryChapterJournal(source.chapterId, bubbles = listOf(MemoryIndexedBubble(receipt))))
    }
}
