package com.mangalens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.*
import com.mangalens.core.translation.memory.SeriesMemoryStore
import com.mangalens.ui.reader.MangaContinuousReader
import com.mangalens.ui.reader.ReaderMemoryPanel
import com.mangalens.ui.reader.TranslationOverlay
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Physical controls over actual saved PNGs/journals. This fixture makes no generated-quality claim. */
@RunWith(AndroidJUnit4::class)
class ReaderPersonalCorrectionUiTest {
    @Test fun saveColdRestoreRollbackAndRemoveRenderPersonalTextWithoutChangingNativeArtifacts() = coreScreenSmoke("reader-personal-corrections") {
        Fixture(File(context.cacheDir, UUID.randomUUID().toString().replace("-", "")), provenGeometry = true).use { fixture ->
            var scenario: ActivityScenario<MainActivity>? = null
            try {
                scenario = ActivityScenario.launch(MainActivity::class.java)
                var controller = fixture.attach(scenario)
                val before = capturePage("native-before")
                openCurrentPageEditor()
                node(By.desc("Original OCR: Hello.").pkg(context.packageName))
                node(By.desc("Original translation: नमस्ते।").pkg(context.packageName))
                typeIntoEditableUi(device, "Personal translation", "नमस्ते, मित्र।")
                scrollTo(By.desc("Save personal correction").pkg(context.packageName))
                tapSettled(By.desc("Save personal correction").pkg(context.packageName))
                waitFor("A physical Save must persist its personal edit and publish the real Reader overlay") {
                    controller.state.value.editor?.bubble?.editRevision == 1 &&
                        controller.state.value.personalOverlays[0]?.get(0)?.personal?.translated == "नमस्ते, मित्र।"
                }
                assertEquals("नमस्ते, मित्र।", runBlocking { SeriesMemoryStore(fixture.root).inspectChapter(fixture.chapter.id).bubbles.single().translatedText })
                fixture.assertNativeUnchanged()
                tapSettled(By.text("Close").pkg(context.packageName))
                // Collapse the same actual tools panel before comparing an unchanged page viewport.
                tapSettled(By.text("Reader tools").pkg(context.packageName))
                val after = capturePage("personal-after")
                assertEquals(before.bounds, after.bounds)
                assertTrue("The real native lettering must contain visible ink", before.ink > 30)
                assertTrue("The actual Reader renderer must change the visible lettering pixels", before.changedPixels(after) > 30)

                scenario.recreate()
                controller = fixture.attach(scenario)
                waitFor("A fresh native store/controller must restore the separate personal overlay") {
                    controller.state.value.personalOverlays[0]?.get(0)?.personal?.translated == "नमस्ते, मित्र।"
                }
                openCurrentPageEditor()
                scrollTo(By.desc("Personal translation").pkg(context.packageName))
                assertEquals("नमस्ते, मित्र।", readEditableUi(device, "Personal translation").text)
                scrollTo(By.desc("Rollback personal correction to original").pkg(context.packageName))
                tapSettled(By.desc("Rollback personal correction to original").pkg(context.packageName))
                waitFor("Rollback to original must advance history and remove the personal overlay") {
                    controller.state.value.editor?.bubble?.editRevision == 2 && controller.state.value.personalOverlays[0].orEmpty().isEmpty()
                }
                scrollTo(By.desc("Rollback personal correction to revision 1").pkg(context.packageName))
                tapSettled(By.desc("Rollback personal correction to revision 1").pkg(context.packageName))
                waitFor("Rollback to a saved revision must restore that exact personal translation") {
                    controller.state.value.editor?.bubble?.editRevision == 3 &&
                        controller.state.value.personalOverlays[0]?.get(0)?.personal?.translated == "नमस्ते, मित्र।"
                }
                scrollTo(By.desc("Remove personal correction").pkg(context.packageName))
                tapSettled(By.desc("Remove personal correction").pkg(context.packageName))
                waitFor("Physical Remove must retain the edit fence and hide the personal overlay") {
                    controller.state.value.editor?.bubble?.editRevision == 4 &&
                        controller.state.value.editor?.bubble?.correction == null && controller.state.value.personalOverlays[0].orEmpty().isEmpty()
                }
                val cold = runBlocking { SeriesMemoryStore(fixture.root).inspectChapter(fixture.chapter.id).bubbles.single() }
                assertEquals(4, cold.editRevision); assertNull(cold.correction)
                fixture.assertNativeUnchanged()
                capture("removed-history-retains-native")
            } finally { scenario?.close() }
        }
    }

