package com.mangalens

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.orez.OrezTranslationEntity
import com.mangalens.ui.MangaLensViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the same chapter job, overlays, progress and page retry used by Reader. */
@RunWith(AndroidJUnit4::class)
class ReaderTranslationRecoveryTest {
    @Test fun damagedPageReportsPartialFailureAndCanBeRetriedWithoutLosingOtherPages() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val prefs = app.getSharedPreferences("mangalens_ocr", Context.MODE_PRIVATE)
        val oldScript = prefs.getString("script", "AUTO")
        val oldAccuracy = prefs.getBoolean("high_accuracy", true)
        prefs.edit().putString("script", "LATIN").putBoolean("high_accuracy", false).commit()
        val id = ChapterLibrary.id("qa:reader-translation-recovery")
        val directory = File(app.filesDir, "chapters").apply { mkdirs() }
        val files = (1..4).map { File(directory, "qa_translation_recovery_$it.png") }
        val store = ViewModelStore()
        lateinit var viewModel: MangaLensViewModel
        val settings = app.getSharedPreferences("mangalens_preferences", Context.MODE_PRIVATE)
        val oldEnabled = settings.getBoolean("translation_manga", false)
        val database = OrezRoomDatabase.get(app)
        try {
            files.forEach { writeTextPage(it) }
            files[1].writeText("This is a damaged image")
            val blank = Bitmap.createBitmap(1000, 300, Bitmap.Config.ARGB_8888)
            blank.eraseColor(Color.WHITE)
            files[3].outputStream().use { blank.compress(Bitmap.CompressFormat.PNG, 100, it) }
            blank.recycle()
            val pages = files.mapIndexed { index, file -> ChapterPage(index + 1, "local:qa:$index", file.absolutePath) }
            ChapterLibrary(app).save(SavedChapter(id, "Translation recovery fixture", "", pages))
            // A real stored translation avoids optional model/network dependence in this recovery test.
            database.datasets().upsertTranslation(OrezTranslationEntity(
                key = "qa-reader-recovery", source = "Hello MangaLens", target = "Hello MangaLens",
                targetLanguage = "en", style = "natural", scope = "chapter:$id"
            ))
            instrumentation.runOnMainSync {
                viewModel = MangaLensViewModel(app)
                store.put("qa", viewModel)
            }
            withTimeout(30_000) { viewModel.state.first { state -> state.library.any { it.id == id } } }
            instrumentation.runOnMainSync {
                viewModel.openSavedChapter(id)
                viewModel.setMangaTranslationEnabled(true)
            }
            withTimeout(30_000) { viewModel.state.first { it.pages.size == 4 && it.activeChapter?.id == id } }
            instrumentation.runOnMainSync { viewModel.translateChapter("en") }
            val completed = withTimeout(120_000) {
                viewModel.state.first { !it.translating && it.translationDone == 4 }
            }
            assertEquals(4, completed.translationTotal)
            assertTrue(completed.translationEnabled)
            assertTrue(completed.translationError)
            assertEquals(setOf(1, 3), completed.overlays.keys)
            assertTrue(completed.error.orEmpty().contains("1 of 4"))
            assertTrue(completed.error.orEmpty().contains("Retry pages 2"))
            val firstPage = completed.overlays[1]
            val thirdPage = completed.overlays[3]

            instrumentation.runOnMainSync {
                viewModel.pauseTranslation(true)
                viewModel.translatePage(ChapterPage(5, "local:unavailable", null), "en")
            }
            assertFalse(viewModel.state.value.translating)
            assertFalse(viewModel.state.value.translationPaused)
            assertNotNull(viewModel.state.value.error)

            writeTextPage(files[1])
            instrumentation.runOnMainSync { viewModel.translatePage(pages[1], "en") }
            val repaired = withTimeout(120_000) {
                viewModel.state.first { !it.translating && it.overlays.containsKey(2) }
            }
            assertNull(repaired.error)
            assertFalse(repaired.translationError)
            assertEquals(setOf(1, 2, 3), repaired.overlays.keys)
            assertEquals(firstPage, repaired.overlays[1])
            assertEquals(thirdPage, repaired.overlays[3])
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            ChapterLibrary(app).remove(id)
            files.forEach { it.delete() }
            database.openHelper.writableDatabase.execSQL("DELETE FROM orez_translations WHERE scope = ?", arrayOf("chapter:$id"))
            prefs.edit().putString("script", oldScript).putBoolean("high_accuracy", oldAccuracy).commit()
            settings.edit().putBoolean("translation_manga", oldEnabled).commit()
        }
    }

    private fun writeTextPage(file: File) {
        val bitmap = Bitmap.createBitmap(1000, 300, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f }
            Canvas(bitmap).apply { drawColor(Color.WHITE); drawText("Hello MangaLens", 50f, 160f, paint) }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
