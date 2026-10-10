package com.mangalens.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Actual original PNG pixels and decoder. Saved fixture text is not generated OCR/model evidence. */
@RunWith(AndroidJUnit4::class)
class ReaderBubbleOriginalCropInstrumentedTest {
    @Test fun originalPixelPreviewUsesPersistedBoundsInsteadOfTheCleanedWhiteSurface() = runBlocking {
        Fixture().use { fixture ->
            val inspection = requireNotNull(fixture.inspect())
            val accepted = requireNotNull(fixture.adapter.tryAcceptInspection(inspection))
            assertTrue(accepted.hasOriginalCrop)
            val crop = requireNotNull(ReaderBubbleOriginalCropLoader(fixture.sources).open(inspection) {
                fixture.adapter.tryAcceptInspection(inspection) != null
            })
            try {
                assertEquals(fixture.bounds.right - fixture.bounds.left, crop.bitmap.width)
                assertEquals(fixture.bounds.bottom - fixture.bounds.top, crop.bitmap.height)
                assertEquals(Color.BLUE, crop.bitmap.getPixel(0, 0))
                assertEquals(Color.BLUE, crop.bitmap.getPixel(crop.bitmap.width / 2, crop.bitmap.height / 2))
                assertTrue(crop.isSourceCurrent())
                fixture.assertUnchanged()
            } finally { crop.close() }
            assertTrue(crop.bitmap.isRecycled)
            assertFalse(crop.isSourceCurrent())
            crop.close()
            fixture.assertUnchanged()
        }
    }

    @Test fun identicalByteOriginalReplacementCannotAcquireAHeldInspectionCrop() = runBlocking {
        Fixture().use { fixture ->
            val inspection = requireNotNull(fixture.inspect())
            assertNotNull(fixture.adapter.tryAcceptInspection(inspection))
            val replacement = File(fixture.sources, "replacement.png")
            FileOutputStream(replacement).use { it.write(fixture.source.readBytes()); it.fd.sync() }
            Files.move(replacement.toPath(), fixture.source.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            assertNull(fixture.adapter.tryAcceptInspection(inspection))
            assertNull(ReaderBubbleOriginalCropLoader(fixture.sources).open(inspection) {
                fixture.adapter.tryAcceptInspection(inspection) != null
            })
            fixture.assertUnchanged()
        }
    }

    @Test fun legacyTextInspectionCannotInventAnOriginalCropFromItsSampledCoordinates() = runBlocking {
        Fixture(legacy = true).use { fixture ->
            val inspection = requireNotNull(fixture.inspect())
            assertEquals("Saved fixture source", requireNotNull(fixture.adapter.tryAcceptInspection(inspection)).originalOcr)
            assertNull(inspection.source)
            assertNull(ReaderBubbleOriginalCropLoader(fixture.sources).open(inspection) {
                fixture.adapter.tryAcceptInspection(inspection) != null
            })
            fixture.assertUnchanged()
        }
    }

    private class Fixture(legacy: Boolean = false) : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "reader-bubble-crop-").toFile()
        val sources = File(root, "chapters").apply { check(mkdirs()) }
        private val journals = File(root, "chapter_translations")
        val bounds = OriginalMangaGeometry.fromSampled(25, 25, 75, 75, 100, 127, 401, 509)
        val source = File(sources, "actual-original.png").apply { writePng(this, 401, 509, true) }
        private val chapter = SavedChapter(UUID.randomUUID().toString().replace("-", ""), "Crop fixture", "local:crop-fixture",
            listOf(ChapterPage(0, "local:actual-original", source.absolutePath)))
        private val store = ChapterTranslationStore(journals, sources)
        private val authority = ReaderMemoryPublicationAuthority()
        val adapter = NativeMemoryPublicationAdapter(root, store, authority)
        private val lettering = SavedMangaLettering("Saved fixture source", "Saved fixture translation", 25, 25, 75, 75,
            "sans-serif", 0, Color.BLACK, 12f, "ALIGN_CENTER", 25, 25, 75, 75,
            originalSourceBounds = if (legacy) null else bounds)
        private val presentation: ReaderMemoryPresentation
        private val artifacts: Map<File, ByteArray>
        init {
            val started = store.start(chapter, ChapterTranslationConfig("en", ocrScript = "LATIN", highAccuracy = false),
                ownerRequestId = "reader-crop:fixture")
            store.markRunning(started.id, started.generation)
            val page = requireNotNull(store.beginPage(started.id, started.generation, 0))
            val output = store.createOutputFile(started.id, started.generation, 0).apply { writePng(this, 100, 127, false) }
            check(store.commitPage(started.id, started.generation, page.copy(status = ChapterTranslationPageStatus.COMPLETED,
                cleanedPath = output.absolutePath, cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 100, imageHeight = 127,
                lettering = listOf(lettering), originalWidth = if (legacy) null else 401, originalHeight = if (legacy) null else 509)))
            val task = requireNotNull(store.finish(started.id, started.generation))
            check(task.status == ChapterTranslationStatus.COMPLETED)
            presentation = authority.activate(ReaderTranslationPresentation.receipt(task))
            artifacts = listOf(source, output, File(journals, "${task.id}.json")).associateWith { it.readBytes() }
        }
        suspend fun inspect() = adapter.inspectSavedBubble(presentation, 0, 0, lettering)
        fun assertUnchanged() {
            artifacts.forEach { (file, original) -> assertArrayEquals("Read preview changed ${file.name}", original, file.readBytes()) }
            assertFalse("Read inspection/preview must not create personal memory", File(root, "reader_memory").exists())
        }
        private fun writePng(file: File, width: Int, height: Int, original: Boolean) {
            check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(if (original) Color.RED else Color.WHITE)
                if (original) canvas.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
                    Paint().apply { color = Color.BLUE; style = Paint.Style.FILL })
                FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() }
            } finally { bitmap.recycle() }
        }
        override fun close() { root.deleteRecursively() }
    }
}