    @Test fun legacySavedGeometryOffersRetranslationAndNeverInventsAnEditableRegion() = coreScreenSmoke("reader-correction-legacy") {
        Fixture(File(context.cacheDir, "reader-memory-legacy-${UUID.randomUUID()}"), provenGeometry = false).use { fixture ->
            var scenario: ActivityScenario<MainActivity>? = null
            try {
                scenario = ActivityScenario.launch(MainActivity::class.java)
                val controller = fixture.attach(scenario)
                openCorrections()
                node(By.text("Retranslate this page to establish its original image coordinates.").pkg(context.packageName))
                assertNull(device.findObject(By.desc("Correct bubble 1").pkg(context.packageName)))
                assertNull(controller.state.value.editor)
                assertTrue(runBlocking { SeriesMemoryStore(fixture.root).inspectChapter(fixture.chapter.id).bubbles.isEmpty() })
                fixture.assertNativeUnchanged()
                capture("legacy-retranslation-required")
            } finally { scenario?.close() }
        }
    }

    private fun CoreScreenSmokeSupport.openCorrections() {
        val tools = By.text("Reader tools").pkg(context.packageName)
        if (device.findObject(tools) == null) {
            val page = node(By.desc("Page 0").pkg(context.packageName)).visibleBounds
            device.click(page.centerX(), page.centerY())
        }
        tapSettled(tools)
        scrollTo(By.desc("Correct current page").pkg(context.packageName))
        tapSettled(By.desc("Correct current page").pkg(context.packageName))
    }

    private fun CoreScreenSmokeSupport.openCurrentPageEditor() {
        openCorrections()
        tapSettled(By.desc("Correct bubble 1").pkg(context.packageName))
        node(By.desc("Original OCR: Hello.").pkg(context.packageName))
    }

    private fun CoreScreenSmokeSupport.capturePage(label: String): PageFrame {
        // Wait for the actual layout/lettering animation, then sample the page's physical bounds.
        SystemClock.sleep(350)
        val bounds = Rect(node(By.desc("Page 0").pkg(context.packageName)).visibleBounds)
        val screenshot = BitmapFactory.decodeFile(capture(label).absolutePath) ?: error("Unreadable QA screenshot")
        try {
            check(bounds.width() > 0 && bounds.height() > 0 && bounds.left >= 0 && bounds.top >= 0 &&
                bounds.right <= screenshot.width && bounds.bottom <= screenshot.height)
            val pixels = IntArray(bounds.width() * bounds.height())
            screenshot.getPixels(pixels, 0, bounds.width(), bounds.left, bounds.top, bounds.width(), bounds.height())
            return PageFrame(bounds, pixels)
        } finally { screenshot.recycle() }
    }

    private data class PageFrame(val bounds: Rect, val pixels: IntArray) {
        val ink: Int get() = pixels.count { Color.red(it) < 100 && Color.green(it) < 100 && Color.blue(it) < 100 }
        fun changedPixels(other: PageFrame): Int = pixels.indices.count { pixels[it] != other.pixels[it] }
    }

