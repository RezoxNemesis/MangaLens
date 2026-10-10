package com.mangalens

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.mangalens.core.reader.ChapterPage
import com.mangalens.ui.reader.MangaContinuousReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Authored UNRUN. Existing ReaderModes/Restoration oracles are unmodified and remain required. */
@RunWith(AndroidJUnit4::class)
class ReaderAdditionalModesInstrumentedTest {
    @Test fun continuousHorizontalAndSinglePageRetainSparseImportedPageDestination()=coreScreenSmoke("reader-additional-modes") {
        val app=instrumentation.targetContext; val prefs=app.getSharedPreferences("mangalens_reader",0)
        val displayBefore=prefs.all.filterKeys { it.startsWith("display_v1_") }; val mode=prefs.getString("default_mode",null); val key="mode_Reader additions QA"; val old=prefs.getString(key,null)
        val files=(0..2).map { n->File(app.cacheDir,"reader-additional-$n.png").apply {
            val bitmap=Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.WHITE)
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        } }
        val pages=files.mapIndexed { n,f->ChapterPage(37+n*5,"content://same-document",f.path) }
        val observed=AtomicReference<Triple<Int,Int,String>?>(); val device=UiDevice.getInstance(instrumentation)
        try {
            prefs.edit().also { e -> prefs.all.keys.filter { it.startsWith("display_v1_") }.forEach(e::remove) }.putString("default_mode","vertical").putString(key,"vertical").commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a->a.setContent { androidx.compose.material3.MaterialTheme {
                    MangaContinuousReader("Reader additions QA",pages,"reader-additions",initialPosition=1,translated=false,overlays=emptyMap(),
                        onPositionChanged={ _,p,o->observed.set(Triple(p,o,prefs.getString("default_mode","").orEmpty())) },onTranslate={},onDownload={},onMenu={},onLongPressPage={})
                } } }
                clickSettledUi(device,By.text("Reader tools").pkg(app.packageName),8000)
                clickSettledUi(device,By.text("Continuous horizontal").pkg(app.packageName),8000)
                assertSettled(observed,1,"horizontal")
                clickSettledUi(device,By.text("Single page").pkg(app.packageName),8000)
                assertSettled(observed,1,"single")
                clickSettledUi(device,By.text("Next ›").pkg(app.packageName),8000)
                assertSettled(observed,2,"single")
                assertTrue(pages.all { it.sourceUrl=="content://same-document" })
            }
        } finally {
            val editor=prefs.edit(); prefs.all.keys.filter { it.startsWith("display_v1_") }.forEach(editor::remove); displayBefore.forEach { (k,v) -> when(v) { is String->editor.putString(k,v); is Int->editor.putInt(k,v); is Float->editor.putFloat(k,v); is Boolean->editor.putBoolean(k,v) } }; if(mode==null)editor.remove("default_mode")else editor.putString("default_mode",mode)
            if(old==null)editor.remove(key)else editor.putString(key,old);editor.commit();files.forEach { it.delete() }
        }
    }
    private fun assertSettled(observed:AtomicReference<Triple<Int,Int,String>?>,page:Int,mode:String) {
        val end=android.os.SystemClock.elapsedRealtime()+8000
        do { if(observed.get()?.let { it.first==page && it.third==mode }==true)return; android.os.SystemClock.sleep(50) } while(android.os.SystemClock.elapsedRealtime()<end)
        fail("Missing accepted ordinal $page; actual=${observed.get()}")
    }
}
