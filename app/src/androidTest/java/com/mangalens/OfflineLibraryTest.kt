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
