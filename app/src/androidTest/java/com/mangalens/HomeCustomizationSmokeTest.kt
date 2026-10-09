package com.mangalens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.ui.home.HomeLayout
import com.mangalens.ui.home.HomeLayoutPolicy
import com.mangalens.ui.home.HomeModule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual default Home, persisted native library fixture, real controls, and Activity recreation. */
@RunWith(AndroidJUnit4::class)
class HomeCustomizationSmokeTest {
    @Test fun homeVisibilityOrderAndResetPersistThroughRecreation() = coreScreenSmoke("home-customization") {
        val prefs = context.getSharedPreferences("mangalens_home", Context.MODE_PRIVATE)
        val restore = restorePreferencesAfter(prefs, "layout_v1")
        val id = UUID.randomUUID().toString().replace("-", "")
        val source = File(context.filesDir, "chapters/home-custom-$id.png").apply { parentFile!!.mkdirs() }
        val library = ChapterLibrary(context)
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.WHITE); source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val chapter = SavedChapter(id, "Home customization QA", "content://explicit-qa-image",
            listOf(ChapterPage(1, "content://explicit-qa-image", source.absolutePath)), bookmarked = true)
        try {
            prefs.edit().remove("layout_v1").commit()
            library.save(chapter)
            launchHome()
            node(By.desc("Home section: Quick actions").pkg(context.packageName))
            node(By.text("Continue reading"))
            capture("default-home")
            tapSettled(By.desc("Customize Home").pkg(context.packageName))
            tapSettled(By.desc("Show Quick actions on Home"))
            waitFor("Quick-action checkbox did not become unchecked") { device.findObject(By.desc("Show Quick actions on Home"))?.isChecked == false }
            node(By.text("Save"))
            capture("editor-before-reorder")
            val beforeMove = node(By.desc("Move Recent Manga up")).visibleBounds.centerY()
            tapSettled(By.desc("Move Recent Manga up"))
            waitFor("Recent Manga did not move in the actual editor") {
                device.findObject(By.desc("Move Recent Manga up"))?.visibleBounds?.let { !it.isEmpty && it.centerY() < beforeMove } == true
            }
            tapSettled(By.desc("Move Recent Manga up"))
            waitFor("Recent Manga did not reach the first editor slot") {
                device.findObject(By.desc("Move Recent Manga up"))?.let { !it.visibleBounds.isEmpty && !it.isEnabled } == true
            }
            tapSettled(By.text("Save"))
            waitFor("Home layout did not persist the actual hide/reorder controls") {
                val layout = HomeLayoutPolicy.decode(prefs.getString("layout_v1", null))
                HomeModule.QUICK_ACTIONS in layout.hidden && layout.order.first() == HomeModule.RECENT_MANGA
            }
            waitFor("Quick actions remained visible in the first Home slot after hiding") {
                device.findObject(By.desc("Customize Home").pkg(context.packageName)) != null &&
                    device.findObject(By.desc("Home section: Quick actions").pkg(context.packageName)) == null
            }
            assertHomeSectionsInOrder("Home section: Recent Manga", "Home section: Continue Reading",
                "Reordering changed preferences but not real Home placement")
            capture("hidden-and-reordered-home")

            recreateActivity()
            node(By.desc("Customize Home").pkg(context.packageName))
            assertNull(device.findObject(By.desc("Home section: Quick actions").pkg(context.packageName)))
            assertHomeSectionsInOrder("Home section: Recent Manga", "Home section: Continue Reading",
                "Recreation lost real module order")
            capture("custom-home-after-recreation")
            tapSettled(By.desc("Customize Home").pkg(context.packageName))
            tapSettled(By.text("Reset"))
            tapSettled(By.text("Save"))
            waitFor("Reset did not persist the original Home layout") { HomeLayoutPolicy.decode(prefs.getString("layout_v1", null)) == HomeLayout() }
            node(By.desc("Home section: Quick actions").pkg(context.packageName))
            capture("reset-default-home")
            recreateActivity()
            node(By.desc("Home section: Quick actions").pkg(context.packageName))
            assertEquals(HomeLayout(), HomeLayoutPolicy.decode(prefs.getString("layout_v1", null)))
            File(context.getExternalFilesDir(null), "qa/core-smoke/home-customization/layout.json")
                .apply { parentFile!!.mkdirs() }.writeText(JSONObject().put("source_sha", BuildConfig.SOURCE_SHA)
                    .put("reset_layout", JSONObject(prefs.getString("layout_v1", null)!!)).toString(2))
        } catch (failure: Throwable) {
            runCatching { recordFailure(failure) }
            throw failure
        } finally {
            finishActivity()
            library.remove(id)
            source.delete()
            restore()
        }
    }

    @Test fun enablingBookmarksShowsRealSavedBookmarkAndCancelDoesNotPersistAnotherEdit() = coreScreenSmoke("home-bookmarks") {
        val prefs = context.getSharedPreferences("mangalens_home", Context.MODE_PRIVATE)
        val restore = restorePreferencesAfter(prefs, "layout_v1")
        val id = UUID.randomUUID().toString().replace("-", "")
        val source = File(context.filesDir, "chapters/home-bookmark-$id.png").apply { parentFile!!.mkdirs() }
        val library = ChapterLibrary(context)
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.WHITE); source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        try {
            prefs.edit().putString("layout_v1", HomeLayoutPolicy.encode(HomeLayout(order = listOf(HomeModule.BOOKMARKS) + HomeModule.entries.filterNot { it == HomeModule.BOOKMARKS },
                hidden = HomeModule.entries.toSet()))).commit()
            library.save(SavedChapter(id, "Persisted Home bookmark QA", "content://explicit-qa-image",
                listOf(ChapterPage(1, "content://explicit-qa-image", source.absolutePath)), bookmarked = true))
            launchHome()
            node(By.text("Your Home sections are hidden"))
            tapSettled(By.desc("Customize Home").pkg(context.packageName))
            tapSettled(By.desc("Show Bookmarks on Home"))
            waitFor("Bookmark checkbox did not become checked") { device.findObject(By.desc("Show Bookmarks on Home"))?.isChecked == true }
            tapSettled(By.text("Save"))
            node(By.desc("Home section: Bookmarks").pkg(context.packageName))
            node(By.text("Your bookmarks"))
            // The freshly written native library fixture is the newest bookmark.
            node(By.text("Persisted Home bookmark QA"))
            capture("enabled-real-bookmarks")
            val beforeCancel = prefs.getString("layout_v1", null)
            tapSettled(By.desc("Customize Home").pkg(context.packageName))
            tapSettled(By.desc("Show Bookmarks on Home"))
            waitFor("Cancel fixture did not actually change the bookmark checkbox") { device.findObject(By.desc("Show Bookmarks on Home"))?.isChecked == false }
            tapSettled(By.text("Cancel"))
            assertEquals("Cancel persisted an unsaved edit", beforeCancel, prefs.getString("layout_v1", null))
            recreateActivity()
            node(By.desc("Home section: Bookmarks").pkg(context.packageName))
            assertEquals(beforeCancel, prefs.getString("layout_v1", null))
            capture("bookmarks-after-recreation")
        } catch (failure: Throwable) {
            runCatching { recordFailure(failure) }
            throw failure
        } finally {
            finishActivity()
            library.remove(id)
            source.delete()
            restore()
        }
    }
}
