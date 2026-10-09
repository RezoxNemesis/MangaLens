package com.mangalens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.mangalens.core.reader.ChapterPage
import com.mangalens.ui.reader.MangaContinuousReader
import com.mangalens.ui.theme.MangaLensTheme
import com.mangalens.ui.theme.ThemeMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Actual Reader selector/callback; target persistence is covered by native configuration tests. */
@RunWith(AndroidJUnit4::class)
class ReaderHinglishTargetSmokeTest {
    @Test fun readerChoosesTheFullRomanTargetAndCanReturnToHindi() = coreScreenSmoke("reader-hinglish-target") {
        val prefs = context.getSharedPreferences("mangalens_reader", 0)
        val restore = restorePreferencesAfter(prefs, "default_mode", "mode_Reader Hinglish QA")
        val directory = File(context.cacheDir, "reader-hinglish-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(directory, "source.png")
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val selected = AtomicReference("hi")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            prefs.edit().putString("default_mode", "vertical").remove("mode_Reader Hinglish QA").commit()
            val active = ActivityScenario.launch(MainActivity::class.java)
            scenario = active
            active.onActivity { activity -> activity.setContent {
                var target by remember { mutableStateOf("hi") }
                MangaLensTheme(themeMode = ThemeMode.DARK) {
                    MangaContinuousReader("Reader Hinglish QA", listOf(ChapterPage(1, "local:qa:hinglish", source.absolutePath)),
                        chapterId = directory.name, translated = false, overlays = emptyMap(), targetLanguage = target,
                        onTargetLanguageChanged = { code -> selected.set(code); target = code },
                        onTranslate = {}, onDownload = {}, onMenu = {}, onLongPressPage = {})
                }
            } }
            node(By.desc("Page 1"))
            if (device.findObject(By.text("Reader tools")) == null) device.click(device.displayWidth / 2, device.displayHeight / 2)
            tap(By.text("Reader tools"))
            tap(By.text("Language: HI ▾"))
            tap(By.text("Hinglish (Roman Hindi)"))
            waitFor("Reader collapsed the full output target into hi") { selected.get() == "hi-latn" }
            node(By.text("Language: Hinglish ▾"))
            capture("roman-target-selected")
            tap(By.text("Language: Hinglish ▾"))
            tap(By.text("Hindi"))
            waitFor("Reader could not return to ordinary Hindi") { selected.get() == "hi" }
            node(By.text("Language: HI ▾"))
            capture("hindi-target-selected")
            File(context.getExternalFilesDir(null), "qa/core-smoke/reader-hinglish-target/target.json")
                .apply { parentFile!!.mkdirs() }.writeText(JSONObject().put("roman_target", "hi-latn")
                    .put("returned_target", selected.get()).put("source_sha", BuildConfig.SOURCE_SHA).toString(2))
        } catch (failure: Throwable) {
            runCatching { recordFailure(failure) }
            throw failure
        } finally {
            scenario?.close()
            directory.deleteRecursively()
            restore()
        }
    }
}
