package com.mangalens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.mangalens.core.reader.*
import com.mangalens.ui.library.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real MainActivity Library routes and controls; no replacement test setContent or network fixture. */
@RunWith(AndroidJUnit4::class)
class LibraryExplorerSmokeTest {
    private fun fixture(context: Context, title: String, status: ReadingStatus = ReadingStatus.ON_HOLD, addedAt: Long = 100): SavedChapter {
        val id = UUID.randomUUID().toString().replace("-", "")
        val image = File(context.filesDir,"chapters/library-explorer-$id.png").apply { parentFile!!.mkdirs() }
        val bitmap=Bitmap.createBitmap(480,1_280,Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.WHITE);image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { bitmap.recycle() }
        return SavedChapter(id,title,"", List(3) { ChapterPage(it+1,"content://explicit-library-qa/$id/${it+1}",image.path) },
            position=1,scrollOffset=137,bookmarked=true,readingStatus=status,addedAt=addedAt,lastReadAt=0)
    }

    @Test fun fiveStatusesMetadataAndCollectionsPersistThroughActualEditorAndRecreation() = coreScreenSmoke("library-details") {
        val prefs=context.getSharedPreferences(LibrarySettingsStore.PREFERENCES_NAME,Context.MODE_PRIVATE)
        val restore=restorePreferencesAfter(prefs,LibrarySettingsStore.OPTIONS_KEY)
        val library=ChapterLibrary(context);val chapter=fixture(context,"Library details QA")
        try {
            prefs.edit().remove(LibrarySettingsStore.OPTIONS_KEY).commit();library.save(chapter)
            launchHome();tap(By.desc("Library").pkg(context.packageName))
            typeIntoEditableUi(device,"Search saved chapters","Library details QA")
            tap(By.desc("Chapter actions for ${chapter.title}"));tap(By.text("Plan to Read"))
            waitFor("Plan to Read did not reach the native journal") { ChapterLibrary(context).list().firstOrNull { it.id==chapter.id }?.readingStatus==ReadingStatus.PLAN_TO_READ }
            tap(By.desc("Chapter actions for ${chapter.title}"));tap(By.text("Dropped"))
            waitFor("Dropped did not reach the native journal") { ChapterLibrary(context).list().firstOrNull { it.id==chapter.id }?.readingStatus==ReadingStatus.DROPPED }
            tap(By.desc("Chapter actions for ${chapter.title}"));tap(By.desc("Edit library details for ${chapter.title}"))
            typeIntoEditableUi(device,"Library series title","Saved QA series")
            typeIntoEditableUi(device,"Library chapter notes","Learn this scene with Mira")
            scrollTo(By.desc("Library new collection"))
            typeIntoEditableUi(device,"Library new collection","QA Weekend")
            tap(By.text("Add"));tap(By.text("Save details"))
            waitFor("Actual details editor did not atomically persist metadata") {
                ChapterLibrary(context).list().firstOrNull { it.id==chapter.id }?.let { it.seriesTitle=="Saved QA series" && it.notes=="Learn this scene with Mira" && it.collections==listOf("QA Weekend") }==true
            }
            val saved=library.list().first { it.id==chapter.id }
            assertEquals(chapter.position,saved.position);assertEquals(chapter.scrollOffset,saved.scrollOffset)
            assertEquals(chapter.addedAt,saved.addedAt);assertEquals(0L,saved.lastReadAt)
            assertTrue(saved.bookmarked);assertEquals(ReadingStatus.DROPPED,saved.readingStatus)
            typeIntoEditableUi(device,"Search saved chapters","Mira QA Weekend")
            node(By.desc("Saved chapter: ${chapter.title}"));capture("details-search")
            recreateActivity();node(By.desc("Library filters and sort"))
            node(By.desc("Saved chapter: ${chapter.title}"));assertEquals("Mira QA Weekend",readEditableUi(device,"Search saved chapters").text)
            tap(By.desc("Chapter actions for ${chapter.title}"));tap(By.desc("Edit library details for ${chapter.title}"))
            assertTrue(readEditableUi(device,"Library series title").text.contains("Saved QA series"))
            typeIntoEditableUi(device,"Library chapter notes","Discard this unsaved note")
            tap(By.text("Cancel"));assertEquals("Learn this scene with Mira",library.list().first { it.id==chapter.id }.notes)
            capture("details-after-recreation")
        } finally { finishActivity();library.remove(chapter.id);restore() }
    }

