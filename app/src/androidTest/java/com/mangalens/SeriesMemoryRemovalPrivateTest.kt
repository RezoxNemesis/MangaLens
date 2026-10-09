package com.mangalens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Real private PNG/journal lifecycle; this does not evaluate OCR or translation quality. */
@RunWith(AndroidJUnit4::class)
class SeriesMemoryRemovalPrivateTest {
    @Test fun removedCorrectionFencesHeldEditorAfterColdReopenAndPreservesPageArtifacts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "qa/series-memory-removal-" + UUID.randomUUID()).apply { check(mkdirs()) }
        try {
            val source = File(root, "chapters/original.png"); writePng(source, Color.WHITE)
            val output = File(root, "chapter_translations/task/output.png"); writePng(output, Color.LTGRAY)
            val receipt = MemoryPublicationReceipt(MemorySourceProof("chapter", 0, source.absolutePath,
                SeriesMemoryStore.sha256(source), 120, 80, MemoryRegionBounds(5, 5, 115, 75)),
                "task", "generation-one", "hi", "captured-private-test-style-and-pin", "Jin, wait.", "जिन, रुको।",
                output.absolutePath, SeriesMemoryStore.sha256(output))
            val guard = Any(); var owner = receipt
            val fence = MemoryPublicationFence { expected, commit -> synchronized(guard) { check(expected == owner); commit() } }
            val store = SeriesMemoryStore(root, fence); store.indexBubble(receipt, receipt)
            val heldRevision = store.inspectChapter("chapter").bubbles.single().editRevision
            val accepted = store.correct(receipt, receipt, heldRevision, MemoryCorrectionEdit(translated = "accepted personal text"))
            store.removeCorrection(receipt, receipt, accepted.revision)

            val cold = SeriesMemoryStore(root, fence)
            val removed = cold.inspectChapter("chapter").bubbles.single()
            assertNull(removed.correction); assertEquals(2, removed.editRevision)
            assertEquals(receipt.originalTranslation, removed.translatedText)
            val journal = File(root, "reader_memory/chapters/chapter.json"); val before = journal.readBytes()
            var rejected = false
            try { cold.correct(receipt, receipt, heldRevision, MemoryCorrectionEdit(translated = "held stale edit")) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected); assertArrayEquals(before, journal.readBytes())
            assertTrue(cold.search("held stale edit").hits.isEmpty()); assertTrue(cold.search("accepted personal text").hits.isEmpty())
            assertEquals(removed.editRevision, cold.search("Jin").hits.single().revision)

            val refreshed = receipt.copy(generation = "generation-two")
            synchronized(guard) { owner = refreshed }
            cold.indexBubble(refreshed, refreshed)
            assertEquals(2, SeriesMemoryStore(root).inspectChapter("chapter").bubbles.single().editRevision)
            val replacement = cold.correct(refreshed, refreshed, 2, MemoryCorrectionEdit(translated = "new accepted personal text"))
            assertEquals(3, replacement.revision); assertEquals(listOf(3), replacement.revisions.map { it.revision })
            assertEquals(receipt.source.sourceSha256, SeriesMemoryStore.sha256(source))
            assertEquals(receipt.outputSha256, SeriesMemoryStore.sha256(output))
            assertFalse(journal.readText().contains("\"accepted personal text\""))
        } finally { root.deleteRecursively() }
    }

    private fun writePng(file: File, color: Int) {
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            FileOutputStream(file).use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.fd.sync() }
        } finally { bitmap.recycle() }
    }
}
