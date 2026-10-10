package com.mangalens.core.translation

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** UNRUN. JVM handle is a controlled file-IO seam; it does not claim Android fstat verification. */
class NativeIndexIncrementalHasherTest {
    @Test fun fileSpansManyPassesAndEveryPassClosesItsDescriptor() = fixture { file ->
        val handles = arrayListOf<TestNativeIndexReadHandle>()
        val cursor = NativeIndexIncrementalHasher(spec(file)) { TestNativeIndexReadHandle(it).also(handles::add) }
        var result: NativeIndexVerifiedFile? = null
        var received = 0L
        repeat(8) { val budget = NativeIndexPassBudget(32 * 1024); result = cursor.pass(budget) { null }; received += budget.used }
        assertNotNull(result); assertEquals(file.length(), received)
        assertEquals(8, handles.size); assertTrue(handles.all { it.closed })
        assertEquals(ChapterTranslationStore.sha256(file), result!!.sha256)
    }
    @Test fun sharedBudgetIncludesBytesReadByAHashMismatch() = fixture { file ->
        val budget = NativeIndexPassBudget(file.length())
        val cursor = NativeIndexIncrementalHasher(spec(file).copy(expectedSha256 = "0".repeat(64)), ::TestNativeIndexReadHandle)
        assertFailure { cursor.pass(budget) { null } }
        assertEquals(file.length(), budget.used); assertEquals(0L, budget.remaining)
    }
    @Test fun identicalAtomicReplacementCannotContinueAnOldDigest() = fixture { file ->
        val cursor = NativeIndexIncrementalHasher(spec(file), ::TestNativeIndexReadHandle)
        assertNull(cursor.pass(NativeIndexPassBudget(1024)) { null })
        val modified = Files.getLastModifiedTime(file.toPath())
        val replacement = File(file.parentFile, "replacement").apply { writeBytes(file.readBytes()) }
        Files.setLastModifiedTime(replacement.toPath(), modified)
        Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        assertFailure { cursor.pass(NativeIndexPassBudget(1024)) { null } }
    }
    @Test fun aFileThatGrowsBetweenPassesIsRejectedBeforeAnotherRead() = fixture { file ->
        val cursor = NativeIndexIncrementalHasher(spec(file), ::TestNativeIndexReadHandle)
        assertNull(cursor.pass(NativeIndexPassBudget(1024)) { null }); file.appendText("changed")
        val budget = NativeIndexPassBudget(1024); assertFailure { cursor.pass(budget) { null } }; assertEquals(0L, budget.used)
    }
    @Test fun aDescriptorFromAnotherInodeCannotContinueTheSamePath() = fixture { file ->
        var opens = 0
        val cursor = NativeIndexIncrementalHasher(spec(file)) { TestNativeIndexReadHandle(it, inodeOffset = if (++opens == 1) 0 else 1) }
        assertNull(cursor.pass(NativeIndexPassBudget(1024)) { null })
        assertFailure { cursor.pass(NativeIndexPassBudget(1024)) { null } }
    }
    @Test fun pathDescriptorMismatchAfterReadingChargesConsumedBytesAndCloses() = fixture { file ->
        lateinit var held: TestNativeIndexReadHandle
        val cursor = NativeIndexIncrementalHasher(spec(file)) { TestNativeIndexReadHandle(it, failPathAfterRead = true).also { held = it } }
        val budget = NativeIndexPassBudget(1024); assertFailure { cursor.pass(budget) { null } }
        assertEquals(1024L, budget.used); assertTrue(held.closed)
    }
    @Test fun zeroProgressIsBoundedAndDoesNotPretendToHaveReadBytes() = fixture { file ->
        val handle = TestNativeIndexReadHandle(file, zeroReads = true)
        val cursor = NativeIndexIncrementalHasher(spec(file)) { handle }
        val budget = NativeIndexPassBudget(1024); assertFailure { cursor.pass(budget) { null } }
        assertEquals(0L, budget.used); assertTrue(handle.closed); assertEquals(4, handle.readCalls)
    }
    @Test fun cancellationAfterHeldDimensionsCannotDeliverAndCanResumeWithoutRedigesting() = fixture { file ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var decoded = 0
        val cursor = NativeIndexIncrementalHasher(spec(file).copy(needsDimensions = true), ::TestNativeIndexReadHandle)
        var delivered = false
        val job = CoroutineScope(Dispatchers.IO).launch {
            cursor.pass(NativeIndexPassBudget(file.length())) { decoded++; entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); 12 to 13 }
            delivered = true
        }
        check(entered.await(5, TimeUnit.SECONDS)); job.cancel(); release.countDown(); job.join()
        assertFalse(delivered)
        val freshBudget = NativeIndexPassBudget(1)
        assertEquals(12 to 13, cursor.pass(freshBudget) { error("completed dimensions must not be repeated") }!!.dimensions)
        assertEquals(0L, freshBudget.used); assertEquals(1, decoded)
    }
    @Test fun headerCaptureDoesNotReadBeyondItsAlreadyChargedFileBytes() = fixture { file ->
        var prefixSize = 0
        val cursor = NativeIndexIncrementalHasher(spec(file).copy(needsDimensions = true), ::TestNativeIndexReadHandle)
        val budget = NativeIndexPassBudget(file.length())
        assertNotNull(cursor.pass(budget) { prefixSize = it.size; 12 to 13 })
        assertEquals(64 * 1024, prefixSize); assertEquals(file.length(), budget.used)
    }
    @Test fun unreadableDimensionsNeverBecomeAWarmFileProof() = fixture { file ->
        val cursor = NativeIndexIncrementalHasher(spec(file).copy(needsDimensions = true), ::TestNativeIndexReadHandle)
        assertFailure { cursor.pass(NativeIndexPassBudget(file.length())) { null } }
    }
    @Test fun thirtyThreeMiBFileContinuesBeyondTheRealThirtyTwoMiBPassLimit() = fixture { file ->
        file.outputStream().use { output -> val chunk = ByteArray(1024 * 1024) { 7 }; repeat(33) { output.write(chunk) } }
        val cursor = NativeIndexIncrementalHasher(spec(file), ::TestNativeIndexReadHandle)
        val first = NativeIndexPassBudget(); assertNull(cursor.pass(first) { null })
        assertEquals(32L * 1024 * 1024, first.used)
        val second = NativeIndexPassBudget(); assertNotNull(cursor.pass(second) { null })
        assertEquals(1024L * 1024, second.used)
    }
    private fun spec(file: File) = NativeIndexFileSpec(7, true, file, ChapterTranslationStore.sha256(file), 40L * 1024 * 1024, false)
    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("native-index-chunks").toFile()
        try { block(File(root, "source.jpg").apply { writeBytes(ByteArray(256 * 1024) { (it % 251).toByte() }) }) }
        finally { root.deleteRecursively() }
    }
    private suspend fun assertFailure(block: suspend () -> Any?) { try { block(); fail("expected bounded proof rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }
}

internal class TestNativeIndexReadHandle(private val file: File, inodeOffset: Long = 0,
    private val failPathAfterRead: Boolean = false, private val zeroReads: Boolean = false) : NativeIndexReadHandle {
    private val captured = nativeIndexStamp(file)
    private val input = FileInputStream(file)
    override val identity = NativeIndexDescriptorIdentity(0, captured.key.hashCode().toLong() + inodeOffset, captured.size, captured.modified.toMillis() / 1000)
    var closed = false; private set
    var readCalls = 0; private set
    override fun matchesPath() = nativeIndexFileCurrent(captured) && (!failPathAfterRead || readCalls == 0)
    override fun seek(offset: Long) { input.channel.position(offset) }
    override fun read(bytes: ByteArray, count: Int): Int { readCalls++; return if (zeroReads) 0 else input.read(bytes, 0, count) }
    override fun close() { input.close(); closed = true }
}
