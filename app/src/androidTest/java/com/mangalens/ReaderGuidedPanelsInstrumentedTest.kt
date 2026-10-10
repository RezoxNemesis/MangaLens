package com.mangalens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableLongStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.translation.ChapterTranslationStore
import com.mangalens.ui.reader.MangaContinuousReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Authored UNRUN. Actual original PNG + UI, never a synthetic native translation/crop receipt. */
@RunWith(AndroidJUnit4::class)
class ReaderGuidedPanelsInstrumentedTest {
    @Test fun originalGutterEstimatesNavigateAndOfferWholePageWithoutChangingTheSource()=screen(false)
    @Test fun changedUiPresentationKeyRetiresPreviousPanelSelectionBeforeFreshDetection()=screen(true)

    private fun screen(changeKey:Boolean)=coreScreenSmoke("reader-guided-panels") {
        val app=instrumentation.targetContext;val preferences=app.getSharedPreferences("mangalens_reader",0);val before=preferences.all
        val original=File(app.cacheDir,"reader-guided-original.png")
        val bitmap=Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE);val canvas=Canvas(bitmap);val ink=Paint().apply { color=Color.BLACK }
            canvas.drawRect(32f,32f,288f,216f,ink);canvas.drawRect(32f,264f,288f,448f,ink)
            original.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
        val bytes=original.readBytes();val hash=ChapterTranslationStore.sha256(original);val uiOwner=mutableLongStateOf(1L)
        try {
            val editor=preferences.edit();preferences.all.keys.filter { it.startsWith("display_v1_") }.forEach(editor::remove)
            assertTrue(editor.putString("default_mode","guided").putString("mode_Reader guided QA","guided").commit())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a -> a.setContent { androidx.compose.material3.MaterialTheme {
                    MangaContinuousReader("Reader guided QA",listOf(ChapterPage(37,"content://actual-document",original.path,contentRevision=hash)),
                        "guided-qa",translated=false,overlays=emptyMap(),onTranslate={},onDownload={},onMenu={},onLongPressPage={},
                        readerPresentationEpoch=uiOwner.longValue)
                } } }
                assertTrue("Actual original gutter controls missing",device.wait(Until.hasObject(By.text("Panel 1 / 2").pkg(app.packageName)),8000))
                clickSettledUi(device,By.desc("Next guided panel").pkg(app.packageName),8000)
                assertTrue(device.wait(Until.hasObject(By.text("Panel 2 / 2").pkg(app.packageName)),8000))
                if(changeKey) {
                    scenario.onActivity { uiOwner.longValue=2L }
                    assertTrue("Old UI panel selection survived a changed presentation key",device.wait(Until.hasObject(By.text("Panel 1 / 2").pkg(app.packageName)),8000))
                } else {
                    clickSettledUi(device,By.text("Whole page").pkg(app.packageName),8000)
                    assertTrue(device.wait(Until.hasObject(By.text("Return to panel").pkg(app.packageName)),8000))
                    clickSettledUi(device,By.text("Return to panel").pkg(app.packageName),8000)
                    assertTrue(device.wait(Until.hasObject(By.text("Panel 2 / 2").pkg(app.packageName)),8000))
                }
                assertArrayEquals(bytes,original.readBytes());assertEquals(hash,ChapterTranslationStore.sha256(original))
            }
        } finally {
            val restore=preferences.edit().clear()
            before.forEach { (k,v) -> when(v) { is String->restore.putString(k,v);is Int->restore.putInt(k,v);is Long->restore.putLong(k,v);is Float->restore.putFloat(k,v);is Boolean->restore.putBoolean(k,v);is Set<*>->restore.putStringSet(k,v.filterIsInstance<String>().toSet()) } }
            assertTrue(restore.commit());original.delete()
        }
    }
}
