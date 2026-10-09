package com.mangalens

import android.net.Uri
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mangalens.ui.video.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Real Android AtomicFile and MediaExtractor/MediaCodec checks; no speech-quality claim. */
@RunWith(AndroidJUnit4::class)
class SubtitleGenerationDurabilityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun atomicJournalBackupRecoveryAndExportPairRetainCompletedWindows() {
        val directory = File(context.filesDir, "subtitle-test-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val store = SubtitleGenerationStore(directory)
            val source = SubtitleSourceIdentity(SubtitleMediaSource("content://fixture/video"), "a".repeat(64))
            val task = store.start(source, SubtitleGenerationConfig(modelSha256 = "b".repeat(64)))
            store.running(task.id, task.generation)
            val window = SubtitleWindow(0, 0, 8000, "c".repeat(64), listOf(SpeechCue(500, 2500, "Completed window.")))
            assertTrue(store.checkpoint(task.id, task.generation, window, 8000))
            val complete = store.finish(task.id, task.generation)!!
            val journal = File(directory, task.id + ".json")
            val backup = File(journal.path + ".bak")
            assertTrue(journal.renameTo(backup)); journal.writeText("{interrupted replacement")
            val recovered = SubtitleGenerationStore(directory).get(task.id)!!
            assertTrue(recovered.validationPending)
            assertEquals(listOf(window), recovered.windows)
            assertEquals(complete.srtSha256, SubtitleGenerationStore.fileHash(File(recovered.srtPath!!)))
            assertTrue(File(recovered.vttPath!!).readText().startsWith("WEBVTT"))
            assertFalse(backup.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun actualSilentAudioDecodeUsesBoundedOverlappingWindowsAndReachesTail() = runBlocking {
        val file = File(context.filesDir, "subtitle-audio-" + UUID.randomUUID() + ".wav")
        try {
            val sampleCount = 16_000 * 12
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + sampleCount * 2).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(sampleCount * 2)
            file.outputStream().use { output -> output.write(header.array()); repeat(12) { output.write(ByteArray(32_000)) } }
            val starts = ArrayList<Long>(); var end = 0L
            SubtitleAudioDecoder(context).decode(SubtitleMediaSource(Uri.fromFile(file).toString())) { samples, start, _, _ ->
                assertTrue(samples.size <= 16_000 * 8)
                assertFalse(SpeechWindowPolicy.hasActivity(samples))
                starts += start; end = start + samples.size * 1000L / 16_000
            }
            assertTrue(starts.size >= 2)
            assertEquals(0L, starts.first())
            assertEquals(7000L, starts[1])
            assertTrue(end in 11_950L..12_050L)
        } finally { file.delete() }
    }

    @Test fun srtAndVttDocumentRequestsKeepAcceptedBytesAcrossActualActivityRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val source = SubtitleMediaSource("content://fixture/export-" + UUID.randomUUID())
        val accepted = FullSubtitleState(taskId = "a".repeat(32), generation = "b".repeat(32), sourceCacheKey = source.cacheKey,
            cues = listOf(SpeechCue(0, 1000, "Accepted original speech.")), srt = "1\n00:00:00,000 --> 00:00:01,000\nAccepted original speech.\n",
            vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nAccepted original speech.\n")
        val displayed = mutableStateOf(accepted)
        // MainActivity's own setContent runs after super.onCreate; lifecycle
        // onActivityCreated content is overwritten before this fixture can click.
        fun installExportComponent(activity: MainActivity) = activity.setContent {
                    MaterialTheme {
                        val export = rememberSubtitleExportActions(displayed.value, source)
                        Column {
                            Button(onClick = export.srt, enabled = export.srtEnabled) { Text(export.srtLabel) }
                            Button(onClick = export.vtt, enabled = export.vttEnabled) { Text(export.vttLabel) }
                            export.status?.let { Text(it) }
                        }
                    }
        }
        // Hold the real ActivityResultRegistry launch without opening another app.
        // A controlled private destination is returned after ActivityScenario.recreate.
        val filter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") }
        val monitor = instrumentation.addMonitor(filter, null, true)
        val device = UiDevice.getInstance(instrumentation)
        val destinations = ArrayList<File>()
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity(::installExportComponent)
                for (format in listOf("SRT", "VTT")) {
                    scenario.onActivity { displayed.value = accepted }
                    val button = device.wait(Until.findObject(By.text("Export $format")), 15_000) ?: error("Missing export button")
                    button.click()
                    assertNotNull(device.wait(Until.findObject(By.text("Retry $format")), 15_000))
                    var requestCode = 0
                    scenario.onActivity { activity -> requestCode = launchedDocumentRequest(activity.activityResultRegistry) }
                    displayed.value = FullSubtitleState(taskId = "c".repeat(32), generation = "d".repeat(32), sourceCacheKey = "content://fixture/new-video")
                    scenario.recreate()
                    scenario.onActivity(::installExportComponent)
                    assertNotNull(device.wait(Until.findObject(By.text("Retry $format")), 15_000))
                    val file = File(context.filesDir, "subtitle-export-test-" + UUID.randomUUID() + "." + format.lowercase()).apply { destinations += this }
                    scenario.onActivity { activity ->
                        assertTrue(activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
                    }
                    val expected = if (format == "SRT") accepted.srt else accepted.vtt
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    while ((!file.exists() || file.length() != expected.toByteArray(Charsets.UTF_8).size.toLong()) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
                    assertEquals(expected, file.readText(Charsets.UTF_8))
                    assertTrue("Picker launch was not held", monitor.hits > 0)
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            destinations.forEach { it.delete() }
        }
    }

    private fun launchedDocumentRequest(registry: ActivityResultRegistry): Int {
        val launched = ActivityResultRegistry::class.java.getDeclaredField("launchedKeys").apply { isAccessible = true }.get(registry) as List<*>
        val codes = ActivityResultRegistry::class.java.getDeclaredField("keyToRc").apply { isAccessible = true }.get(registry) as Map<*, *>
        return codes[launched.single()] as Int
    }

    @Test fun actualOnlineAudioDecodeScopesEveryRedirectedHeadAndRangeConnection() = runBlocking {
        // Exceed the 512 KiB range cache, making a distinct tail fetch necessary.
        val sampleCount = 16_000 * 20
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + sampleCount * 2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(sampleCount * 2)
        val audio = ByteArray(44 + sampleCount * 2).apply {
            header.array().copyInto(this)
            // Actual PCM activity in the last 600 ms proves final-range decoding.
            for (sample in sampleCount - 9600 until sampleCount) {
                val value = if (sample % 2 == 0) 6000 else -6000
                this[44 + sample * 2] = value.toByte()
                this[45 + sample * 2] = (value shr 8).toByte()
            }
        }
        okhttp3.mockwebserver.MockWebServer().use { origin -> okhttp3.mockwebserver.MockWebServer().use { target ->
            val targetUrl = target.url("/audio").newBuilder().host("127.0.0.1").build().toString()
            val leaked = java.util.concurrent.atomic.AtomicBoolean(false)
            val ranges = java.util.concurrent.CopyOnWriteArrayList<LongRange>()
            val sawHead = java.util.concurrent.atomic.AtomicBoolean(false)
            val etag = "\"fixture-audio-v1\""
            origin.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", targetUrl)
            }
            target.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    if (listOf("Cookie", "Authorization", "Referer").any { request.getHeader(it) != null }) leaked.set(true)
                    if (request.method == "HEAD") {
                        sawHead.set(true)
                        return okhttp3.mockwebserver.MockResponse().setHeader("Content-Length", audio.size).setHeader("ETag", etag)
                    }
                    val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.getHeader("Range").orEmpty())!!
                    val start = match.groupValues[1].toInt(); val end = minOf(match.groupValues[2].toLong(), audio.lastIndex.toLong()).toInt()
                    ranges += start.toLong()..end.toLong()
                    if (start > audio.lastIndex) return okhttp3.mockwebserver.MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${audio.size}")
                    return okhttp3.mockwebserver.MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${audio.size}").setHeader("ETag", etag)
                        .setBody(okio.Buffer().write(audio, start, end - start + 1))
                }
            }
            val source = SubtitleMediaSource(origin.url("/private").toString(), mapOf("Cookie" to "session=original",
                "Authorization" to "private-token", "Referer" to "https://private.invalid/account?secret=1"))
            SubtitleNetworkSource(source).use { assertEquals(audio.size.toLong(), it.size()) }
            val identity = SubtitleInputs.capture(context, source)
            assertTrue(identity.verifiable)
            assertEquals(etag, identity.strongEtag)
            assertEquals(targetUrl, identity.networkUrl)
            val starts = ArrayList<Long>(); var end = 0L; var activeTail = false
            SubtitleAudioDecoder(context).decode(source, identity.strongEtag, identity.networkSize, identity.networkUrl) { samples, start, _, _ ->
                assertTrue(samples.size <= 16_000 * 8)
                if (start < 14_000L) assertFalse(SpeechWindowPolicy.hasActivity(samples))
                else activeTail = SpeechWindowPolicy.hasActivity(samples)
                starts += start; end = start + samples.size * 1000L / 16_000
            }
            assertEquals(listOf(0L, 7000L, 14000L), starts)
            assertTrue(end in 19_950L..20_050L)
            assertTrue("Decoded tail lacks its final PCM activity", activeTail)
            assertTrue("Scoped HEAD did not reach the final resource", sawHead.get())
            assertTrue("No GET byte proof was captured", ranges.any { it.first == 0L && it.last == 0L })
            assertTrue("Decoder did not fetch a distinct final range", ranges.any { it.first > 0L && it.last == audio.lastIndex.toLong() })
            assertFalse(leaked.get())
            assertEquals("session=original", origin.takeRequest().getHeader("Cookie"))
        } }
    }
}
