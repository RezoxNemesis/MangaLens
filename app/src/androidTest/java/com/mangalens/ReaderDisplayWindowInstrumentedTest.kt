package com.mangalens

import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mangalens.ui.reader.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Authored UNRUN. Actual Android property adapter and scalar preference persistence. */
@RunWith(AndroidJUnit4::class)
class ReaderDisplayWindowInstrumentedTest {
    @Test fun realReaderWindowLeaseRestoresOriginalBrightnessOrientationAndAwakeFlag() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val orientation=activity.requestedOrientation; val brightness=activity.window.attributes.screenBrightness
            val awake=activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            val lease=ReaderWindowLease(activity)
            try {
                lease.update(ReaderWindowSettings(ReaderWindowOrientation.PORTRAIT,true,.37f,true))
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,activity.requestedOrientation)
                assertEquals(.37f,activity.window.attributes.screenBrightness,0f)
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
                lease.deactivate()
                assertEquals(orientation,activity.requestedOrientation); assertEquals(brightness,activity.window.attributes.screenBrightness,0f)
                assertEquals(awake,activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            } finally { lease.close() }
        } }
    }
    @Test fun foreignWindowBrightnessIsNotOverwrittenWhenReaderLeaseCloses() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val old=activity.window.attributes.screenBrightness; val lease=ReaderWindowLease(activity)
            try {
                lease.update(ReaderWindowSettings(brightnessOverride=.37f))
                activity.window.attributes=activity.window.attributes.apply { screenBrightness=.82f }
                lease.close(); assertEquals(.82f,activity.window.attributes.screenBrightness,0f)
            } finally { lease.close(); activity.window.attributes=activity.window.attributes.apply { screenBrightness=old } }
        } }
    }
    @Test fun realPreferencesRoundTripRestoresAllDisplayScalarsWithoutChangingOldModeKeys()=runBlocking {
        val app=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=app.getSharedPreferences("mangalens_reader",0); val old=prefs.all
        val options=ReaderDisplayOptions(ReaderWindowSettings(ReaderWindowOrientation.LANDSCAPE,true,.42f,true),32,.1f,true,ReaderComparison.SPLIT,.6f,true)
        try {
            val oldMode=prefs.getString("default_mode",null); val oldScale=prefs.getFloat("text_scale",1f)
            val storage=ReaderDisplayPreferences(app); storage.save(options)
            assertEquals(options,storage.load()); assertEquals(oldMode,prefs.getString("default_mode",null)); assertEquals(oldScale,prefs.getFloat("text_scale",1f),0f)
        } finally {
            withContext(Dispatchers.IO) {
                val editor=prefs.edit().clear()
                old.forEach { (k,v) -> when(v) { is String->editor.putString(k,v); is Int->editor.putInt(k,v); is Long->editor.putLong(k,v); is Float->editor.putFloat(k,v); is Boolean->editor.putBoolean(k,v); is Set<*>->editor.putStringSet(k,v.filterIsInstance<String>().toSet()) } }
                check(editor.commit())
            }
        }
    }
}