    @Test fun combinedFiltersSortAndCancelUseRealSavedFactsAfterRecreation() = coreScreenSmoke("library-query") {
        val prefs=context.getSharedPreferences(LibrarySettingsStore.PREFERENCES_NAME,Context.MODE_PRIVATE)
        val restore=restorePreferencesAfter(prefs,LibrarySettingsStore.OPTIONS_KEY)
        val library=ChapterLibrary(context)
        val first=fixture(context,"Library query QA Zulu",ReadingStatus.PLAN_TO_READ,100).copy(collections=listOf("QA Study"))
        val second=fixture(context,"Library query QA Alpha",ReadingStatus.PLAN_TO_READ,200).copy(collections=listOf("QA Study"),bookmarked=false)
        try {
            prefs.edit().remove(LibrarySettingsStore.OPTIONS_KEY).commit();library.save(first);library.save(second)
            launchHome();tap(By.desc("Library").pkg(context.packageName));typeIntoEditableUi(device,"Search saved chapters","Library query QA")
            node(By.desc("Saved chapter: ${first.title}"));node(By.desc("Saved chapter: ${second.title}"))
            tap(By.desc("Library filters and sort"));tap(By.desc("Sort library: Title A–Z"))
            scrollTo(By.desc("Choose status filter: Plan to Read")).click()
            scrollTo(By.desc("Choose source filter: Local imports")).click()
            scrollTo(By.desc("Choose collection filter: QA Study")).click()
            scrollTo(By.desc("Choose offline filter: Fully offline")).click();tap(By.text("Apply"))
            waitFor("Filter/sort Apply did not persist the combined native choice") {
                LibraryOptionsCodec.decode(prefs.getString(LibrarySettingsStore.OPTIONS_KEY,null)).let {
                    it.sort==LibrarySort.TITLE_ASC && it.status==ReadingStatus.PLAN_TO_READ && it.sourceKey=="local" && it.collection=="QA Study" && it.offline==LibraryOfflineFilter.READY
                }
            }
            val alphaBounds=node(By.desc("Saved chapter: ${second.title}")).visibleBounds
            val zuluBounds=node(By.desc("Saved chapter: ${first.title}")).visibleBounds
            assertTrue("Title sort changed preferences without moving the real grid", alphaBounds.top < zuluBounds.top || alphaBounds.top == zuluBounds.top && alphaBounds.left < zuluBounds.left)
            tap(By.desc("Filter bookmarks"));waitFor("Bookmark combination did not filter the unbookmarked saved chapter") {
                device.findObject(By.desc("Saved chapter: ${first.title}"))!=null && device.findObject(By.desc("Saved chapter: ${second.title}"))==null
            }
            val accepted=prefs.getString(LibrarySettingsStore.OPTIONS_KEY,null)
            tap(By.desc("Library filters and sort"));tap(By.desc("Sort library: Title Z–A"));tap(By.text("Cancel"))
            assertEquals(accepted,prefs.getString(LibrarySettingsStore.OPTIONS_KEY,null))
            recreateActivity();node(By.desc("Saved chapter: ${first.title}"));assertNull(device.findObject(By.desc("Saved chapter: ${second.title}")))
            assertEquals(accepted,prefs.getString(LibrarySettingsStore.OPTIONS_KEY,null));capture("combined-query-after-recreation")
        } finally { finishActivity();library.remove(first.id);library.remove(second.id);restore() }
    }

