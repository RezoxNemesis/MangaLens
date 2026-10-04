package com.mangalens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual bundled Python/yt-dlp runtime, with no external-site dependency. */
@RunWith(AndroidJUnit4::class)
class SiteExtractorRuntimeTest {
    @Test fun bundledSiteExtractorExecutesInInstalledAndroidApp() {
        YoutubeDL.init(InstrumentationRegistry.getInstrumentation().targetContext)
        val request = YoutubeDLRequest("https://example.com/").apply { addOption("--version") }
        val result = YoutubeDL.execute(request, processId = "qa-extractor-version", callback = null)
        assertTrue("Bundled extractor did not execute: ${result.out}",
            Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}").containsMatchIn(result.out))
    }
}
