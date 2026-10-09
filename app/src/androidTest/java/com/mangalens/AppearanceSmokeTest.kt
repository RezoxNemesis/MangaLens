package com.mangalens

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppearanceSmokeTest {
    @Test fun appearanceActionsPersistThroughRecreationAndNavigationWorksWithLargeFontsAndRotation() = coreScreenSmoke("appearance") {
        val appearance = context.getSharedPreferences("mangalens_appearance", Context.MODE_PRIVATE)
        val settings = context.getSharedPreferences("mangalens_preferences", Context.MODE_PRIVATE)
        val restoreAppearance = restorePreferencesAfter(appearance, "density", "accent", "reduced_motion")
        val restoreSettings = restorePreferencesAfter(settings, "theme_mode")
        val oldFontScale = device.executeShellCommand("settings get system font_scale").trim()
        try {
            appearance.edit().putString("density", "COMPACT").putString("accent", "BLUE").putBoolean("reduced_motion", false).commit()
            settings.edit().putString("theme_mode", "DARK").commit()
            launchHome()
            tap(By.desc("Settings and protection"))
            scrollTo(By.text("Balanced")).click()
            waitFor("Density action was not persisted") { appearance.getString("density", null) == "BALANCED" }
            capture("density-applied")
            assertSelected("Balanced")
            scrollTo(By.text("Cyan")).click()
            waitFor("Accent action was not persisted") { appearance.getString("accent", null) == "CYAN" }
            capture("accent-applied")
            assertSelected("Cyan")
            checkableBeside("Reduce motion").click()
            waitFor("Reduce-motion action was not persisted") { appearance.getBoolean("reduced_motion", false) }
            assertTrue("Reduce-motion control did not update", checkableBeside("Reduce motion").isChecked)
            capture("density-accent-reduced-motion")
            checkableBeside("Amoled").click()
            waitFor("AMOLED theme action was not persisted") { settings.getString("theme_mode", null) == "AMOLED" }
            waitFor("AMOLED control did not become selected") { checkableBeside("Amoled").isChecked }
            // Preference persistence precedes the next rendered frame. Check real pixels
            // rather than capturing a stale pre-click frame on a software emulator.
            val rendered = File(context.cacheDir, "qa-amoled-render.png")
            try {
                waitFor("AMOLED did not render a black app background", 15_000) {
                    if (!device.takeScreenshot(rendered)) return@waitFor false
                    val frame = BitmapFactory.decodeFile(rendered.absolutePath) ?: return@waitFor false
                    try { frame.getPixel(1, frame.height / 2) == Color.BLACK } finally { frame.recycle() }
                }
            } finally { rendered.delete() }
            val screenshot = capture("amoled-theme")
            val bitmap = BitmapFactory.decodeFile(screenshot.absolutePath)
            try {
                assertEquals("AMOLED did not render a black app background", Color.BLACK, bitmap.getPixel(1, bitmap.height / 2))
            } finally { bitmap.recycle() }
            recreateActivity()
            // Settings restores its real LazyColumn position; the header can be
            // above the viewport while the selected appearance controls remain.
            scrollUpToSettingsHeader()
            assertEquals("Recreation lost density", "BALANCED", appearance.getString("density", null))
            assertEquals("Recreation lost accent", "CYAN", appearance.getString("accent", null))
            assertTrue("Recreation lost reduced motion", appearance.getBoolean("reduced_motion", false))
            assertTrue("Recreation lost selected AMOLED control", checkableBeside("Amoled").isChecked)
            capture("appearance-after-recreation")
            device.executeShellCommand("settings put system font_scale 1.3")
            device.setOrientationLeft()
            node(By.desc("Library"))
            tap(By.desc("Library"))
            node(By.text("My Library"))
            capture("large-font-landscape-library")
            tap(By.desc("Home").pkg(context.packageName))
            node(By.desc("Settings and protection"))
            capture("large-font-landscape-home")
        } catch (failure: Throwable) {
            runCatching { recordFailure(failure) }
            throw failure
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
            if (oldFontScale == "null") device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $oldFontScale")
            finishActivity()
            restoreAppearance()
            restoreSettings()
        }
    }

    private fun CoreScreenSmokeSupport.scrollUpToSettingsHeader() {
        val header = By.text("Settings").pkg(context.packageName)
        val deadline = SystemClock.uptimeMillis() + 15_000 // Existing node deadline.
        var moves = 0
        while (moves < 12 && SystemClock.uptimeMillis() < deadline) {
            device.findObject(header)?.takeIf { !it.visibleBounds.isEmpty }?.let { return }
            val container = device.findObjects(By.scrollable(true).pkg(context.packageName))
                .maxByOrNull { it.visibleBounds.width().toLong() * it.visibleBounds.height() }
                ?: throw AssertionError("Settings had no app scroll container after recreation")
            container.scroll(Direction.UP, .45f)
            moves++
        }
        node(header, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }
}
