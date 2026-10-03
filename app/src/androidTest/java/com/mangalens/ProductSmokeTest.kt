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
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 15_000))
        device.waitForIdle()
        val screenshots = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(screenshots, "home.png")))
        for ((label, expected) in listOf("Library" to "Local Media Player", "Orez AI" to "OREZ AI", "Downloads" to "Downloads", "Settings" to "Appearance")) {
            val navigation = device.wait(Until.findObject(By.desc(label)), 10_000)
            assertNotNull("Missing navigation: $label", navigation)
            navigation.click()
            assertTrue("Destination did not open: $label", device.wait(Until.hasObject(By.text(expected)), 10_000))
            device.waitForIdle()
            assertTrue(device.takeScreenshot(File(screenshots, label.lowercase().replace(' ', '-') + ".png")))
        }
        device.pressBack()
    }
}
