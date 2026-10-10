package com.mangalens.core.search.embedding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.security.MessageDigest

class SemanticVectorIndexTest {
    @get:Rule val folder = TemporaryFolder()
    private fun entry(id: Int = 1) = SemanticVectorEntry(id.toString(16).padStart(32, '0'), id.toString(16).padStart(64, '0'), "a".repeat(64), FloatArray(384).also { it[0] = 1f }, 4, false)
    private fun body(version: Int = 1, count: Int = 0, pin: String = SemanticModelArtifactStore.sha256(SemanticEmbeddingPin.cachePin.toByteArray())): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { it.writeInt(0x4d4c5349); it.writeInt(version); it.writeUTF(pin); it.writeInt(count) }
        return output.toByteArray()
    }
    private fun checked(body: ByteArray) = body + MessageDigest.getInstance("SHA-256").digest(body)
    @Test fun roundTripPreservesExactSourceAndActualVector() {
        val first = entry(); val loaded = SemanticVectorIndexCodec.decode(SemanticVectorIndexCodec.encode(listOf(first))).single()
        assertEquals(first.key, loaded.key); assertEquals(first.chapterId, loaded.chapterId); assertArrayEquals(first.vector, loaded.vector, 0f)
    }
    @Test fun unrelatedChapterAndSourceKeysStayDistinct() { assertNotEquals(entry(1).key, entry(2).key) }
    @Test fun wrongModelTokenizerRuntimePinFailsEvenWithValidChecksum() { assertThrows(IllegalArgumentException::class.java) { SemanticVectorIndexCodec.decode(checked(body(pin = "b".repeat(64)))) } }
    @Test fun futureJournalVersionFails() { assertThrows(IllegalArgumentException::class.java) { SemanticVectorIndexCodec.decode(checked(body(version = 2))) } }
    @Test fun corruptVectorBytesFailChecksum() {
        val bytes = SemanticVectorIndexCodec.encode(listOf(entry())); bytes[bytes.size - 40] = (bytes[bytes.size - 40].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { SemanticVectorIndexCodec.decode(bytes) }
    }
    @Test fun recomputedChecksumDoesNotAdmitTrailingBodyBytes() { assertThrows(IllegalArgumentException::class.java) { SemanticVectorIndexCodec.decode(checked(body() + byteArrayOf(1))) } }
    @Test fun hostileRecordCountIsRejectedBeforeAllocation() { assertThrows(IllegalArgumentException::class.java) { SemanticVectorIndexCodec.decode(checked(body(count = Int.MAX_VALUE))) } }
    @Test fun duplicateSourceKeysAreRejected() { assertThrows(IllegalArgumentException::class.java) { SemanticVectorIndexCodec.encode(listOf(entry(), entry())) } }
    @Test fun corruptedDerivedCacheIsReportedForExplicitRebuild() {
        val root = folder.newFolder(); val directory = File(root, "semantic_models").also { it.mkdir() }
        File(directory, "library-vectors-v1.bin").writeBytes(byteArrayOf(1, 2, 3))
        val found = SemanticVectorIndex(root).read(); assertTrue(found.unreadable); assertTrue(found.entries.isEmpty())
    }
    @Test fun canceledStagedReplacementRetainsPreviousCommittedIndex() = runBlocking {
        val root = folder.newFolder(); val store = SemanticVectorIndex(root); store.replace(listOf(entry(1))) {}
        val directory = File(root, "semantic_models")
        try {
            store.replace(listOf(entry(2))) { if (directory.listFiles().orEmpty().any { it.name.endsWith(".pending") && it.length() > 0 }) throw CancellationException() }
            fail("The held write checkpoint must cancel before replacing the old journal.")
        } catch (_: CancellationException) { }
        assertEquals(entry(1).key, store.read().entries.single().key)
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".pending") })
    }
    @Test fun oneOwnedCrashStageIsReplacedWithoutPromotingItsHints() = runBlocking {
        val root = folder.newFolder(); val store = SemanticVectorIndex(root); store.replace(listOf(entry(1))) {}
        File(root, "semantic_models/library-vectors-v1.bin.pending").writeBytes(SemanticVectorIndexCodec.encode(listOf(entry(2))))
        assertEquals(entry(1).key, store.read().entries.single().key)
        store.replace(listOf(entry(3))) {}
        assertEquals(entry(3).key, store.read().entries.single().key)
        assertFalse(File(root, "semantic_models/library-vectors-v1.bin.pending").exists())
    }
    @Test fun callerCancellationIsNeverConvertedToCorruptCacheStatus() = runBlocking {
        val root = folder.newFolder(); val store = SemanticVectorIndex(root); store.replace(listOf(entry())) {}
        assertThrows(CancellationException::class.java) { store.read { throw CancellationException() } }
    }
    @Test fun oversizeDerivedJournalIsRejectedWithBoundedRead() {
        val root = folder.newFolder(); val directory = File(root, "semantic_models").also { it.mkdir() }
        RandomAccessFile(File(directory, "library-vectors-v1.bin"), "rw").use { it.setLength(SemanticVectorIndexCodec.MAX_BYTES.toLong() + 1) }
        assertTrue(SemanticVectorIndex(root).read().unreadable)
    }
}
