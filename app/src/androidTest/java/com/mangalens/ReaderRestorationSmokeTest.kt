package com.mangalens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.ChapterTranslationConfig
import com.mangalens.core.translation.ChapterTranslationPageStatus
import com.mangalens.core.translation.ChapterTranslationStatus
import com.mangalens.core.translation.ChapterTranslationStore
import com.mangalens.core.translation.OcrRegion
import com.mangalens.core.translation.SavedMangaLettering
import com.mangalens.ui.reader.MangaContinuousReader
import com.mangalens.ui.reader.TranslationOverlay
import com.mangalens.ui.theme.MangaLensTheme
import com.mangalens.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Reader component + real atomic journal. This does not claim WorkManager dispatch acceptance. */
@RunWith(AndroidJUnit4::class)
class ReaderRestorationSmokeTest {
    @Test fun reopenedLetteringRendersWithoutPatchesAndReaderControlsRetainCompletedFiles() = coreScreenSmoke("reader-restoration") {
        val prefs = context.getSharedPreferences("mangalens_reader", Context.MODE_PRIVATE)
        val restore = restorePreferencesAfter(prefs, "default_mode", "mode_Reader restoration QA")
        val fixture = fixture(context)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            prefs.edit().putString("default_mode", "vertical").remove("mode_Reader restoration QA").commit()
            val journal = ChapterTranslationStore(fixture.translations, fixture.sources)
            val task = requireNotNull(runBlocking { journal.refresh(fixture.taskId) })
            assertEquals(ChapterTranslationStatus.PAUSED, task.status)
            assertEquals(1, task.processedPages)
            assertEquals(fixture.lettering, task.pages.first().lettering.single())
            val initialScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = initialScenario
            mount(initialScenario, fixture, journal)
            node(By.text("1 / 2 pages processed"))
            waitFor("Verified cleaned PNG did not replace the source") { greenPixels() > 20_000 }
            capture("restored-translated-vertical")
            assertVisibleLettering(capture("restored-lettering"), node(By.desc("Page 1")).visibleBounds, fixture.lettering)
            toggleOriginal("Show original page")
            waitFor("Original action did not restore the source surface") { redPixels() > 20_000 }
            capture("original-vertical")
            toggleOriginal("Show translated page")
            waitFor("Translation action did not restore the verified surface") { greenPixels() > 20_000 }
            capture("translated-again")
            tap(By.text("Reader tools"))
            tap(By.text("Horizontal LTR"))
            tap(By.text("Reader tools"))
            waitFor("Paged reader lost the cleaned surface") { greenPixels() > 20_000 }
            capture("translated-paged")
            val translatedBounds = Rect(node(By.desc("Page 1")).visibleBounds)
            toggleOriginal("Show original page")
            waitFor("Paged Original action did not restore source") { redPixels() > 20_000 }
            capture("original-paged")
            assertEquals("Original toggle changed fitted image geometry", translatedBounds, node(By.desc("Page 1")).visibleBounds)
            toggleOriginal("Show translated page")
            tap(By.desc("Resume chapter translation"))
            waitFor("Resume did not persist its new generation") { journal.get(fixture.taskId)?.status == ChapterTranslationStatus.QUEUED }
            tap(By.desc("Pause chapter translation"))
            waitFor("Pause did not persist") { journal.get(fixture.taskId)?.status == ChapterTranslationStatus.PAUSED }
            tap(By.desc("Cancel chapter translation"))
            waitFor("Cancel did not persist") { journal.get(fixture.taskId)?.status == ChapterTranslationStatus.CANCELLED }
            assertTrue("Cancel deleted completed cleaned output", fixture.cleaned.isFile)
            assertEquals("Reader controls changed original image bytes", fixture.sourceSha, ChapterTranslationStore.sha256(fixture.original))
            assertEquals("Reader controls changed completed cleaned bytes", fixture.cleanedSha, ChapterTranslationStore.sha256(fixture.cleaned))
            capture("cancel-retained-rendered-page")
            initialScenario.close()
            scenario = null
            val reopened = ChapterTranslationStore(fixture.translations, fixture.sources)
            val retained = requireNotNull(runBlocking { reopened.refresh(fixture.taskId) })
            assertEquals(ChapterTranslationStatus.CANCELLED, retained.status)
            assertTrue("Reopening after cancel lost completed lettering", retained.hasTranslations)
            val reopenedScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = reopenedScenario
            mount(reopenedScenario, fixture, reopened)
            node(By.text("1 / 2 pages processed"))
            waitFor("Fresh reader did not restore completed output after cancel") { greenPixels() > 20_000 }
            capture("reopened-after-cancel")
        } catch (failure: Throwable) {
            runCatching { recordFailure(failure) }
            throw failure
        } finally {
            scenario?.close()
            fixture.directory.deleteRecursively()
            restore()
        }
    }

    @Test fun unreadableCleanedPngFallsBackToSourceWithoutDrawingStoredTextOnSourceGlyphs() = coreScreenSmoke("reader-invalid-surface") {
        val prefs = context.getSharedPreferences("mangalens_reader", Context.MODE_PRIVATE)
        val restore = restorePreferencesAfter(prefs, "default_mode", "mode_Reader restoration QA")
        val fixture = fixture(context)
        val invalid = File(fixture.directory, "invalid.png").apply { writeText("not a PNG") }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            prefs.edit().putString("default_mode", "vertical").remove("mode_Reader restoration QA").commit()
            val saved = fixture.lettering
            val overlay = overlay(saved)
            assertTrue("Persisted restoration unexpectedly retained a patch Bitmap", overlay.patch == null)
            val fallbackScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = fallbackScenario
            fallbackScenario.onActivity { activity -> activity.setContent {
                MangaLensTheme(themeMode = ThemeMode.DARK) {
                    MangaContinuousReader("Reader restoration QA", fixture.chapter.pages, fixture.chapter.id,
                        translated = true, overlays = mapOf(1 to listOf(overlay)),
                        translatedBackgrounds = mapOf(1 to invalid.absolutePath),
                        onTranslate = {}, onDownload = {}, onMenu = {}, onLongPressPage = {})
                }
            } }
            node(By.desc("Page 1"))
            waitFor("Invalid cleaned surface did not fall back to readable source") { redPixels() > 20_000 }
            capture("invalid-png-source-fallback")
            val screenshot = capture("invalid-png-no-overlay")
            assertEquals("Stored lettering was painted over original source glyphs", 0,
                inkPixels(screenshot, node(By.desc("Page 1")).visibleBounds, saved))
            assertEquals("Fallback changed original bytes", fixture.sourceSha, ChapterTranslationStore.sha256(fixture.original))
        } catch (failure: Throwable) {
            runCatching { recordFailure(failure) }
            throw failure
        } finally {
            scenario?.close()
            fixture.directory.deleteRecursively()
            restore()
        }
    }

    @Test fun roundedDownsampledSurfaceKeepsMarkedLetteringRectanglesAlignedInVerticalAndPagedModes() = coreScreenSmoke("reader-rounded-geometry") {
        val prefs = context.getSharedPreferences("mangalens_reader", Context.MODE_PRIVATE)
        val restore = restorePreferencesAfter(prefs, "default_mode", "mode_Reader geometry QA", "text_scale")
        val directory = File(context.cacheDir, "reader-geometry-${UUID.randomUUID()}").apply { mkdirs() }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            prefs.edit().putString("default_mode", "vertical").remove("mode_Reader geometry QA").putFloat("text_scale", 1f).commit()
            val sources = File(directory, "sources").apply { mkdirs() }
            val original = File(sources, "original.png")
            writeMarkedImage(original, 1_000, 5_000, Color.RED)
            val sourceSha = ChapterTranslationStore.sha256(original)
            val chapter = SavedChapter(ChapterLibrary.id(directory.name), "Reader geometry QA", "qa:reader-geometry",
                listOf(ChapterPage(1, "local:qa:geometry", original.absolutePath)))
            val translations = File(directory, "translations")
            val journal = ChapterTranslationStore(translations, sources)
            val task = journal.start(chapter, ChapterTranslationConfig("hi"))
            requireNotNull(journal.markRunning(task.id, task.generation))
            val page = requireNotNull(journal.beginPage(task.id, task.generation, 1))
            val top = Rect(20, 260, 180, 340)
            val lower = Rect(20, 700, 180, 860)
            val saved = listOf(top, lower).map { bounds ->
                SavedMangaLettering("Hello!", "नमस्ते!", bounds.left, bounds.top, bounds.right, bounds.bottom,
                    "sans-serif", Typeface.BOLD, Color.BLACK, 24f, "ALIGN_CENTER", bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
            val cleaned = journal.createOutputFile(task.id, task.generation, 1)
            // Slightly different post-resampling aspect; both surfaces remain within the
            // allowed geometry tolerance. Lettering coordinates belong to this 200x1005 PNG.
            writeMarkedImage(cleaned, 200, 1_005, Color.WHITE, listOf(top to Color.GREEN, lower to Color.MAGENTA))
            assertTrue(journal.commitPage(task.id, task.generation, page.copy(status = ChapterTranslationPageStatus.COMPLETED,
                imageWidth = 200, imageHeight = 1_005, cleanedPath = cleaned.absolutePath,
                cleanedSha256 = ChapterTranslationStore.sha256(cleaned), lettering = saved)))
            val verified = requireNotNull(runBlocking { ChapterTranslationStore(translations, sources).refresh(task.id) }).pages.single()
            val activeScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activeScenario
            activeScenario.onActivity { activity -> activity.setContent {
                MangaLensTheme(themeMode = ThemeMode.DARK) {
                    MangaContinuousReader(chapter.title, chapter.pages, chapter.id,
                        translated = true, overlays = mapOf(1 to verified.lettering.map { overlay(it, verified.imageWidth, verified.imageHeight) }),
                        translatedBackgrounds = mapOf(1 to requireNotNull(verified.cleanedPath)),
                        onTranslate = {}, onDownload = {}, onMenu = {}, onLongPressPage = {})
                }
            } }
            waitFor("Marked cleaned surface did not load") { greenPixels() > 1_000 }
            hideReaderChrome()
            assertMarkedLettering(capture("rounded-vertical-alignment"), node(By.desc("Page 1")).visibleBounds, top, Color.GREEN)
            toggleOriginal("Show original page")
            waitFor("Rounded surface's Original action did not restore source") { redPixels() > 20_000 }
            capture("rounded-vertical-original")
            toggleOriginal("Show translated page")
            tap(By.text("Reader tools"))
            tap(By.text("Horizontal LTR"))
            tap(By.text("Reader tools"))
            waitFor("Paged image did not settle into its fitted box") { device.findObject(By.desc("Page 1"))?.visibleBounds?.width()?.let { it < device.displayWidth / 2 } == true }
            // Hide chrome so neither marked rectangle is covered by the reader dock.
            hideReaderChrome()
            val image = Rect(node(By.desc("Page 1")).visibleBounds)
            assertTrue("Cleaned paged box retained the source ratio", kotlin.math.abs(image.width().toFloat() / image.height() - 200f / 1_005) < 1f / image.height())
            val paged = capture("rounded-paged-alignment")
            assertMarkedLettering(paged, image, top, Color.GREEN)
            assertMarkedLettering(paged, image, lower, Color.MAGENTA)
            toggleOriginal("Show original page")
            waitFor("Paged original did not use source geometry") {
                val bounds = device.findObject(By.desc("Page 1"))?.visibleBounds ?: return@waitFor false
                kotlin.math.abs(bounds.width().toFloat() / bounds.height() - 1_000f / 5_000) < 1f / bounds.height() && redPixels() > 20_000
            }
            capture("rounded-paged-original")
            assertEquals("Geometry restoration changed source bytes", sourceSha, ChapterTranslationStore.sha256(original))
        } catch (failure: Throwable) {
            runCatching { recordFailure(failure) }
            throw failure
        } finally {
            scenario?.close()
            directory.deleteRecursively()
            restore()
        }
    }

    private data class Fixture(
        val directory: File, val sources: File, val translations: File,
        val chapter: SavedChapter, val taskId: String, val original: File,
        val cleaned: File, val lettering: SavedMangaLettering, val sourceSha: String, val cleanedSha: String
    )

    private fun fixture(context: Context): Fixture {
        val directory = File(context.cacheDir, "reader-restoration-${UUID.randomUUID()}").apply { mkdirs() }
        val sources = File(directory, "sources").apply { mkdirs() }
        val translations = File(directory, "translations").apply { mkdirs() }
        val original = File(sources, "first.png")
        val second = File(sources, "second.png")
        writeImage(original, Color.RED, "Hello!")
        writeImage(second, Color.BLUE, "Page two")
        val chapter = SavedChapter(ChapterLibrary.id(directory.name), "Reader restoration QA", "qa:reader-restoration",
            listOf(ChapterPage(1, "local:qa:first", original.absolutePath), ChapterPage(2, "local:qa:second", second.absolutePath)))
        val store = ChapterTranslationStore(translations, sources)
        val task = store.start(chapter, ChapterTranslationConfig("hi", localRefinement = false))
        requireNotNull(store.markRunning(task.id, task.generation))
        val first = requireNotNull(store.beginPage(task.id, task.generation, 1))
        val cleaned = store.createOutputFile(task.id, task.generation, 1)
        writeImage(cleaned, Color.GREEN)
        val saved = SavedMangaLettering("Hello!", "नमस्ते!", 120, 260, 520, 420, "sans-serif", Typeface.BOLD,
            Color.BLACK, 54f, "ALIGN_CENTER", 120, 260, 520, 420)
        val cleanedSha = ChapterTranslationStore.sha256(cleaned)
        assertTrue(store.commitPage(task.id, task.generation, first.copy(status = ChapterTranslationPageStatus.COMPLETED,
            cleanedPath = cleaned.absolutePath, cleanedSha256 = cleanedSha, imageWidth = 640, imageHeight = 960, lettering = listOf(saved))))
        requireNotNull(store.pause(task.id, task.generation))
        return Fixture(directory, sources, translations, chapter, task.id, original, cleaned, saved,
            ChapterTranslationStore.sha256(original), cleanedSha)
    }

    private fun mount(scenario: ActivityScenario<MainActivity>, fixture: Fixture, journal: ChapterTranslationStore) {
        scenario.onActivity { activity -> activity.setContent {
            val states by journal.states.collectAsState()
            val task = states.single { it.id == fixture.taskId }
            val scope = rememberCoroutineScope()
            MangaLensTheme(themeMode = ThemeMode.DARK) {
                MangaContinuousReader("Reader restoration QA", fixture.chapter.pages, fixture.chapter.id,
                    translated = task.hasTranslations,
                    translating = task.status in setOf(ChapterTranslationStatus.QUEUED, ChapterTranslationStatus.RUNNING),
                    overlays = task.pages.associate { page -> page.index to page.lettering.map { overlay(it, page.imageWidth, page.imageHeight) } },
                    translatedBackgrounds = task.pages.mapNotNull { page -> page.cleanedPath?.let { page.index to it } }.toMap(),
                    translationDone = task.processedPages, translationTotal = task.totalPages,
                    translationPaused = task.status == ChapterTranslationStatus.PAUSED,
                    onTranslationPaused = { paused -> scope.launch { withContext(Dispatchers.IO) {
                        val current = requireNotNull(journal.get(fixture.taskId))
                        if (paused) journal.pause(current.id, current.generation) else journal.resume(current.id, current.generation)
                    } } },
                    onTranslationCancelled = { scope.launch { withContext(Dispatchers.IO) {
                        val current = requireNotNull(journal.get(fixture.taskId))
                        journal.cancel(current.id, current.generation)
                    } } },
                    onTranslate = {}, onDownload = {}, onMenu = {}, onLongPressPage = {})
            }
        } }
    }

    private fun overlay(saved: SavedMangaLettering, width: Int = 640, height: Int = 960) = TranslationOverlay(
        OcrRegion(saved.source, saved.sourceLeft, saved.sourceTop, saved.sourceRight, saved.sourceBottom), saved.translated,
        imageWidthPx = width, imageHeightPx = height, lettering = saved)

    private fun writeMarkedImage(file: File, width: Int, height: Int, background: Int, marks: List<Pair<Rect, Int>> = emptyList()) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(background)
            val canvas = Canvas(bitmap)
            marks.forEach { (bounds, color) -> canvas.drawRect(bounds, Paint().apply { this.color = color }) }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun assertMarkedLettering(file: File, image: Rect, source: Rect, color: Int) {
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val measured = ReaderMarkerPixels.bounds(pixels, bitmap.width, bitmap.height, color)
            assertNotNull("Cleaned PNG's complete marked lettering rectangle was not visible", measured)
            val mark = requireNotNull(measured)
            val scale = image.width() / 200.0
            val expected = ReaderMarkerBounds(image.left + source.left * scale, image.top + source.top * scale,
                image.left + source.right * scale, image.top + source.bottom * scale)
            for ((actual, target) in listOf(mark.left to expected.left, mark.top to expected.top, mark.right to expected.right, mark.bottom to expected.bottom)) {
                assertTrue("Cleaned artwork and saved lettering coordinates diverged: actual=$mark expected=$expected",
                    kotlin.math.abs(actual - target) <= 2.0)
            }
            var ink = 0
            val inkBounds = Rect(kotlin.math.floor(mark.left).toInt(), kotlin.math.floor(mark.top).toInt(),
                kotlin.math.ceil(mark.right).toInt(), kotlin.math.ceil(mark.bottom).toInt())
            for (y in inkBounds.top until inkBounds.bottom) for (x in inkBounds.left until inkBounds.right) {
                val pixel = pixels[y * bitmap.width + x]
                if (Color.red(pixel) < 40 && Color.green(pixel) < 40 && Color.blue(pixel) < 40) ink++
            }
            assertTrue("Saved text did not render in the cleaned PNG's marked rectangle", ink > 100)
        } finally { bitmap.recycle() }
    }

    private fun writeImage(file: File, background: Int, text: String? = null) {
        val bitmap = Bitmap.createBitmap(640, 960, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(background)
            text?.let { Canvas(bitmap).drawText(it, 160f, 350f, Paint().apply { color = Color.WHITE; textSize = 54f }) }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun CoreScreenSmokeSupport.toggleOriginal(description: String) {
        if (device.findObject(By.desc(description)) == null) {
            if (device.findObject(By.text("Reader tools")) == null) {
                device.click(device.displayWidth / 2, device.displayHeight / 2)
                node(By.text("Reader tools"))
            }
            // A paused-task header remains visible after the compact dock times out.
            tap(By.text("Reader tools"))
            tap(By.text("Reader tools"))
        }
        tap(By.desc(description))
    }

    private fun CoreScreenSmokeSupport.hideReaderChrome() {
        if (device.findObject(By.text("Reader tools")) != null) device.click(device.displayWidth / 2, device.displayHeight / 2)
        waitFor("Reader chrome covered the geometry fixture") { device.findObject(By.text("Reader tools")) == null }
    }

    private fun CoreScreenSmokeSupport.redPixels() = countPixels { Color.red(it) > 230 && Color.green(it) < 25 && Color.blue(it) < 25 }
    private fun CoreScreenSmokeSupport.greenPixels() = countPixels { Color.green(it) > 230 && Color.red(it) < 25 && Color.blue(it) < 25 }

    private fun CoreScreenSmokeSupport.countPixels(predicate: (Int) -> Boolean): Int {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pixels.count(predicate)
        } finally { bitmap.recycle() }
    }

    private fun assertVisibleLettering(file: File, image: Rect, lettering: SavedMangaLettering) {
        assertTrue("Reopened saved text was not rendered inside its balloon", inkPixels(file, image, lettering) > 100)
    }

    private fun inkPixels(file: File, image: Rect, lettering: SavedMangaLettering): Int {
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
        return try {
            val scale = image.width() / 640f
            val left = (image.left + lettering.left * scale).toInt().coerceIn(0, bitmap.width - 1)
            val right = (image.left + lettering.right * scale).toInt().coerceIn(left + 1, bitmap.width)
            val top = (image.top + lettering.top * scale).toInt().coerceIn(0, bitmap.height - 1)
            val bottom = (image.top + lettering.bottom * scale).toInt().coerceIn(top + 1, bitmap.height)
            val width = right - left
            val pixels = IntArray(width * (bottom - top))
            bitmap.getPixels(pixels, 0, width, left, top, width, bottom - top)
            pixels.count { Color.red(it) < 40 && Color.green(it) < 40 && Color.blue(it) < 40 }
        } finally { bitmap.recycle() }
    }
}
