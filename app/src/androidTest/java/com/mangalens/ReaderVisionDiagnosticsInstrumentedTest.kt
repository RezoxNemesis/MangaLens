package com.mangalens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.translation.*
import com.mangalens.ui.reader.MangaContinuousReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Authored UNRUN. Pixel control executes reconstruction; UI control deliberately injects labelled diagnostic data, not a fake OCR result. */
@RunWith(AndroidJUnit4::class)
class ReaderVisionDiagnosticsInstrumentedTest {
    @Test fun protectedReconstructionCannotEraseNeighbourOriginalPixels() {
        val source = Bitmap.createBitmap(240, 120, Bitmap.Config.ARGB_8888)
        var output: Bitmap? = null
        try {
            source.eraseColor(Color.WHITE); val canvas = Canvas(source); val paint = Paint().apply { color = Color.BLACK }
            canvas.drawRect(30f, 40f, 65f, 55f, paint); canvas.drawRect(150f, 40f, 200f, 55f, paint)
            val before = IntArray(240 * 120); source.getPixels(before, 0, 240, 0, 0, 240, 120)
            val observed = MangaWritableRect(30, 40, 65, 55); val other = MangaWritableRect(150, 40, 200, 55)
            val limit = MangaWritableGeometry.limit(observed, listOf(other), 240, 120)!!
            val patch = MangaLettering.prepare(source, RectF(30f, 40f, 65f, 55f), listOf(RectF(30f, 40f, 65f, 55f)), "HELLO", false, 2,
                Rect(limit.left, limit.top, limit.right, limit.bottom))
            try {
                assertTrue(patch.bounds.right <= limit.right)
                output = source.copy(Bitmap.Config.ARGB_8888, true); MangaLettering.drawBackground(Canvas(output!!), patch)
                val result = IntArray(before.size); output!!.getPixels(result, 0, 240, 0, 0, 240, 120)
                for (y in 0 until 120) for (x in 0 until 240) if (!patch.bounds.contains(x, y)) assertEquals("Untouched artwork changed", before[y * 240 + x], result[y * 240 + x])
                for (y in 40 until 55) for (x in 150 until 200) assertEquals(Color.BLACK, result[y * 240 + x])
                val sourceAfter = IntArray(before.size); source.getPixels(sourceAfter, 0, 240, 0, 0, 240, 120); assertArrayEquals(before, sourceAfter)
            } finally { patch.background.recycle() }
        } finally { output?.recycle(); source.recycle() }
    }
    @Test fun readerShowsRecordedDiagnosticsAndKeepsOriginalBytes() = coreScreenSmoke("reader-vision-diagnostics") {
        val app = instrumentation.targetContext
        val original = File(app.cacheDir, "reader-vision-diagnostics.png")
        val bitmap = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.WHITE); original.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
        val bytes = original.readBytes(); val sha = ChapterTranslationStore.sha256(original)
        val diagnostic = SavedPageOcrDiagnostics(sha, 240, 360, 2, 1, listOf(SavedOcrFinding(0, SavedOcrBox(10, 10, 100, 40), "LATIN", .81f,
            listOf(SavedOcrBox(10, 10, 100, 40)), SavedOcrOutcome.GEOMETRY_CONFLICT)))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { androidx.compose.material3.MaterialTheme {
                    MangaContinuousReader("Recorded diagnostics QA", listOf(ChapterPage(37, "content://actual-original", original.path, contentRevision = sha)), "diagnostic-qa",
                        translated = false, overlays = emptyMap(), onTranslate = {}, onDownload = {}, onMenu = {}, onLongPressPage = {}, ocrDiagnostics = mapOf(37 to diagnostic))
                } } }
                clickSettledUi(device, By.text("Reader tools").pkg(app.packageName), 8000)
                clickSettledUi(device, By.desc("Show developer OCR region boxes").pkg(app.packageName), 8000)
                assertTrue(device.wait(Until.hasObject(By.desc("Recorded OCR region boxes").pkg(app.packageName)), 8000))
                clickSettledUi(device, By.desc("Open recorded OCR diagnostics").pkg(app.packageName), 8000)
                assertTrue(device.wait(Until.hasObject(By.textContains("LATIN · confidence 0.81").pkg(app.packageName)), 8000))
                assertTrue(device.wait(Until.hasObject(By.textContains("geometry conflict").pkg(app.packageName)), 8000))
                assertArrayEquals(bytes, original.readBytes())
            }
        } finally { original.delete() }
    }
}
