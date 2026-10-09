package com.mangalens

import android.util.Base64
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.download.LocalMediaMuxer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class LocalMediaMuxerTest {
    @Test fun headerOnlyMp4CannotBePublishedAsCompletedVideo() {
        val test = InstrumentationRegistry.getInstrumentation().context
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val source = test.assets.open("video/ocr-frame.mp4.base64").use { Base64.decode(it.readBytes(), Base64.DEFAULT) }
        val output = File(app.cacheDir, "header-only.mp4")
        try {
            output.outputStream().use { destination ->
                var offset = 0
                while (offset < source.size) {
                    val box = java.nio.ByteBuffer.wrap(source, offset, source.size - offset)
                    val shortSize = box.int.toLong() and 0xffffffffL
                    val type = ByteArray(4).also { box.get(it) }.toString(Charsets.US_ASCII)
                    val size = when (shortSize) {
                        0L -> (source.size - offset).toLong()
                        1L -> box.long
                        else -> shortSize
                    }
                    require(size >= 8L && size <= source.size - offset)
                    if (type != "mdat") destination.write(source, offset, size.toInt())
                    offset += size.toInt()
                }
            }
            assertTrue("Metadata-only MP4 was accepted", runCatching { LocalMediaMuxer.videoHeight(output) }.isFailure)
        } finally { output.delete() }
    }

    @Test fun separateTracksProduceVerifiedVideoWithAudio() {
        val test = InstrumentationRegistry.getInstrumentation().context
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val videoBytes = test.assets.open("video/ocr-frame.mp4.base64").use { Base64.decode(it.readBytes(), Base64.DEFAULT) }
        val audioBytes = test.assets.open("video/mux-audio.m4a.base64").use { Base64.decode(it.readBytes(), Base64.DEFAULT) }
        val video = File(app.cacheDir, "mux-fixture-video.mp4").apply { writeBytes(videoBytes) }
        val audio = File(app.cacheDir, "mux-fixture-audio.m4a").apply { writeBytes(audioBytes) }
        val output = File(app.cacheDir, "mux-fixture-output.mp4")
        try {
            val originalVideo = track(video, "video/")
            val originalAudio = track(audio, "audio/")
            LocalMediaMuxer.mux(video, audio, output)
            assertEquals(360, LocalMediaMuxer.videoHeight(output, requireAudio = true, expectedDurationUs = 5_000_000L))
            assertTrue(output.length() > 0)
            val outputVideo = track(output, "video/")
            val outputAudio = track(output, "audio/")
            val videoOrigin = assertOriginalTrack(originalVideo, outputVideo)
            val audioOrigin = assertOriginalTrack(originalAudio, outputAudio)
            assertTrue("Muxing independently shifted audio and video", abs(videoOrigin - audioOrigin) <= 2_000L)
        } finally { video.delete(); audio.delete(); output.delete() }
    }

    private data class Track(val configuration: Map<String, String>, val payloads: List<String>, val timestamps: List<Long>)

    /** Inspect actual encoded packets, including negative AAC priming timestamps. */
    private fun track(file: File, prefix: String): Track {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).single {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
            }
            val format = extractor.getTrackFormat(index)
            val configuration = (0..2).mapNotNull { number ->
                val key = "csd-$number"
                format.getByteBuffer(key)?.duplicate()?.let { data ->
                    key to hash(ByteArray(data.remaining()).also(data::get))
                }
            }.toMap()
            extractor.selectTrack(index)
            val payloads = mutableListOf<String>()
            val timestamps = mutableListOf<Long>()
            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            while (extractor.sampleTrackIndex >= 0) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                assertTrue("Original fixture contains an empty encoded packet", size > 0)
                buffer.position(0); buffer.limit(size)
                payloads += hash(ByteArray(size).also(buffer::get))
                timestamps += extractor.sampleTime
                extractor.advance()
            }
            assertTrue("Missing actual encoded $prefix samples", payloads.isNotEmpty())
            return Track(configuration, payloads, timestamps)
        } finally { extractor.release() }
    }

    private fun assertOriginalTrack(original: Track, output: Track): Long {
        assertEquals("Codec configuration changed", original.configuration, output.configuration)
        assertEquals("An original packet was dropped, changed or reordered", original.payloads, output.payloads)
        val offset = output.timestamps.first() - original.timestamps.first()
        original.timestamps.zip(output.timestamps).forEachIndexed { index, (before, after) ->
            // MP4 time-base rounding must not mask a dropped tail or per-packet timing change.
            assertTrue("Original packet $index timing changed", abs(after - before - offset) <= 2_000L)
        }
        return offset
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

