package com.mangalens.download

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DownloadRequestContextStoreTest {
    @Test fun durationAndHeadersSurviveRestartAndInterruptedReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "duration-context-fixture"
        val store = DownloadRequestContextStore(context)
        val media = ResolvedMediaLink("https://cdn.example/video.mp4", "video/mp4",
            headers = mapOf("Referer" to "https://example.com/watch/1"),
            expectedDurationUs = 3_600_000_000L)
        try {
            store.write(id, media)
            assertEquals(media, DownloadRequestContextStore(context).readMedia(id))
            val file = AtomicFile(File(context.filesDir, "download_request_context/$id.json"))
            val interrupted = file.startWrite()
            interrupted.write("{incomplete".toByteArray())
            file.failWrite(interrupted)
            assertEquals(media, DownloadRequestContextStore(context).readMedia(id))
        } finally { store.remove(id) }
    }
}
