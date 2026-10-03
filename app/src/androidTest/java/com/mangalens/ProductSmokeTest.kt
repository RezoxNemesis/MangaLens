package com.mangalens

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProductSmokeTest {
    @Test fun primaryScreensOpenAndCaptureScreenshots() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val fixtureId = com.mangalens.core.reader.ChapterLibrary.id("qa:reader")
        val library = com.mangalens.core.reader.ChapterLibrary(context)
        val image = File(File(context.filesDir, "chapters").apply { mkdirs() }, "qa_reader.png")
        val bitmap = android.graphics.Bitmap.createBitmap(800, 1600, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply {
            drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 48f }
            drawText("Offline reader test", 40f, 120f, paint)
            drawRect(40f, 220f, 760f, 900f, paint)
        }
        image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        library.save(com.mangalens.core.reader.SavedChapter(fixtureId, "QA offline chapter", "", listOf(com.mangalens.core.reader.ChapterPage(1, "local:qa", image.absolutePath))))
        val screenshots = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        var completed = false
        try {
        if (android.os.Build.VERSION.SDK_INT >= 33) device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 15_000))
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(screenshots, "home.png")))
        for ((label, expected) in listOf("Library" to "Local Media Player", "Orez AI" to "OREZ AI", "Downloads" to "Downloads", "Settings" to "Appearance")) {
            val navigation = device.wait(Until.findObject(By.descContains(label)), 10_000)
            assertNotNull("Missing navigation: $label", navigation)
            navigation.click()
            assertTrue("Destination did not open: $label", device.wait(Until.hasObject(By.text(expected)), 10_000))
            device.waitForIdle()
            assertTrue(device.takeScreenshot(File(screenshots, label.lowercase().replace(' ', '-') + ".png")))
            if (label == "Library") {
                val chapter = device.wait(Until.findObject(By.text("QA offline chapter")), 10_000)
                assertNotNull("Saved chapter did not restore into Library", chapter)
                chapter.click()
                assertTrue("Offline page did not render", device.wait(Until.hasObject(By.descContains("Page 1")), 10_000))
                device.waitForIdle()
                assertTrue(device.takeScreenshot(File(screenshots, "reader.png")))
                device.pressBack()
                assertTrue(device.wait(Until.hasObject(By.descContains("Orez AI")), 10_000))
            }
        }
        device.pressBack()
        completed = true
        } finally {
            if (!completed) { device.takeScreenshot(File(screenshots, "failure.png")); device.dumpWindowHierarchy(File(screenshots, "failure-hierarchy.xml")) }
            library.remove(fixtureId); image.delete()
        }
    }
}
