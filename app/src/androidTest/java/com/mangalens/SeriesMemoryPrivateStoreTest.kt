package com.mangalens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

/** App-private persistence/identity proof; this does not evaluate OCR or native translation quality. */
@RunWith(AndroidJUnit4::class)
class SeriesMemoryPrivateStoreTest {
    private fun fixture(block: suspend (File, MemoryPublicationReceipt, SeriesMemoryStore, (MemoryPublicationReceipt?) -> Unit) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "qa/series-memory-" + UUID.randomUUID()).apply { check(mkdirs()) }
        try {
            val source = File(root, "chapters/original.png"); writePage(source, "Jin, do not worry.")
            val output = File(root, "chapter_translations/task/source-hi.png"); writePage(output, "जिन, चिंता मत करो।")
            val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(source.absolutePath, size)
            val receipt = MemoryPublicationReceipt(MemorySourceProof("chapter-one", 1, source.absolutePath,
                SeriesMemoryStore.sha256(source), size.outWidth, size.outHeight, MemoryRegionBounds(30, 40, 470, 140)),
                "task-one", "generation-one", "hi", "faithful:private-test-model-identity", "Jin, do not worry.", "जिन, चिंता मत करो।",
                output.absolutePath, SeriesMemoryStore.sha256(output), "series-one")
            val guard = Any(); var current: MemoryPublicationReceipt? = receipt
            val store = SeriesMemoryStore(root, MemoryPublicationFence { expected, commit -> synchronized(guard) { check(expected == current); commit() } })
            store.createSeries("Fixture series", "series-one"); store.associateChapter("chapter-one", "series-one", 1); store.indexBubble(receipt, receipt)
            block(root, receipt, store) { next -> synchronized(guard) { current = next } }
        } finally { root.deleteRecursively() }
    }

    @Test fun correctionRoundTripsPrivatePngEvidenceAndLeavesBothPageArtifactsUnchanged() = fixture { root, receipt, store, _ ->
        val sourceHash = SeriesMemoryStore.sha256(File(receipt.source.sourcePath))
        val outputHash = SeriesMemoryStore.sha256(File(receipt.outputPath!!))
        val correction = store.correct(receipt, receipt, 0, MemoryCorrectionEdit(translated = "जिन, चिंता मत करो!"))
        val cold = SeriesMemoryStore(root)
        assertEquals(correction, cold.inspectChapter("chapter-one").bubbles.single().correction)
        assertEquals(receipt, cold.inspectChapter("chapter-one").bubbles.single().correction!!.original)
        assertEquals(sourceHash, SeriesMemoryStore.sha256(File(receipt.source.sourcePath)))
        assertEquals(outputHash, SeriesMemoryStore.sha256(File(receipt.outputPath)))
        val hit = cold.search("चिंता मत करो!").hits.single { it.kind == MemorySearchKind.CORRECTED_TRANSLATION }
        assertEquals(receipt.source, hit.source); assertEquals("series-one", hit.seriesId)
    }

    @Test fun replacedGenerationAndPrivateSourceCannotCommitOrReturnOldOwnerValues() = fixture { root, receipt, store, changeOwner ->
        changeOwner(receipt.copy(generation = "generation-two"))
        var rejected = false
        try { store.correct(receipt, receipt, 0, MemoryCorrectionEdit(translated = "जिन, चिंता मत करो!")) } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected); assertNull(SeriesMemoryStore(root).inspectChapter("chapter-one").bubbles.single().correction)
        changeOwner(receipt)
        File(receipt.source.sourcePath).outputStream().use { it.write(byteArrayOf(1, 2, 3)) }
        assertTrue(SeriesMemoryStore(root).search("Jin").hits.isEmpty())
        assertEquals(receipt.originalOcr, SeriesMemoryStore(root).inspectChapter("chapter-one").bubbles.single().receipt.originalOcr)
    }

    @Test fun explicitRemovalPersistsTombstoneWithoutRemovingSoleSourceOrOutput() = fixture { root, receipt, store, _ ->
        store.removeChapter("chapter-one")
        val cold = SeriesMemoryStore(root)
        assertTrue(cold.inspectChapter("chapter-one").removed); assertTrue(cold.search("Jin").hits.isEmpty())
        var rejected = false
        try { store.indexBubble(receipt, receipt) } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertTrue(File(receipt.source.sourcePath).isFile); assertTrue(File(receipt.outputPath!!).isFile)
    }

    private fun writePage(file: File, text: String) {
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val bitmap = Bitmap.createBitmap(500, 350, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply { drawColor(Color.WHITE); drawText(text, 35f, 100f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 28f }) }
            FileOutputStream(file).use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.fd.sync() }
        } finally { bitmap.recycle() }
    }
}
