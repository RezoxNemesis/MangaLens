package com.mangalens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProductSmokeTest {
    @Test fun retainedRoutesRestoreAnOfflineChapterAndDisplayTheCandidateIdentity() = coreScreenSmoke("product") {
        val expectedSha = InstrumentationRegistry.getArguments().getString("expected_source_sha")
        if (expectedSha != null) assertEquals("APK belongs to another source candidate", expectedSha, BuildConfig.SOURCE_SHA)
        val prefs = context.getSharedPreferences("mangalens_preferences", Context.MODE_PRIVATE)
        val restore = restorePreferencesAfter(prefs, "last_url", "translation_web")
        val fixtureKey = "qa:product-smoke:${System.nanoTime()}"
        val fixtureId = ChapterLibrary.id(fixtureKey)
        val library = ChapterLibrary(context)
        val fixtureDirectory = File(context.filesDir, "chapters").apply { mkdirs() }
        val image = File(fixtureDirectory, "qa-product-$fixtureId.png")
        val bitmap = Bitmap.createBitmap(800, 1600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
            drawText("Offline reader acceptance", 40f, 120f, paint)
            drawRect(40f, 220f, 760f, 900f, paint)
        }
        image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val browserFixture = BrowserWorkspaceFixtureIsolation(context)
        try {
            prefs.edit().putString("last_url", "").putBoolean("translation_web", false).commit()
            library.save(SavedChapter(fixtureId, "QA offline chapter", "", listOf(ChapterPage(1, "local:qa", image.absolutePath))))
            launchHome()
            capture("home")
            tapSettled(By.desc("Library"))
            node(By.text("My Library"))
            tapSettled(By.text("QA offline chapter"))
            node(By.descContains("Page 1"))
            capture("reader-offline")
            device.pressBack()
            node(By.text("My Library"))
            assertTrue("Saved page disappeared after closing Reader", image.isFile)
            assertEquals("Offline fixture metadata was not retained", 1, library.list().single { it.id == fixtureId }.pages.size)
            tapSettled(By.desc("Orez AI"))
            node(By.text("Orez AI"))
            capture("orez")
            tapSettled(By.desc("Home").pkg(context.packageName))
            openHomeShortcut("Downloads", minimumWidthDp = 196)
            node(By.text("Download Room"))
            capture("downloads")
            tapSettled(By.text("Files"))
            node(By.textContains("saved chapters"))
            tapSettled(By.text("Open library →"))
            node(By.text("My Library"))
            capture("library-restored-from-downloads")
            tapSettled(By.desc("Home").pkg(context.packageName))
            tapSettled(By.desc("Settings and protection"))
            node(By.text("Settings"))
            val identity = scrollTo(By.textContains("Build ${BuildConfig.VERSION_NAME} • ${BuildConfig.SOURCE_SHA} • ${BuildConfig.BUILD_CHANNEL}"))
            assertTrue("Visible build identity was blank", identity.text.contains(BuildConfig.SOURCE_SHA))
            capture("settings-source-identity")
            device.pressBack()
            node(By.desc("Home").pkg(context.packageName))
            tapSettled(By.desc("Watch videos"))
            node(By.text("DEVICE VIDEOS • YOUR COLLECTION"))
            capture("watch")
            tapSettled(By.desc("Web browser"))
            node(By.text("Search or open a website"))
            tapSettled(By.text("Cancel"))
            capture("web-empty")
            device.pressBack()
            node(By.desc("Home").pkg(context.packageName))
            capture("home-returned")
        } catch (failure: Throwable) {
            runCatching { recordFailure(failure) }
            throw failure
        } finally {
            try {
                finishActivity()
                library.remove(fixtureId)
                image.delete()
                restore()
            } finally { browserFixture.close() }
        }
    }
}
