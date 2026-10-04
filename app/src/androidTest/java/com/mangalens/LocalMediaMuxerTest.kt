package com.mangalens

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.download.LocalMediaMuxer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalMediaMuxerTest {
    @Test fun separateTracksProduceVerifiedVideoWithAudio() {
        val test = InstrumentationRegistry.getInstrumentation().context
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val videoBytes = runCatching { test.assets.open("video/ocr-frame.mp4.base64").use { Base64.decode(it.readBytes(), Base64.DEFAULT) } }.getOrNull()
        val audioBytes = runCatching { test.assets.open("video/mux-audio.m4a.base64").use { Base64.decode(it.readBytes(), Base64.DEFAULT) } }.getOrNull()
        assumeTrue("Generate mux fixtures with create-video-ocr-fixture.sh", videoBytes != null && audioBytes != null)
        val video = File(app.cacheDir, "mux-fixture-video.mp4").apply { writeBytes(videoBytes!!) }
        val audio = File(app.cacheDir, "mux-fixture-audio.m4a").apply { writeBytes(audioBytes!!) }
        val output = File(app.cacheDir, "mux-fixture-output.mp4")
        try {
            LocalMediaMuxer.mux(video, audio, output)
            assertEquals(360, LocalMediaMuxer.videoHeight(output, requireAudio = true))
            assertTrue(output.length() > 0)
        } finally { video.delete(); audio.delete(); output.delete() }
    }
}
