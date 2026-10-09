package com.mangalens

import android.graphics.BitmapFactory
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.mangalens.core.acquisition.StaticChapterAcquirer
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.reader.ProgressiveChapterRepository
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.*
import com.mangalens.ui.reader.MangaContinuousReader
import com.mangalens.ui.reader.TranslationOverlay
import com.mangalens.ui.theme.MangaLensTheme
import com.mangalens.ui.theme.ThemeMode
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/** Required public user fixture, using the real acquirer, transfer, library and translation worker. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UserSuppliedChapterAcceptanceTest {
    @Test fun test01SuppliedChapterDownloadsAllPagesAndReopensInTheReader() = coreScreenSmoke("user-manhwa-download") {
        val record = JSONObject().put("source", SOURCE).put("source_sha", BuildConfig.SOURCE_SHA)
        try {
            val chapter = runBlocking { withTimeout(900_000) { acquire(this@coreScreenSmoke, record) } }
            val reopened = ChapterLibrary(context).list().single { it.id == chapter.id }
            assertEquals(chapter.pages, reopened.pages)
            showReader(reopened, emptyMap(), emptyMap(), "hi") { capture("real-source-reader") }
            record.put("status", "passed")
        } catch (failure: Throwable) {
            record.put("status", "failed").put("failure", failure.toString())
            recordFailure(failure)
            throw failure
        } finally { report("user-manhwa-download", record) }
    }

    @Test fun test02SuppliedDialogueUsesHindiAndHinglishAndSurvivesColdJournalReopen() = coreScreenSmoke("user-manhwa-translation") {
        val record = JSONObject().put("source", SOURCE).put("source_sha", BuildConfig.SOURCE_SHA)
            .put("quality_assessment", "UNASSESSED: functional results require source-dialogue, meaning and lettering inspection.")
        val cases = JSONArray(); record.put("targets", cases)
        try {
            runBlocking {
                val chapter = withTimeout(900_000) { acquire(this@coreScreenSmoke, JSONObject()) }
                val store = ChapterTranslationStore.shared(context)
                for (target in listOf("hi", "hi-latn")) {
                    val output = JSONObject().put("target", target); cases.put(output)
                    val task = ChapterTranslationJobs.start(context, chapter,
                        ChapterTranslationConfig(targetLanguage = target, ocrScript = "LATIN", highAccuracy = true),
                        requestedPages = listOf(2), ownerRequestId = "qa:real-manhwa:${java.util.UUID.randomUUID()}",
                        forceReprocess = true)
                    output.put("task_id", task.id).put("generation", task.generation).put("owner", task.ownerRequestId)
                    val finished = withTimeout(720_000) {
                        while (true) {
                            val current = store.get(task.id) ?: error("Actual translation journal disappeared")
                            assertEquals("Another translation generation replaced this test", task.generation, current.generation)
                            assertEquals(task.ownerRequestId, current.ownerRequestId)
                            if (current.status !in setOf(ChapterTranslationStatus.QUEUED, ChapterTranslationStatus.RUNNING)) return@withTimeout current
                            delay(250)
                        }
                        @Suppress("UNREACHABLE_CODE") error("No terminal translation status")
                    }
                    val page = finished.pages.single { it.index == 2 }
                    output.put("task_status", finished.status.name).put("page_status", page.status.name)
                        .put("rejected_regions", page.rejectedRegions).put("error", page.error)
                    output.put("bubbles", JSONArray().apply { page.lettering.forEach { bubble ->
                        put(JSONObject().put("source", bubble.source).put("output", bubble.translated)
                            .put("hindi_draft", bubble.savedHindiDraft)
                            .put("bounds", JSONArray(listOf(bubble.left, bubble.top, bubble.right, bubble.bottom))))
                    } })
                    val requested = requireNotNull(finished.requestedPages).toSet()
                    assertEquals(setOf(2), requested)
                    assertTrue("Requested dialogue did not finish: $output",
                        finished.status in setOf(ChapterTranslationStatus.COMPLETED, ChapterTranslationStatus.PARTIAL))
                    assertTrue("A requested page is incomplete: $output",
                        finished.pages.filter { it.index in requested }.all { it.isComplete })
                    assertTrue("A partial task has unfinished requested pages: $output",
                        finished.pages.filterNot { it.isComplete }.all { it.index !in requested })
                    assertEquals(ChapterTranslationPageStatus.COMPLETED, page.status)
                    assertEquals("Source dialogue had rejected/untranslated regions: $output", 0, page.rejectedRegions)
                    assertTrue("Too few source bubbles reached translation", page.lettering.size >= 3)
                    assertTrue(page.lettering.all { TranslationQualityPolicy.isUsable(it.source, it.translated, target, it.savedHindiDraft) })
                    if (target == "hi-latn") assertTrue(page.lettering.none { b -> b.translated.any { it in '\u0900'..'\u097f' } })
                    else assertTrue(page.lettering.any { b -> b.translated.any { it in '\u0900'..'\u097f' } })
                    val cold = ChapterTranslationStore(File(context.filesDir, "chapter_translations"), File(context.filesDir, "chapters"))
                    val restored = cold.refresh(task.id) ?: error("Saved translation could not reopen")
                    val saved = restored.pages.single { it.index == 2 }
                    assertEquals(page.lettering, saved.lettering)
                    assertEquals(page.cleanedSha256, saved.cleanedSha256)
                    assertNotNull(saved.cleanedPath)
                    val overlays = saved.lettering.map { lettering -> TranslationOverlay(
                        region = OcrRegion(lettering.source, lettering.sourceLeft, lettering.sourceTop,
                            lettering.sourceRight, lettering.sourceBottom),
                        translatedText = lettering.translated, imageWidthPx = saved.imageWidth, imageHeightPx = saved.imageHeight,
                        lettering = lettering) }
                    showReader(chapter.copy(pages = listOf(chapter.pages.single { it.index == 2 })),
                            mapOf(2 to overlays), mapOf(2 to requireNotNull(saved.cleanedPath)), target) {
                        capture("real-dialogue-$target-reopened")
                    }
                }
            }
            record.put("status", "passed")
        } catch (failure: Throwable) {
            record.put("status", "failed").put("failure", failure.toString())
            recordFailure(failure)
            throw failure
        } finally { report("user-manhwa-translation", record) }
    }

    private suspend fun acquire(support: CoreScreenSmokeSupport, record: JSONObject): SavedChapter {
        val discovered = StaticChapterAcquirer().discover(SOURCE) ?: error("Supplied chapter could not be acquired through the real Android network")
        assertEquals("The supplied chapter must expose all fifteen reader pages", 15, discovered.pageUrls.size)
        val repository = ProgressiveChapterRepository(support.context)
        val pages = repository.persistDiscoveredPages(discovered.pageUrls, SOURCE)
        record.put("pages", JSONArray().apply { pages.forEach { page ->
            val file = File(requireNotNull(page.localPath))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            assertTrue(file.length() > 0 && bounds.outWidth > 0 && bounds.outHeight > 0)
            put(JSONObject().put("index", page.index).put("source", page.sourceUrl).put("bytes", file.length())
                .put("sha256", ChapterTranslationStore.sha256(file)).put("width", bounds.outWidth).put("height", bounds.outHeight))
        } })
        val library = ChapterLibrary(support.context)
        val id = ChapterLibrary.id(SOURCE)
        val previous = library.list().firstOrNull { it.id == id }
        val chapter = previous?.copy(title = discovered.title, pages = pages)
            ?: SavedChapter(id, discovered.title, SOURCE, pages)
        library.save(chapter)
        return chapter
    }

    private fun CoreScreenSmokeSupport.showReader(chapter: SavedChapter, overlays: Map<Int, List<TranslationOverlay>>,
        backgrounds: Map<Int, String>, target: String, inspect: CoreScreenSmokeSupport.() -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity -> activity.setContent { MangaLensTheme(themeMode = ThemeMode.DARK) {
                MangaContinuousReader(chapter.title, chapter.pages, chapterId = chapter.id,
                    translated = backgrounds.isNotEmpty(), overlays = overlays, translatedBackgrounds = backgrounds,
                    targetLanguage = target, onTranslate = {}, onDownload = {}, onMenu = {}, onLongPressPage = {})
            } } }
            node(By.descContains("Page ${chapter.pages.first().index}"), 45_000)
            inspect()
        } catch (failure: Throwable) {
            recordFailure(failure)
            throw failure
        } finally { scenario.close() }
    }

    private fun CoreScreenSmokeSupport.report(name: String, record: JSONObject) {
        File(context.getExternalFilesDir(null), "qa/core-smoke/$name/outputs.json")
            .apply { parentFile!!.mkdirs() }.writeText(record.toString(2))
    }
    companion object { private const val SOURCE = "https://demonicscans.org/title/Kidnapped-Dragons/chapter/63/1" }
}
