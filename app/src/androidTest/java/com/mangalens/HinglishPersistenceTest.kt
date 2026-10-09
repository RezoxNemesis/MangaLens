package com.mangalens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.ChapterPageTranslator
import com.mangalens.core.translation.ChapterTranslationConfig
import com.mangalens.core.translation.ChapterTranslationPageStatus
import com.mangalens.core.translation.ChapterTranslationStore
import com.mangalens.core.translation.HinglishTranslationOutput
import com.mangalens.core.translation.SavedMangaLettering
import com.mangalens.core.translation.TranslationMemoryCodec
import com.mangalens.core.translation.TranslationQualityPolicy
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.orez.OrezTranslationEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Real Android journals, PNGs, and chapter-scoped Room recall from controlled Hindi drafts. */
@RunWith(AndroidJUnit4::class)
class HinglishPersistenceTest {
    @Test fun shortNounsKeepHindiEvidenceAfterTranslatorCloseJournalReopenAndScopedRecall() = coreScreenSmoke("hinglish-persistence") {
        val token = UUID.randomUUID().toString().replace("-", "")
        val root = File(context.filesDir, "hinglish-persistence-$token").apply { mkdirs() }
        val sources = File(root, "chapters").apply { mkdirs() }
        val journals = File(root, "translations").apply { mkdirs() }
        val sourceFile = File(sources, "1.png").also(::writeSurface)
        val chapter = SavedChapter(token, "Controlled Hindi proof fixture", "content://explicit-fixture",
            listOf(ChapterPage(1, "content://explicit-fixture", sourceFile.absolutePath)))
        val config = ChapterTranslationConfig("hi-latn", localRefinement = false)
        val scope = "chapter:$token"
        val database = OrezRoomDatabase.get(context)
        val outputs = JSONArray()
        val report = JSONObject().put("source_sha", BuildConfig.SOURCE_SHA).put("cases", outputs)
            .put("assessment", "Controlled Hindi drafts verify journal and Room provenance; real ML Kit and dialogue naturalness are separate gates.")
        try {
            runBlocking {
                withTimeout(90_000L) {
                    val store = ChapterTranslationStore(journals, sources)
                    val task = store.start(chapter, config, requestedPages = listOf(1), ownerRequestId = "qa:$token")
                    store.markRunning(task.id, task.generation)
                    val page = store.beginPage(task.id, task.generation, 1)!!
                    val drafts = listOf("Fire!" to "आग!", "Power!" to "शक्ति!", "Sword!" to "तलवार!").associate { (source, hindi) ->
                        val draft = HinglishTranslationOutput.fromHindiDraft(source, hindi)
                        source to TranslationQualityPolicy.chooseDraft(source, draft, "I was beaten up.", "hi-latn")
                    }
                    ChapterPageTranslator(context, store, task).use { translator ->
                        for ((source, draft) in drafts) translator.remember(source, draft)
                    }
                    val output = store.createOutputFile(task.id, task.generation, 1).also(::writeSurface)
                    val letters = drafts.entries.mapIndexed { index, (source, draft) ->
                        outputs.put(JSONObject().put("source", source).put("hindi_draft", draft.hindiDraft).put("roman", draft.text))
                        SavedMangaLettering(source, draft.text, 5, 10 + index * 55, 110, 55 + index * 55,
                            "sans-serif", 0, Color.BLACK, 24f, "ALIGN_CENTER", 10, 15 + index * 55,
                            100, 50 + index * 55, savedHindiDraft = draft.hindiDraft)
                    }
                    assertTrue(store.commitPage(task.id, task.generation, page.copy(status = ChapterTranslationPageStatus.COMPLETED,
                        cleanedPath = output.absolutePath, cleanedSha256 = ChapterTranslationStore.sha256(output),
                        imageWidth = 120, imageHeight = 200, lettering = letters)))
                    store.finish(task.id, task.generation)

                    val reopenedStore = ChapterTranslationStore(journals, sources)
                    assertTrue(reopenedStore.get(task.id)!!.validationPending)
                    val restored = reopenedStore.refresh(task.id)!!
                    assertEquals(letters, restored.pages.single().lettering)
                    assertEquals(listOf(1), restored.requestedPages)
                    assertEquals("qa:$token", restored.ownerRequestId)
                    ChapterPageTranslator(context, reopenedStore, restored).use { translator ->
                        for ((source, draft) in drafts) {
                            assertEquals(draft, translator.recall(source))
                            val rawMemory = database.datasets().exactTranslationScoped(source, "hi-latn", config.style().memoryKey, scope)!!
                            assertNotEquals(draft.text, rawMemory)
                            assertEquals(draft, TranslationMemoryCodec.decode(source, rawMemory, "hi-latn"))
                            assertNull(database.datasets().exactTranslationScoped(source, "hi", config.style().memoryKey, scope))
                            assertNull(database.datasets().exactTranslationScoped(source, "hi-latn", "formal", scope))
                        }
                        // Replace only this fixture's Fire row with an unproven legacy
                        // noun. Recall must reject it instead of silently displaying it.
                        database.datasets().upsertTranslation(OrezTranslationEntity("qa-plain-$token", "Fire!", "aag!",
                            "hi-latn", config.style().memoryKey, scope))
                        assertNull(translator.recall("Fire!"))
                        val letter = letters.first()
                        assertTrue(TranslationQualityPolicy.isUsable(letter.source, letter.translated, "hi-latn", letter.savedHindiDraft))
                        assertFalse(TranslationQualityPolicy.isUsable(letter.source, "The fire is strong.", "hi-latn", letter.savedHindiDraft))
                    }
                    assertTrue(sourceFile.exists())
                }
            }
            report.put("status", "passed")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.toString())
            recordFailure(failure)
            throw failure
        } finally {
            database.openHelper.writableDatabase.delete("orez_translations", "scope = ?", arrayOf(scope))
            root.deleteRecursively()
            File(context.getExternalFilesDir(null), "qa/core-smoke/hinglish-persistence/outputs.json")
                .apply { parentFile!!.mkdirs() }.writeText(report.toString(2))
        }
    }

    private fun writeSurface(file: File) {
        val bitmap = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            FileOutputStream(file).use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.fd.sync()
            }
        } finally { bitmap.recycle() }
    }
}
