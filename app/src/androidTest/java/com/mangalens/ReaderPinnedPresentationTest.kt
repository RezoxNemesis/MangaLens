package com.mangalens

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.*
import com.mangalens.ui.MangaLensViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Controlled saved surface + an actually verified configuration pin; no model-quality claim. */
@RunWith(AndroidJUnit4::class)
class ReaderPinnedPresentationTest {
    @Test fun savedPinnedSurfaceRestoresAcrossFreshViewModelsAndTargetStyleChanges() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val refiner = TranslationOrezRefiner(app)
        val request = try { refiner.captureRequest(true, TranslationStyleProfile.NATURAL) } finally { refiner.close() }
        assertNotNull("The acceptance model pack must be actually verified before this pinned fixture.", request.pinnedModel)
        val id = UUID.randomUUID().toString().replace("-", "")
        val sources = File(app.filesDir, "chapters").apply { mkdirs() }
        val source = File(sources, "reader-pin-$id.png")
        val original = Bitmap.createBitmap(1000, 300, Bitmap.Config.ARGB_8888)
        Canvas(original).apply { drawColor(Color.WHITE); drawText("Hello MangaLens", 100f, 170f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 80f }) }
        var ink = 0
        for (y in 80..200) for (x in 80..940) if (Color.red(original.getPixel(x, y)) < 100) ink++
        assertTrue(ink > 100)
        source.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        original.recycle()
        val chapter = SavedChapter(id, "Pinned Reader fixture", "https://explicit.example/reader-pin-$id",
            listOf(ChapterPage(1, "local:reader-pin-$id", source.absolutePath)), updatedAt = Long.MAX_VALUE - 1)
        val settings = app.getSharedPreferences("mangalens_preferences", Context.MODE_PRIVATE)
        val ocr = app.getSharedPreferences("mangalens_ocr", Context.MODE_PRIVATE)
        val oldSettings = settings.all.filterKeys { it in setOf("translation_manga", "translation_target", "translation_style", "translation_custom_style") }
        val oldOcr = ocr.all.filterKeys { it in setOf("script", "high_accuracy", "preserve_style", "local_refinement") }
        var models = ViewModelStore()
        val library = ChapterLibrary(app)
        val store = ChapterTranslationStore.shared(app)
        try {
            assertTrue(settings.edit().putBoolean("translation_manga", true).putString("translation_target", "en")
                .putString("translation_style", "natural").putString("translation_custom_style", "").commit())
            assertTrue(ocr.edit().putString("script", "LATIN").putBoolean("high_accuracy", false)
                .putBoolean("preserve_style", true).putBoolean("local_refinement", true).commit())
            library.save(chapter)
            val config = ChapterTranslationConfig("en", ocrScript = "LATIN", highAccuracy = false,
                localRefinement = true, refinementRequest = request)
            val started = store.start(chapter, config, ownerRequestId = "reader-pin-$id")
            store.markRunning(started.id, started.generation)
            val page = store.beginPage(started.id, started.generation, 1)!!
            val clean = Bitmap.createBitmap(1000, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val output = store.createOutputFile(started.id, started.generation, 1).canonicalFile
            output.outputStream().use { assertTrue(clean.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            clean.recycle()
            assertTrue(store.commitPage(started.id, started.generation, page.copy(
                status = ChapterTranslationPageStatus.COMPLETED, cleanedPath = output.absolutePath,
                cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 1000, imageHeight = 300,
                lettering = listOf(SavedMangaLettering("Hello MangaLens", "Hello MangaLens", 80, 80, 940, 220,
                    "sans-serif", 0, Color.BLACK, 48f, "ALIGN_CENTER", 80, 80, 940, 220)))))
            val completed = store.finish(started.id, started.generation)!!
            assertEquals(ChapterTranslationStatus.COMPLETED, completed.status)
            val cold = ChapterTranslationStore(File(app.filesDir, "chapter_translations"), sources)
            val verified = cold.refresh(completed.id, completed.generation)!!
            assertFalse(verified.validationPending)
            assertEquals(config, verified.config)
            assertEquals(completed.pages, verified.pages)

            fun viewModel(): MangaLensViewModel {
                lateinit var value: MangaLensViewModel
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    value = ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory(app))[MangaLensViewModel::class.java]
                }
                return value
            }
            suspend fun restored(vm: MangaLensViewModel) {
                val state = withTimeout(60_000) { vm.state.first { it.activeChapter?.id == id &&
                    it.translationEnabled && it.overlays.keys == setOf(1) && !it.translating } }
                assertFalse(state.translationError)
                assertEquals(1, state.translationDone); assertEquals(1, state.translationTotal)
                assertEquals("Hello MangaLens", state.overlays.getValue(1).single().translatedText)
                assertEquals(mapOf(1 to output.absolutePath), state.translatedBackgrounds)
                assertEquals(completed.generation, store.get(completed.id)!!.generation)
                assertEquals(request, store.get(completed.id)!!.config.refinementRequest)
            }
            val first = viewModel(); restored(first)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
            models = ViewModelStore()
            val reopened = viewModel(); restored(reopened)
            withContext(Dispatchers.Main) { reopened.setTargetLanguage("hi") }
            assertFalse(reopened.state.value.translationEnabled)
            assertTrue(reopened.state.value.overlays.isEmpty())
            withContext(Dispatchers.Main) { reopened.setTargetLanguage("en") }
            restored(reopened)
            withContext(Dispatchers.Main) { reopened.setTranslationStyle("formal") }
            assertFalse(reopened.state.value.translationEnabled)
            assertTrue(reopened.state.value.overlays.isEmpty())
            withContext(Dispatchers.Main) { reopened.setTranslationStyle("natural") }
            restored(reopened)
            val pin = request.pinnedModel!!
            File(app.getExternalFilesDir(null) ?: app.filesDir, "qa/core-smoke/reader-pinned-presentation/result.json").apply {
                parentFile!!.mkdirs(); writeText(JSONObject().put("evidence_kind", "controlled saved surface and real verified configuration pin; no generated-quality claim")
                    .put("task", completed.id).put("generation", completed.generation).put("model_id", pin.modelId)
                    .put("model_sha256", pin.sha256).put("model_bytes", pin.bytes).put("source_sha256", page.sourceSha256)
                    .put("cleaned_sha256", completed.pages.single().cleanedSha256).put("recreated", true).toString(2))
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
            ChapterTranslationJobs.removeChapter(app, id)
            library.remove(id); source.delete()
            restore(settings, oldSettings, setOf("translation_manga", "translation_target", "translation_style", "translation_custom_style"))
            restore(ocr, oldOcr, setOf("script", "high_accuracy", "preserve_style", "local_refinement"))
        }
        Unit
    }

    private fun restore(preferences: android.content.SharedPreferences, values: Map<String, *>, keys: Set<String>) {
        val editor = preferences.edit()
        keys.forEach { key -> when (val value = values[key]) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            else -> editor.remove(key)
        } }
        assertTrue(editor.commit())
    }
}
