package com.mangalens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.mangalens.core.reader.*
import com.mangalens.core.translation.*
import com.mangalens.core.translation.memory.*
import com.mangalens.ui.library.LibrarySettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real MainActivity Library→saved-text→Reader controls. Native records/PNGs are controlled fixtures, not OCR/model quality evidence. */
@RunWith(AndroidJUnit4::class)
class LibrarySavedTextUiTest {
    @Test fun physicalScopedSearchKeepsOriginalAndPersonalTextDistinctAndOpensActualSparsePageOrdinal() = coreScreenSmoke("library-saved-text-ui") {
        val id = UUID.randomUUID().toString().replace("-", "")
        val prefs = context.getSharedPreferences("mangalens_preferences", Context.MODE_PRIVATE)
        val restoreApp = restorePreferencesAfter(prefs, "translation_target", "translation_style", "translation_custom_style", "translation_manga")
        val readerPrefs = context.getSharedPreferences("mangalens_reader", Context.MODE_PRIVATE)
        val title = "Saved text QA $id"
        val restoreReader = restorePreferencesAfter(readerPrefs, "default_mode", "mode_$title")
        val libraryPrefs = context.getSharedPreferences(LibrarySettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val restoreLibrary = restorePreferencesAfter(libraryPrefs, LibrarySettingsStore.OPTIONS_KEY)
        val library = ChapterLibrary(context)
        val native = ChapterTranslationStore.shared(context)
        val files = listOf(7, 0).associateWith { index -> File(context.filesDir, "chapters/saved-text-$id-$index.png").apply { parentFile!!.mkdirs() } }
        fun writePng(file: File, text: String? = null) {
            val bitmap = Bitmap.createBitmap(480, 1_280, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                if (text != null) Canvas(bitmap).drawText(text, 40f, 150f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 42f })
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
        var task: ChapterTranslationTask? = null
        try {
            files.forEach { (index, file) -> writePng(file, if (index == 7) "Hello." else "Good night.") }
            val chapter = SavedChapter(id, title, "content://explicit-saved-text-qa/$id",
                listOf(7, 0).map { ChapterPage(it, "content://explicit-saved-text-qa/$id/$it", files.getValue(it).path) }, position = 1, scrollOffset = 137)
            val configuration = ChapterTranslationConfig("hi", ocrScript = "LATIN", highAccuracy = false, preserveStyle = false)
            val started = native.start(chapter, configuration, ownerRequestId = "saved-text-ui:$id")
            task = started
            assertNotNull(native.markRunning(started.id, started.generation))
            for (index in listOf(7, 0)) {
                val page = native.beginPage(started.id, started.generation, index)!!
                val output = native.createOutputFile(started.id, started.generation, index)
                writePng(output)
                val source = if (index == 7) "Hello." else "Good night."
                val translated = if (index == 7) "नमस्ते।" else "शुभ रात्रि।"
                val lettering = SavedMangaLettering(source, translated, 40, 90, 440, 200, "sans-serif", 0, Color.BLACK,
                    42f, "ALIGN_CENTER", 40, 90, 440, 200, originalSourceBounds = SavedOriginalSourceBounds(1, 40, 90, 440, 200))
                assertTrue(native.commitPage(started.id, started.generation, page.copy(status = ChapterTranslationPageStatus.COMPLETED,
                    cleanedPath = output.path, cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 480, imageHeight = 1_280,
                    originalWidth = 480, originalHeight = 1_280, lettering = listOf(lettering))))
            }
            val completed = native.finish(started.id, started.generation)!!
            task = completed
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(completed))
            val adapter = NativeMemoryPublicationAdapter(context.filesDir, native, authority)
            val editor = runBlocking { adapter.openEditor(selected, 7, 0)!! }
            runBlocking {
                assertNotNull(adapter.openEditor(selected, 0, 0))
                adapter.correct(editor, 0, MemoryCorrectionEdit(correctedOcr = "Hello, friend."))
            }
            library.save(chapter)
            val originalBytes = files.mapValues { it.value.readBytes() }
            // The Store directory is private and may be versioned; find only this unique task's journal.
            val actualJournal = context.filesDir.walkTopDown().single { it.isFile && it.name == "${completed.id}.json" }
            val nativeBytes = actualJournal.readBytes()
            val outputs = completed.pages.associate { it.cleanedPath!! to File(it.cleanedPath).readBytes() }
            prefs.edit().putString("translation_target", "en").putString("translation_style", "formal")
                .putBoolean("translation_manga", false).commit()
            readerPrefs.edit().putString("default_mode", "vertical").remove("mode_$title").commit()
            libraryPrefs.edit().remove(LibrarySettingsStore.OPTIONS_KEY).commit()
            launchHome(); tap(By.desc("Library").pkg(context.packageName))
            typeIntoEditableUi(device, "Search saved chapters", title)
            tap(By.desc("$title cover")); node(By.desc("Page 0"))
            device.pressBack(); node(By.desc("Library filters and sort"))
            tap(By.desc("Chapter actions for $title")); tap(By.desc("Search saved text in $title"))
            typeIntoEditableUi(device, "Search text in selected chapter", "Hello")
            tap(By.desc("Run selected chapter text search"))
            node(By.desc("Saved text results: 2"))
            node(By.text("Original OCR · page 1 · hi")); node(By.text("Personal OCR · page 1 · hi"))
            capture("original-and-personal-distinct")
            typeIntoEditableUi(device, "Search text in selected chapter", "friend")
            tap(By.desc("Run selected chapter text search")); node(By.desc("Saved text results: 1"))
            recreateActivity(); node(By.desc("Search text in selected chapter"))
            assertEquals("friend", readEditableUi(device, "Search text in selected chapter").text)
            assertNull("Recreation cannot promote a cached native result", device.findObject(By.desc("Saved text results: 1")))
            tap(By.desc("Run selected chapter text search")); node(By.desc("Saved text results: 1"))
            tap(By.descStartsWith("Open saved text result ")); node(By.desc("Page 7"))
            waitFor("A page7 result must persist its actual list ordinal0, not pageIndex7") { library.list().singleOrNull { it.id == id }?.position == 0 }
            assertEquals("en", prefs.getString("translation_target", null)); assertEquals("formal", prefs.getString("translation_style", null))
            assertFalse(prefs.getBoolean("translation_manga", true))
            capture("actual-sparse-page-seven-at-ordinal-zero")
            assertArrayEquals(nativeBytes, actualJournal.readBytes())
            originalBytes.forEach { (index, bytes) -> assertArrayEquals(bytes, files.getValue(index).readBytes()) }
            outputs.forEach { (path, bytes) -> assertArrayEquals(bytes, File(path).readBytes()) }
        } catch (failure: Throwable) {
            runCatching { recordFailure(failure) }
            throw failure
        } finally {
            finishActivity()
            task?.let { native.finishChapterRemoval(native.beginChapterRemoval(id)) }
            library.remove(id)
            runBlocking { SeriesMemoryStore(context.filesDir).removeChapter(id) }
            files.values.forEach { it.delete() }
            restoreLibrary(); restoreReader(); restoreApp()
        }
    }
}
