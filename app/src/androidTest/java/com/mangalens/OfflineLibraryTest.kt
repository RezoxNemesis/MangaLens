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
