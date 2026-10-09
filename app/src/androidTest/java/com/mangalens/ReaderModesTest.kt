package com.mangalens

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mangalens.core.reader.ChapterPage
import com.mangalens.ui.reader.MangaContinuousReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReaderModesTest {
    @Test fun sameChapterSwitchesBetweenVerticalLtrAndRtlWithoutLosingPageCount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val prefs = app.getSharedPreferences("mangalens_reader", 0)
        val old = prefs.getString("default_mode", "vertical")
        prefs.edit().putString("default_mode", "vertical").remove("mode_Reader mode QA").commit()
        val files = (1..3).map { number ->
            File(app.cacheDir, "reader-mode-$number.png").apply {
                val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawText("PAGE $number", 40f, 150f, android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = 40f })
                outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        }
        val pages = files.mapIndexed { index, file -> ChapterPage(index + 1, file.toURI().toString(), file.absolutePath) }
        val observed = AtomicReference<ObservedPosition?>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val device = UiDevice.getInstance(instrumentation)
        try {
            scenario.onActivity { activity -> activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    MangaContinuousReader("Reader mode QA", pages, "mode-qa", translated = false, overlays = emptyMap(),
                        onPositionChanged = { chapter, page, offset ->
                            observed.set(ObservedPosition(chapter, page, offset, prefs.getString("mode_Reader mode QA", "vertical").orEmpty()))
                        },
                        onTranslate = {}, onDownload = {}, onMenu = {}, onLongPressPage = {})
                }
            } }
            fun click(label: String) { requireNotNull(device.wait(Until.findObject(By.text(label)), 8000)) { "Missing $label" }.click() }
            fun assertViewport(page: Int, mode: String) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 8000
                do {
                    val physical = observed.get()
                    val header = device.findObject(By.text("Page $page / 3"))
                    if (header != null && physical?.chapter == "mode-qa" && physical.page == page - 1 && physical.mode == mode) return
                    android.os.SystemClock.sleep(50)
                } while (android.os.SystemClock.elapsedRealtime() < deadline)
                fail("Missing settled Page $page / 3 in $mode; viewport=${observed.get()}; persistedMode=${prefs.getString("mode_Reader mode QA", "")}")
            }
            click("Reader tools")
            click("Horizontal LTR")
            assertViewport(1, "ltr")
            // Selecting the current mode must not disable the viewport observer.
            click("Horizontal LTR")
            click("Next ›")
            assertViewport(2, "ltr")
            click("Horizontal RTL")
            assertViewport(2, "rtl")
            assertEquals("rtl", prefs.getString("mode_Reader mode QA", ""))
            click("Vertical scroll")
            assertViewport(2, "vertical")
        } finally {
            scenario.close(); files.forEach { it.delete() }
            prefs.edit().putString("default_mode", old).remove("mode_Reader mode QA").commit()
        }
    }

    private data class ObservedPosition(val chapter: String, val page: Int, val offset: Int, val mode: String)
}