    private class Fixture(val root: File, provenGeometry: Boolean) : AutoCloseable {
        private val sources = File(root, "chapters").apply { check(mkdirs()) }
        private val journals = File(root, "chapter_translations")
        private val source = File(sources, "original.png").apply { writePng(this, "Hello.") }
        val chapter = SavedChapter(UUID.randomUUID().toString().replace("-", ""), "Personal Reader fixture", "local:personal-reader", listOf(ChapterPage(0, "local:original", source.absolutePath)))
        private val config = ChapterTranslationConfig("hi", ocrScript = "LATIN", highAccuracy = false)
        private val store = ChapterTranslationStore(journals, sources)
        private val lettering = SavedMangaLettering("Hello.", "नमस्ते।", 100, 100, 900, 300, "sans-serif", 0, Color.BLACK, 64f,
            "ALIGN_CENTER", 100, 100, 900, 300, originalSourceBounds = if (provenGeometry) SavedOriginalSourceBounds(1, 100, 100, 900, 300) else null)
        private val output: File
        private val task: ChapterTranslationTask
        private val nativeBytes: Map<File, ByteArray>
        private val scopes = mutableListOf<CoroutineScope>()
        init {
            val started = store.start(chapter, config, ownerRequestId = "reader-ui:fixture")
            store.markRunning(started.id, started.generation)
            val page = store.beginPage(started.id, started.generation, 0)!!
            output = store.createOutputFile(started.id, started.generation, 0).apply { writePng(this, null) }
            check(store.commitPage(started.id, started.generation, page.copy(status = ChapterTranslationPageStatus.COMPLETED,
                cleanedPath = output.absolutePath, cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 1000, imageHeight = 400,
                lettering = listOf(lettering), originalWidth = if (provenGeometry) 1000 else null, originalHeight = if (provenGeometry) 400 else null)))
            task = store.finish(started.id, started.generation)!!
            check(task.status == ChapterTranslationStatus.COMPLETED)
            nativeBytes = listOf(source, output, File(journals, "${task.id}.json")).associateWith { it.readBytes() }
        }
        fun attach(scenario: ActivityScenario<MainActivity>): ReaderMemoryController {
            val coldStore = ChapterTranslationStore(journals, sources)
            val coldTask = runBlocking { coldStore.refresh(task.id, task.generation)!! }
            check(!coldTask.validationPending)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also(scopes::add)
            val controller = ReaderMemoryController(root, scope, { coldStore })
            scenario.onActivity { activity ->
                controller.bindAccepted(coldTask, ReaderTranslationPresentation.receipt(coldTask))
                activity.setContent {
                    MaterialTheme {
                        DisposableEffect(controller) { controller.enterReader(); onDispose { controller.leaveReader() } }
                        val memory by controller.state.collectAsState()
                        val compositionScope = rememberCoroutineScope()
                        DisposableEffect(compositionScope) {
                            scopes.add(compositionScope)
                            onDispose { }
                        }
                        MangaContinuousReader(chapter.title, chapter.pages, chapterId = chapter.id, translated = true,
                            overlays = mapOf(0 to listOf(TranslationOverlay(OcrRegion("Hello.", 100, 100, 900, 300), "नमस्ते।",
                                imageWidthPx = 1000, imageHeightPx = 400, lettering = lettering))), translatedBackgrounds = mapOf(0 to output.absolutePath),
                            onTranslate = {}, onDownload = {}, onMenu = {}, onLongPressPage = {},
                            onVisiblePage = controller::onVisiblePage, onCorrectPage = { index -> compositionScope.launch { controller.openPage(index) } },
                            personalOverlays = memory.personalOverlays)
                        ReaderMemoryPanel(controller, onRetranslate = {})
                    }
                }
            }
            return controller
        }
        fun assertNativeUnchanged() { nativeBytes.forEach { (file, bytes) -> assertArrayEquals("Personal UI changed native artifact ${file.name}", bytes, file.readBytes()) } }
        override fun close() { runBlocking { scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() } }; root.deleteRecursively() }
        companion object {
            private fun writePng(file: File, text: String?) {
                check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                val bitmap = Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(bitmap).apply { drawColor(Color.WHITE); text?.let { drawText(it, 100f, 230f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f }) } }
                    FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() }
                } finally { bitmap.recycle() }
            }
        }
    }
}
