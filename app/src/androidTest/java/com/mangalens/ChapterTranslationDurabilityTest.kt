package com.mangalens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.ChapterTranslationConfig
import com.mangalens.core.translation.ChapterTranslationPageStatus
import com.mangalens.core.translation.ChapterTranslationStatus
import com.mangalens.core.translation.ChapterTranslationStore
import com.mangalens.core.translation.SavedMangaLettering
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Android AtomicFile/BitmapFactory boundaries, using real private PNGs and fresh store instances. */
@RunWith(AndroidJUnit4::class)
class ChapterTranslationDurabilityTest {
    private class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "translation-test-" + UUID.randomUUID()).apply { mkdirs() }
        val sources = File(root, "chapters").apply { mkdirs() }
        val journals = File(root, "translations").apply { mkdirs() }
        val source = File(sources, "1.png").also { image(it, Color.WHITE) }
        fun store() = ChapterTranslationStore(journals, sources)
        fun chapter() = SavedChapter("b".repeat(32), "Durability fixture", "content://explicit-fixture",
            listOf(ChapterPage(1, "content://explicit-fixture", source.absolutePath)))
        fun image(file: File, color: Int) {
            val bitmap = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).drawColor(color)
                FileOutputStream(file).use { stream -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.fd.sync() }
            } finally { bitmap.recycle() }
        }
        override fun close() { root.deleteRecursively() }
    }

    @Test fun atomicBackupRecoveryRetainsSuccessfulLetteringAndRejectsStaleGeneration() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), ChapterTranslationConfig("hi", ocrScript = "LATIN", highAccuracy = false),
                requestedPages = listOf(1), ownerRequestId = "orez:atomic-recovery")
            store.markRunning(task.id, task.generation)
            val page = store.beginPage(task.id, task.generation, 1)!!
            val output = store.createOutputFile(task.id, task.generation, 1).also { f.image(it, Color.WHITE) }
            assertTrue(store.commitPage(task.id, task.generation, page.copy(status = ChapterTranslationPageStatus.PARTIAL,
                cleanedPath = output.absolutePath, cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 120, imageHeight = 200,
                lettering = listOf(SavedMangaLettering("Hello.", "नमस्ते।", 5, 10, 110, 70, "sans-serif", 0, Color.BLACK,
                    26f, "ALIGN_CENTER", 10, 15, 100, 55)), rejectedRegions = 1)))
            val finished = store.finish(task.id, task.generation)!!
            assertEquals(ChapterTranslationStatus.PARTIAL, finished.status)
            val file = File(f.journals, task.id + ".json")
            // Emulate the legacy AtomicFile interrupted-write form: old complete journal
            // remains in .bak while the replacement base contains incomplete bytes.
            val backup = File(f.journals, file.name + ".bak")
            assertTrue(file.renameTo(backup))
            file.writeText("{incomplete replacement")
            val reopened = f.store()
            assertTrue(reopened.get(task.id)!!.validationPending)
            assertNull(reopened.get(task.id)!!.pages.single().cleanedPath)
            val recovered = reopened.refresh(task.id)!!
            assertEquals(ChapterTranslationStatus.PARTIAL, recovered.status)
            assertEquals("नमस्ते।", recovered.pages.single().lettering.single().translated)
            assertEquals(listOf(1), recovered.requestedPages)
            assertEquals("orez:atomic-recovery", recovered.ownerRequestId)
            assertFalse(backup.exists())
            val resumed = reopened.resume(task.id, recovered.generation)!!
            assertNotEquals(task.generation, resumed.generation)
            assertEquals(recovered.requestedPages, resumed.requestedPages)
            assertEquals(recovered.ownerRequestId, resumed.ownerRequestId)
            assertNull(reopened.cancel(task.id, task.generation))
            assertTrue(f.source.exists())
        }
    }

    @Test fun incompleteNewAtomicWriteDoesNotBecomeAVisibleCheckpoint() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), ChapterTranslationConfig("en"))
            val file = File(f.journals, task.id + ".json")
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            stream.write("{incomplete new generation".toByteArray())
            stream.fd.sync()
            stream.close() // Process loss: neither finishWrite nor failWrite ran.
            val reopened = f.store().refresh(task.id)!!
            assertEquals(task.generation, reopened.generation)
            assertEquals(ChapterTranslationStatus.QUEUED, reopened.status)
            assertEquals(ChapterTranslationPageStatus.PENDING, reopened.pages.single().status)
        }
    }

    @Test fun validImageReplacementFailsChecksumBeforeSavedOverlayRestoration() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), ChapterTranslationConfig("hi"))
            store.markRunning(task.id, task.generation)
            val page = store.beginPage(task.id, task.generation, 1)!!
            val output = store.createOutputFile(task.id, task.generation, 1).also { f.image(it, Color.WHITE) }
            assertTrue(store.commitPage(task.id, task.generation, page.copy(status = ChapterTranslationPageStatus.COMPLETED,
                cleanedPath = output.absolutePath, cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 120, imageHeight = 200,
                lettering = listOf(SavedMangaLettering("Hello.", "नमस्ते।", 5, 10, 110, 70, "sans-serif", 0, Color.BLACK,
                    26f, "ALIGN_CENTER", 10, 15, 100, 55)))))
            store.finish(task.id, task.generation)
            f.image(output, Color.BLACK) // Still decodable with identical geometry.
            val reopened = f.store()
            assertFalse(reopened.states.value.single().hasTranslations)
            val checked = reopened.refresh(task.id)!!
            assertEquals(ChapterTranslationPageStatus.PENDING, checked.pages.single().status)
            assertTrue(checked.pages.single().lettering.isEmpty())
            assertNotEquals(ChapterTranslationStatus.COMPLETED, checked.status)
            assertTrue(f.source.exists())
        }
    }

    @Test fun explicitRemovalDeletesAtomicSidecarsAndGeneratedSurfaceWithoutTouchingOriginal() {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), ChapterTranslationConfig("hi"), ownerRequestId = "orez:delete-fixture")
            store.markRunning(task.id, task.generation)
            val page = store.beginPage(task.id, task.generation, 1)!!
            val output = store.createOutputFile(task.id, task.generation, 1).also { f.image(it, Color.WHITE) }
            val result = page.copy(status = ChapterTranslationPageStatus.COMPLETED,
                cleanedPath = output.absolutePath, cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 120, imageHeight = 200,
                lettering = listOf(SavedMangaLettering("Hello.", "नमस्ते।", 5, 10, 110, 70, "sans-serif", 0, Color.BLACK,
                    26f, "ALIGN_CENTER", 10, 15, 100, 55)))
            assertTrue(store.commitPage(task.id, task.generation, result))
            val originalHash = ChapterTranslationStore.sha256(f.source)
            val removal = store.beginChapterRemoval(task.chapterId)
            val journal = File(f.journals, task.id + ".json")
            val backup = File(journal.path + ".bak").apply { writeText("interrupted old journal") }
            val pending = File(journal.path + ".new").apply { writeText("interrupted next journal") }
            store.finishChapterRemoval(removal)
            assertFalse(journal.exists())
            assertFalse(backup.exists())
            assertFalse(pending.exists())
            assertFalse(output.exists())
            assertEquals(originalHash, ChapterTranslationStore.sha256(f.source))
            assertNull(f.store().get(task.id))
            assertFalse(store.commitPage(task.id, task.generation, result))
            try { store.createOutputFile(task.id, task.generation, 1); fail("Late worker recreated removed translation storage") }
            catch (_: CancellationException) { }
        }
    }
}