    @Test fun realReaderVisitUpdatesLastReadWithoutChangingChosenStatusOrAddedDate() = coreScreenSmoke("library-last-read") {
        val prefs=context.getSharedPreferences(LibrarySettingsStore.PREFERENCES_NAME,Context.MODE_PRIVATE)
        val restore=restorePreferencesAfter(prefs,LibrarySettingsStore.OPTIONS_KEY)
        val reader=context.getSharedPreferences("mangalens_reader",Context.MODE_PRIVATE)
        val restoreReader=restorePreferencesAfter(reader,"default_mode","mode_Library visit QA")
        val appPrefs=context.getSharedPreferences("mangalens_preferences",Context.MODE_PRIVATE)
        val restoreApp=restorePreferencesAfter(appPrefs,"translation_manga")
        val library=ChapterLibrary(context);val chapter=fixture(context,"Library visit QA",ReadingStatus.ON_HOLD,123)
        try {
            prefs.edit().remove(LibrarySettingsStore.OPTIONS_KEY).commit()
            appPrefs.edit().putBoolean("translation_manga",false).commit()
            reader.edit().putString("default_mode","vertical").remove("mode_Library visit QA").commit()
            library.save(chapter);launchHome();tap(By.desc("Library").pkg(context.packageName))
            typeIntoEditableUi(device,"Search saved chapters","Library visit QA")
            node(By.desc("Saved chapter: ${chapter.title}"))
            assertEquals("Passive restore counted as reading",0L,library.list().first { it.id==chapter.id }.lastReadAt)
            tap(By.desc("${chapter.title} cover"))
            node(By.desc("Page 2"))
            waitFor("Opening the actual reader did not persist last read") { library.list().firstOrNull { it.id==chapter.id }?.lastReadAt?.let { it>0 }==true }
            val visited=library.list().first { it.id==chapter.id }
            assertEquals(chapter.addedAt,visited.addedAt);assertEquals(ReadingStatus.ON_HOLD,visited.readingStatus)
            assertTrue(File(visited.pages.first().localPath!!).isFile)
            capture("real-reader-visit")
        } finally { finishActivity();library.remove(chapter.id);restoreReader();restoreApp();restore() }
    }

    @Test fun actualAtomicBackupRecoversLegacyMetadataAndKeepsSharedPages() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val library=ChapterLibrary(context);val first=fixture(context,"Library AtomicFile QA").copy(readingStatus=ReadingStatus.PLAN_TO_READ)
        val second=first.copy(id=UUID.randomUUID().toString().replace("-",""),title="Shared Library AtomicFile QA")
        val base=File(context.filesDir,"chapter_library/${first.id}.json");val backup=File(base.path+".bak")
        try {
            library.save(first);library.save(second)
            val json=JSONObject(base.readText()).put("version",1)
            listOf("seriesTitle","notes","collections","addedAt","lastReadAt").forEach { json.remove(it) }
            backup.writeText(json.toString());base.delete()
            val recovered=ChapterLibrary(context).list().first { it.id==first.id }
            assertEquals(first.position,recovered.position);assertEquals(first.scrollOffset,recovered.scrollOffset)
            assertEquals(0L,recovered.addedAt);assertEquals(0L,recovered.lastReadAt);assertTrue(recovered.collections.isEmpty())
            assertEquals(ReadingStatus.PLAN_TO_READ,recovered.readingStatus)
            library.updateMetadata(first.id,LibraryChapterMetadata("Actual series","Actual note",listOf("QA collection")))
            assertEquals("Actual note",ChapterLibrary(context).list().first { it.id==first.id }.notes)
            library.remove(first.id);assertTrue(File(first.pages.first().localPath!!).isFile)
            library.remove(second.id);assertFalse(File(first.pages.first().localPath!!).exists())
        } finally { library.remove(first.id);library.remove(second.id);backup.delete() }
    }
}
