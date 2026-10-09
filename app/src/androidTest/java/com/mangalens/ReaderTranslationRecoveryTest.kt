package com.mangalens

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.mangalens.core.translation.ChapterTranslationJobs
import com.mangalens.core.translation.ChapterTranslationConfig
import com.mangalens.core.translation.ChapterTranslationPage
import com.mangalens.core.translation.ChapterTranslationPageStatus
import com.mangalens.core.translation.ChapterTranslationStatus
import com.mangalens.core.translation.ChapterTranslationStore
import com.mangalens.core.translation.ChapterTranslationTask
import com.mangalens.ui.MangaLensUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Exercises the same chapter job, overlays, progress and page retry used by Reader. */
@RunWith(AndroidJUnit4::class)
class ReaderTranslationRecoveryTest {
    @Test fun damagedPageReportsPartialFailureAndCanBeRetriedWithoutLosingOtherPages() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val prefs = app.getSharedPreferences("mangalens_ocr", Context.MODE_PRIVATE)
        val originalOcr = listOf("script", "high_accuracy", "preserve_style", "local_refinement").associateWith { prefs.all[it] }
        assertTrue(prefs.edit().putString("script", "LATIN").putBoolean("high_accuracy", false)
            .putBoolean("preserve_style", true).putBoolean("local_refinement", false).commit())
        val fixture = UUID.randomUUID().toString()
        val id = ChapterLibrary.id("qa:reader-translation-recovery:$fixture")
        val directory = File(app.filesDir, "chapters").apply { mkdirs() }
        val files = (1..4).map { File(directory, "qa_recovery_${fixture}_$it.png") }
        val store = ViewModelStore()
        lateinit var viewModel: MangaLensViewModel
        var viewModelCreated = false
        val settings = app.getSharedPreferences("mangalens_preferences", Context.MODE_PRIVATE)
        val originalSettings = listOf("translation_manga", "translation_target", "translation_style", "translation_custom_style")
            .associateWith { settings.all[it] }
        assertTrue(settings.edit().putBoolean("translation_manga", true).putString("translation_target", "en")
            .putString("translation_style", "natural").putString("translation_custom_style", "").commit())
        val database = OrezRoomDatabase.get(app)
        val translations = ChapterTranslationStore.shared(app)
        val configuration = ChapterTranslationConfig("en", ocrScript = "LATIN", highAccuracy = false)
        try {
            files.forEach { writeTextPage(it) }
            files[1].writeText("This is a damaged image")
            val blank = Bitmap.createBitmap(1000, 300, Bitmap.Config.ARGB_8888)
            blank.eraseColor(Color.WHITE)
            files[3].outputStream().use { blank.compress(Bitmap.CompressFormat.PNG, 100, it) }
            blank.recycle()
            for (index in listOf(0, 2)) assertHasActualGlyphPixels(files[index])
            val originalHashes = files.associate { it.canonicalPath to ChapterTranslationStore.sha256(it) }
            val pages = files.mapIndexed { index, file -> ChapterPage(index + 1, "local:qa:$index", file.absolutePath) }
            ChapterLibrary(app).save(SavedChapter(id, "Translation recovery fixture", "", pages))
            assertNull(translations.latest(id, "en"))
            // A real stored translation avoids optional model/network dependence in this recovery test.
            database.datasets().upsertTranslation(OrezTranslationEntity(
                key = "qa-reader-recovery:$fixture", source = "Hello MangaLens", target = "Hello MangaLens",
                targetLanguage = "en", style = "natural", scope = "chapter:$id"
            ))
            instrumentation.runOnMainSync {
                viewModel = MangaLensViewModel(app)
                store.put("qa", viewModel)
                viewModelCreated = true
            }
            withTimeout(30_000) { viewModel.state.first { state -> state.library.any { it.id == id } } }
            instrumentation.runOnMainSync {
                viewModel.openSavedChapter(id)
                viewModel.setMangaTranslationEnabled(true)
            }
            withTimeout(30_000) { viewModel.state.first { it.pages.size == 4 && it.activeChapter?.id == id } }
            instrumentation.runOnMainSync { viewModel.translateChapter("en") }
            val (partial, completed) = withTimeout(120_000) {
                val terminal = translations.states.first { tasks -> tasks.any { task ->
                    task.chapterId == id && task.config == configuration && task.processedPages == 4 && task.status in TERMINAL
                } }.single { it.chapterId == id && it.config == configuration }
                val surfacePages = terminal.pages.filter { it.lettering.isNotEmpty() && it.cleanedPath != null }.map { it.index }.toSet()
                val presented = viewModel.state.first { state -> !state.translating && state.translationDone == 4 &&
                    state.translationTotal == 4 && state.translationMessage == terminal.error && state.overlays.keys == surfacePages }
                terminal to presented
            }
            writeEvidence(app, "partial", partial, completed)
            assertEquals("Actual finished chapter must retain its two translated neighbours: ${partial.error}",
                ChapterTranslationStatus.PARTIAL, partial.status)
            assertFalse(partial.validationPending)
            assertNotNull(partial.ownerRequestId)
            assertEquals(mapOf(1 to ChapterTranslationPageStatus.COMPLETED, 2 to ChapterTranslationPageStatus.FAILED,
                3 to ChapterTranslationPageStatus.COMPLETED, 4 to ChapterTranslationPageStatus.NO_TEXT),
                partial.pages.associate { it.index to it.status })
            assertEquals(setOf(2), partial.pages.filter { !it.isComplete }.map { it.index }.toSet())
            assertTrue(partial.pages.single { it.index == 2 }.error.orEmpty().contains("decode source page 2"))
            assertNull(partial.pages.single { it.index == 4 }.error)
            assertEquals(originalHashes, partial.pages.associate { it.sourcePath!! to it.sourceSha256!! })
            val coldPartial = ChapterTranslationStore(File(app.filesDir, "chapter_translations"), directory)
                .refresh(partial.id, partial.generation)!!
            assertEquals(canonicalPagePaths(partial.pages), canonicalPagePaths(coldPartial.pages))
            assertEquals(partial.config, coldPartial.config)
            assertEquals(partial.status, coldPartial.status)
            assertEquals(partial.error, coldPartial.error)
            assertEquals(4, completed.translationTotal)
            assertTrue("Expected rendered pages 1 and 3; actual task: ${partial.status}: ${partial.error}", completed.translationEnabled)
            assertTrue(completed.translationError)
            assertEquals(setOf(1, 3), completed.overlays.keys)
            assertTrue(completed.translationMessage.orEmpty().contains("1 of 4"))
            assertEquals(setOf(1, 3), completed.translatedBackgrounds.keys)
            val firstPage = completed.overlays[1]
            val thirdPage = completed.overlays[3]

            instrumentation.runOnMainSync {
                viewModel.pauseTranslation(true)
                viewModel.translatePage(ChapterPage(5, "local:unavailable", null), "en")
            }
            assertFalse(viewModel.state.value.translating)
            assertFalse(viewModel.state.value.translationPaused)
            assertNotNull(viewModel.state.value.translationMessage)

            writeTextPage(files[1])
            val repairedSourceHash = ChapterTranslationStore.sha256(files[1])
            assertNotEquals(originalHashes[files[1].canonicalPath], repairedSourceHash)
            instrumentation.runOnMainSync { viewModel.translatePage(pages[1], "en") }
            val (task, repaired) = withTimeout(120_000) {
                val terminal = translations.states.first { tasks -> tasks.any { task ->
                    task.id == partial.id && task.generation != partial.generation && task.config == configuration &&
                        task.requestedPages == listOf(2) && task.processedPages == 4 && task.status in TERMINAL
                } }.single { it.id == partial.id && it.generation != partial.generation }
                val surfacePages = terminal.pages.filter { it.lettering.isNotEmpty() && it.cleanedPath != null }.map { it.index }.toSet()
                terminal to viewModel.state.first { state -> !state.translating && state.translationDone == 4 &&
                    state.translationMessage == terminal.error && state.overlays.keys == surfacePages }
            }
            writeEvidence(app, "repaired", task, repaired)
            assertEquals(ChapterTranslationStatus.COMPLETED, task.status)
            assertNotEquals(partial.generation, task.generation)
            assertNotEquals(partial.ownerRequestId, task.ownerRequestId)
            assertEquals(partial.config, task.config)
            assertEquals(listOf(2), task.requestedPages)
            assertEquals(repairedSourceHash, task.pages.single { it.index == 2 }.sourceSha256)
            for (index in listOf(1, 3, 4)) assertEquals(partial.pages.single { it.index == index }, task.pages.single { it.index == index })
            assertNull(repaired.translationMessage)
            assertFalse(repaired.translationError)
            assertEquals(setOf(1, 2, 3), repaired.overlays.keys)
            assertEquals(firstPage, repaired.overlays[1])
            assertEquals(thirdPage, repaired.overlays[3])
            assertEquals(setOf(1, 2, 3), repaired.translatedBackgrounds.keys)
            val coldRepaired = ChapterTranslationStore(File(app.filesDir, "chapter_translations"), directory)
                .refresh(task.id, task.generation)!!
            assertEquals(canonicalPagePaths(task.pages), canonicalPagePaths(coldRepaired.pages))
            assertEquals(task.config, coldRepaired.config)
            assertEquals(ChapterTranslationStatus.COMPLETED, coldRepaired.status)
            val repairedFile = repaired.translatedBackgrounds[2]!!
            // A new reader must restore the page-local repair from the same atomic task.
            instrumentation.runOnMainSync {
                store.clear()
                viewModel = MangaLensViewModel(app)
                store.put("qa-reopened", viewModel)
            }
            withTimeout(30_000) { viewModel.state.first { it.library.any { saved -> saved.id == id } } }
            instrumentation.runOnMainSync { viewModel.openSavedChapter(id) }
            val restored = withTimeout(30_000) { viewModel.state.first { it.overlays.keys == setOf(1, 2, 3) } }
            assertEquals(repairedFile, restored.translatedBackgrounds[2])
            assertEquals(repaired.overlays[2], restored.overlays[2])
            assertEquals(4, restored.translationDone)
            assertTrue(restored.overlays.values.flatten().all { it.patch == null && it.lettering != null })
            writeEvidence(app, "restored", coldRepaired, restored)
        } catch (failure: Throwable) {
            runCatching { writeEvidence(app, "failure", translations.latest(id, "en"),
                if (viewModelCreated) viewModel.state.value else null) }
            throw failure
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            ChapterTranslationJobs.removeChapter(app, id)
            ChapterLibrary(app).remove(id)
            files.forEach { it.delete() }
            database.openHelper.writableDatabase.execSQL("DELETE FROM orez_translations WHERE scope = ?", arrayOf("chapter:$id"))
            restorePreferences(prefs, originalOcr)
            restorePreferences(settings, originalSettings)
        }
    }

    // Android /data/user/0 and /data/data can name the same managed files. The cold
    // journal canonicalizes paths; every other source/output/lettering/status field stays exact.
    private fun canonicalPagePaths(pages: List<ChapterTranslationPage>) = pages.map { page -> page.copy(
        sourcePath = page.sourcePath?.let { File(it).canonicalPath },
        cleanedPath = page.cleanedPath?.let { File(it).canonicalPath }) }

    private fun restorePreferences(preferences: SharedPreferences, original: Map<String, Any?>) {
        assertTrue(preferences.edit().apply {
            original.forEach { (key, value) -> when (value) {
                null -> remove(key)
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                else -> error("Unexpected original fixture preference type for $key")
            } }
        }.commit())
    }

    private fun writeEvidence(context: Context, phase: String, task: ChapterTranslationTask?, state: MangaLensUiState?) {
        val destination = File(context.getExternalFilesDir(null) ?: context.filesDir, "qa/core-smoke/reader-translation-recovery").apply { mkdirs() }
        val details = JSONObject().put("phase", phase).put("evidence_kind", "actual worker, private source PNG, AtomicFile and scoped Room fixture")
        task?.let {
            details.put("task_id", it.id).put("generation", it.generation).put("owner", it.ownerRequestId)
                .put("status", it.status.name).put("config", it.config.toString()).put("error", it.error)
                .put("validation_pending", it.validationPending).put("requested_pages", it.requestedPages?.let(::JSONArray))
                .put("pages", JSONArray(it.pages.map { page ->
                    val source = page.sourcePath?.let(::File)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    if (source?.isFile == true) {
                        BitmapFactory.decodeFile(source.absolutePath, bounds)
                        source.copyTo(File(destination, "$phase-page${page.index}-source.png"), overwrite = true)
                    }
                    JSONObject().put("index", page.index).put("status", page.status.name)
                        .put("error", page.error).put("source_path", page.sourcePath).put("source_canonical_path", source?.canonicalPath)
                        .put("source_sha256", page.sourceSha256).put("cleaned_sha256", page.cleanedSha256)
                        .put("source_width", bounds.outWidth).put("source_height", bounds.outHeight)
                        .put("lettering", JSONArray(page.lettering.map { text -> JSONObject().put("source", text.source).put("output", text.translated) }))
                }))
        }
        state?.let {
            details.put("ui_translation_enabled", it.translationEnabled).put("ui_error", it.translationError)
                .put("ui_message", it.translationMessage).put("ui_processed", it.translationDone).put("ui_total", it.translationTotal)
                .put("ui_overlay_pages", JSONArray(it.overlays.keys.sorted())).put("ui_cleaned_pages", JSONArray(it.translatedBackgrounds.keys.sorted()))
                .put("ui_sources", JSONArray(it.pages.map { page -> JSONObject().put("index", page.index)
                    .put("path", page.localPath).put("canonical_path", page.localPath?.let { path -> File(path).canonicalPath }) }))
        }
        File(destination, "$phase.json").writeText(details.toString(2))
    }

    private fun assertHasActualGlyphPixels(source: File) {
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = true
        }) ?: throw AssertionError("Source fixture PNG did not decode: ${source.name}")
        try {
            assertEquals(1000, bitmap.width); assertEquals(300, bitmap.height)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Source fixture must contain drawn dark glyphs, not a blank image", pixels.count { pixel ->
                Color.red(pixel) < 64 && Color.green(pixel) < 64 && Color.blue(pixel) < 64
            } >= 100)
        } finally { bitmap.recycle() }
    }

    private companion object {
        val TERMINAL = setOf(ChapterTranslationStatus.COMPLETED, ChapterTranslationStatus.PARTIAL,
            ChapterTranslationStatus.FAILED, ChapterTranslationStatus.CANCELLED)
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
