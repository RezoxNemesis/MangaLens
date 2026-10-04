package com.mangalens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.reader.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflineLibraryTest {
    @Test fun stoppedDownloadsCannotBeOverwrittenByLateWorkerUpdates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = com.mangalens.download.DownloadDatabase.get(context).downloads()
        val id = "qa-download-state"
        val item = com.mangalens.download.DownloadEntity(id, "https://fixture.example/media", "Fixture", "video/mp4")
        try {
            for (state in listOf(com.mangalens.download.DownloadState.PAUSED, com.mangalens.download.DownloadState.CANCELLED)) {
                dao.upsert(item.copy(state = state))
                assertEquals(0, dao.progressIfActive(id, 100, 200))
                assertEquals(0, dao.failIfActive(id, "late network failure"))
                assertEquals(0, dao.completeIfActive(id, "content://fixture/download", 200))
                assertEquals(0, dao.adaptiveStateIfActive(id, 100, 200, com.mangalens.download.DownloadState.DOWNLOADING, null))
                assertEquals(0, dao.adaptiveStateIfActive(id, 200, 200, com.mangalens.download.DownloadState.COMPLETED, null))
                assertEquals(state, dao.get(id)!!.state)
            }
            dao.upsert(item)
            assertEquals(1, dao.progressIfActive(id, 100, 200))
            assertEquals(1, dao.completeIfActive(id, "content://fixture/download", 200))
            assertEquals(0, dao.progressIfActive(id, 50, 200))
            assertEquals(com.mangalens.download.DownloadState.COMPLETED, dao.get(id)!!.state)
            assertEquals(0, dao.adaptiveStateIfActive(id, 50, 200, com.mangalens.download.DownloadState.DOWNLOADING, null))
            assertEquals(0, dao.resumeIfStopped(id, com.mangalens.download.DownloadState.DOWNLOADING))
            dao.upsert(item.copy(state = com.mangalens.download.DownloadState.FAILED))
            assertEquals(1, dao.resumeIfStopped(id, com.mangalens.download.DownloadState.DOWNLOADING))
            assertEquals(1, dao.adaptiveStateIfActive(id, 200, 200, com.mangalens.download.DownloadState.COMPLETED, null))
            dao.delete(id)
            assertEquals(0, dao.completeIfActive(id, "content://fixture/download", 200))
            assertEquals(0, dao.adaptiveStateIfActive(id, 200, 200, com.mangalens.download.DownloadState.COMPLETED, null))
            assertEquals(0, dao.resumeIfStopped(id, com.mangalens.download.DownloadState.DOWNLOADING))
            assertNull(dao.get(id))
        } finally { dao.delete(id) }
    }

    @Test fun bookmarksAndReadingStatusSurviveRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File(File(context.filesDir, "chapters").apply { mkdirs() }, "status_fixture.img").apply { writeBytes(byteArrayOf(1)) }
        val id = ChapterLibrary.id("qa:status")
        try {
            ChapterLibrary(context).save(SavedChapter(id, "My story", "", listOf(ChapterPage(1, "local:qa", image.absolutePath)), bookmarked = true, readingStatus = ReadingStatus.ON_HOLD))
            val restored = ChapterLibrary(context).list().first { it.id == id }
            assertTrue(restored.bookmarked)
            assertEquals(ReadingStatus.ON_HOLD, restored.readingStatus)
            ChapterLibrary(context).save(restored.copy(readingStatus = ReadingStatus.COMPLETED))
            assertEquals(ReadingStatus.COMPLETED, ChapterLibrary(context).list().first { it.id == id }.readingStatus)
        } finally { ChapterLibrary(context).remove(id); image.delete() }
    }

    @Test fun importsPdfPagesIntoPersistentOfflineLibrary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "qa_chapter.pdf")
        val document = android.graphics.pdf.PdfDocument()
        try {
            repeat(2) { index ->
                val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                val paint = android.graphics.Paint().apply { textSize = 30f }
                page.canvas.drawText("Chapter page ${index + 1}", 20f, 80f, paint)
                document.finishPage(page)
            }
            source.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
        val id = ChapterLibrary.id("qa:pdf")
        val imported = DocumentImporter.prepare(context, listOf(android.net.Uri.fromFile(source)))
        try {
            val pages = ProgressiveChapterRepository(context).persistLocalImages(imported.images, context)
            assertEquals(2, pages.size)
            ChapterLibrary(context).save(SavedChapter(id, imported.title, "", pages))
            imported.close()
            val restored = ChapterLibrary(context).list().first { it.id == id }
            assertEquals(2, restored.pages.size)
            restored.pages.forEach { page -> assertNotNull(android.graphics.BitmapFactory.decodeFile(page.localPath)) }
        } finally { imported.close(); ChapterLibrary(context).remove(id); source.delete() }
    }

    @Test fun downloadProviderGrantsOnlyDownloadFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(File(context.filesDir, "downloads").apply { mkdirs() }, "provider_fixture.txt").apply { writeText("fixture") }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.downloads", file)
            assertEquals("content", uri.scheme)
            assertEquals("fixture", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            try {
                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.downloads", File(context.filesDir, "private.json"))
                fail("Provider must not expose unrelated app files")
            } catch (_: IllegalArgumentException) { }
        } finally { file.delete() }
    }
    @Test fun changingChapterRetainsOfflinePagesAndRestoresProgress() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "chapters").apply { mkdirs() }
        val image = File(directory, "test_offline.img")
        image.writeBytes(byteArrayOf(1, 2, 3))
        val id = ChapterLibrary.id("test:offline")
        try {
            val page = ChapterPage(1, "https://fixture.example/chapter/page.png", image.absolutePath)
            val library = ChapterLibrary(context)
            library.save(SavedChapter(id, "Offline fixture", "https://fixture.example/chapter/1", listOf(page), 0, 123))
            val repository = ProgressiveChapterRepository(context)
            repository.restorePages(listOf(page))
            repository.clearChapterCache()
            assertTrue("Changing chapter must not delete saved images", image.isFile)
            val restored = ChapterLibrary(context).list().first { it.id == id }
            assertEquals(123, restored.scrollOffset)
            assertEquals(page, restored.pages.single())
        } finally {
            image.delete()
            File(context.filesDir, "chapter_library/$id.json").delete()
        }
    }
    @Test fun deletingChapterPreservesSharedOfflineImages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File(File(context.filesDir, "chapters").apply { mkdirs() }, "shared_test.img").apply { writeBytes(byteArrayOf(1)) }
        val first = ChapterLibrary.id("test:first")
        val second = ChapterLibrary.id("test:second")
        val library = ChapterLibrary(context)
        val page = ChapterPage(1, "local:test", image.absolutePath)
        try {
            library.save(SavedChapter(first, "First", "", listOf(page)))
            library.save(SavedChapter(second, "Second", "", listOf(page)))
            library.remove(first)
            assertTrue(image.isFile)
            library.remove(second)
            assertFalse(image.exists())
        } finally { library.remove(first); library.remove(second); image.delete() }
    }
    @Test fun latinOcrRecognizesARealBitmap() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("mangalens_ocr", android.content.Context.MODE_PRIVATE)
        val oldScript = prefs.getString("script", "AUTO")
        val oldAccuracy = prefs.getBoolean("high_accuracy", true)
        prefs.edit().putString("script", "LATIN").putBoolean("high_accuracy", false).commit()
        val image = android.graphics.Bitmap.createBitmap(1000, 300, android.graphics.Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = 72f; isAntiAlias = true }
        android.graphics.Canvas(image).apply { drawColor(android.graphics.Color.WHITE); drawText("Hello MangaLens", 50f, 160f, paint) }
        val engine = com.mangalens.engine.AdvancedTranslationEngine(context)
        try {
            val regions = engine.recognizeScriptAware(image)
            assertTrue("OCR did not read fixture text", regions.any { it.source.contains("Hello", ignoreCase = true) })
        } finally {
            engine.close(); image.recycle()
            prefs.edit().putString("script", oldScript).putBoolean("high_accuracy", oldAccuracy).commit()
        }
    }
    @Test fun webViewsCannotReadAppFiles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val web = android.webkit.WebView(instrumentation.targetContext)
            try {
                com.mangalens.core.web.SafeWebView.configure(web)
                assertFalse(web.settings.allowFileAccess)
                assertFalse(web.settings.allowContentAccess)
                assertEquals(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW, web.settings.mixedContentMode)
            } finally { web.destroy() }
        }
    }
}
