package com.mangalens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.*
import com.mangalens.orez.OrezModelPin
import com.mangalens.orez.OrezRoomDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual AtomicFile/PNG/Room boundaries; controlled pin metadata is not a model inference claim. */
@RunWith(AndroidJUnit4::class)
class ChapterRefinementPersistenceTest {
    @Test fun coldJournalAndScopedRoomKeepCapturedModelAndStyleIdentity() = coreScreenSmoke("chapter-refinement-persistence") {
        val token = UUID.randomUUID().toString().replace("-", "")
        val root = File(context.filesDir, "chapter-refinement-persistence-$token").apply { mkdirs() }
        val sources = File(root, "chapters").apply { mkdirs() }
        val journals = File(root, "translations").apply { mkdirs() }
        val source = File(sources, "1.png")
        val bitmap = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.WHITE); source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val chapter = SavedChapter(token, "Captured pin metadata fixture", "content://fixture",
            listOf(ChapterPage(1, "content://fixture", source.absolutePath)))
        val request = TranslationRefinementRequest(true, TranslationStyleProfile.NATURAL,
            OrezModelPin("fixture-only", "a".repeat(64), 240_000_000))
        val config = ChapterTranslationConfig("hi-latn", localRefinement = true, refinementRequest = request)
        val scope = "chapter:$token"
        try {
            runBlocking { withTimeout(90_000) {
                val store = ChapterTranslationStore(journals, sources)
                val task = store.start(chapter, config, ownerRequestId = "qa:$token")
                val draft = HinglishTranslationOutput.fromHindiDraft("A room with no windows.", "बिना खिड़कियों वाला कमरा।")
                ChapterPageTranslator(context, store, task).use { it.remember("A room with no windows.", draft) }
                store.pause(task.id, task.generation)
                val cold = ChapterTranslationStore(journals, sources)
                val reopened = requireNotNull(cold.refresh(task.id))
                assertEquals(request, reopened.config.refinementRequest)
                val resumed = requireNotNull(cold.resume(reopened.id, reopened.generation))
                assertEquals(request, resumed.config.refinementRequest)
                ChapterPageTranslator(context, cold, resumed).use { assertEquals(draft, it.recall("A room with no windows.")) }
                val differentPin = cold.start(chapter, config.copy(refinementRequest = request.copy(
                    pinnedModel = request.pinnedModel!!.copy(sha256 = "b".repeat(64)))))
                ChapterPageTranslator(context, cold, differentPin).use { assertNull(it.recall("A room with no windows.")) }
                val differentFlags = cold.start(chapter, config.copy(refinementRequest = request.copy(
                    style = request.style.copy(preserveNames = false))))
                ChapterPageTranslator(context, cold, differentFlags).use { assertNull(it.recall("A room with no windows.")) }
                val legacy = cold.start(chapter, config.copy(refinementRequest = null))
                ChapterPageTranslator(context, cold, legacy).use {
                    it.remember("A room with no windows.", draft)
                    assertNull("Legacy enabled task must not bypass its missing captured model through Room", it.recall("A room with no windows."))
                }
                assertEquals(ChapterTranslationStore.sha256(source), resumed.pages.single().sourceSha256)
            } }
        } finally {
            OrezRoomDatabase.get(context).openHelper.writableDatabase.delete("orez_translations", "scope = ?", arrayOf(scope))
            root.deleteRecursively()
        }
    }
}
