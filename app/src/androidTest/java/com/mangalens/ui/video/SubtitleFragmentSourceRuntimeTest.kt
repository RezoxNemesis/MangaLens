package com.mangalens.ui.video

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.download.OriginalFragmentPlan
import com.mangalens.download.OriginalMediaFragment
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Authored UNRUN. Actual pinned AAC→selected original bytes→held FD→full PCM. No ASR/meaning claim. */
@RunWith(AndroidJUnit4::class)
class SubtitleFragmentSourceRuntimeTest {
    @Test fun selectedFullAudioSpoolDecodesExactLocalPcmAndColdReceiptWithoutAnchorRequests(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bytes = instrumentation.context.assets.open("original-media/audio-aac-8.m4a").use { it.readBytes() }
        val digest = sha(bytes)
        assertEquals("788a4d7b262ee0b28119de8ac5193260f1e46e3d35829ca0ee7be8a5e33a7a20", digest)
        val id = UUID.randomUUID().toString().replace("-", "")
        val local = File(context.cacheDir, "fragment-source-$id.m4a").apply { writeBytes(bytes) }
        val parts = linkedMapOf<String, ByteArray>()
        val requests = AtomicInteger(); val anchorRequests = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            requests.incrementAndGet()
            val payload = parts[request.requestUrl?.encodedPath]
            if (payload == null) { anchorRequests.incrementAndGet(); return MockResponse().setResponseCode(404).setBody("Anchor is not a full audio track") }
            return MockResponse().setHeader("Content-Type", "audio/mp4").setBody(Buffer().write(payload))
        } }
        server.start()
        val slices = listOf(0, 1024, bytes.size / 2, bytes.size).zipWithNext().mapIndexed { index, (start, end) ->
            val path = "/original-$index"; val fragment = bytes.copyOfRange(start, end); parts[path] = fragment
            OriginalMediaFragment(server.url(path).toString(), expectedBytes = fragment.size.toLong())
        }
        val source = SubtitleMediaSource(server.url("/manifest-looking-anchor.mpd").toString(), cacheKey = "actual-$id",
            sourceResolutionId = id, fragmentPlan = OriginalFragmentPlan(server.url("/manifest-looking-anchor.mpd").toString(),
                "actual-aac", "audio/mp4", 8_000_000, slices))
        val expected = SubtitleSourceIdentity(source, SubtitleInputs.fragmentDescriptor(source), false)
        val ownedFolder = File(context.filesDir, "subtitle_fragment_sources/${expected.fingerprint}")
        var safeCleanup = false
        var stopped = false
        try {
            val reference = mutableListOf<Pair<Long, FloatArray>>()
            withTimeout(30_000) { SubtitleAudioDecoder(context).decode(SubtitleMediaSource(Uri.fromFile(local).toString())) { pcm, start, _, _ -> reference += start to pcm.copyOf() } }
            assertTrue(reference.isNotEmpty()); assertTrue(reference.sumOf { it.second.size } >= 16_000 * 8)
            val decoded = mutableListOf<Pair<Long, FloatArray>>()
            val proof = withTimeout(30_000) { SubtitleFragmentSources.withSource(context, expected) { verified ->
                assertTrue(hasSubtitleSourceProof(verified)); assertEquals(digest, verified.fragmentContentSha256)
                assertEquals(bytes.size.toLong(), verified.fragmentSize)
                SubtitleAudioDecoder(context).decode(verified.source) { pcm, start, _, _ -> decoded += start to pcm.copyOf() }
                verified
            } }
            assertEquals(reference.map { it.first }, decoded.map { it.first })
            assertEquals(reference.size, decoded.size)
            reference.zip(decoded).forEach { (a, b) -> assertArrayEquals("Same actual encoded source must preserve every decoded sample", a.second, b.second, 0f) }
            val tail = decoded.last().first + decoded.last().second.size * 1000L / 16000
            assertTrue("Full eight-second decoded tail was lost", tail in 7500..9500)
            assertEquals(0, anchorRequests.get()); assertEquals(3, requests.get())
            server.shutdown(); stopped = true
            val cold = withTimeout(30_000) { SubtitleFragmentSources.capture(context, source) }
            assertTrue(canTrustSubtitleSource(proof, cold)); assertEquals(digest, cold.fragmentContentSha256)
            assertEquals(3, requests.get()) // Completed source capture is a local held-file revalidation.
            val missing = source.copy(sourceResolutionId = UUID.randomUUID().toString().replace("-", ""))
            assertFalse(hasSubtitleSourceProof(withTimeout(30_000) { SubtitleFragmentSources.capture(context, missing) }))
            safeCleanup = true
        } finally {
            if (!stopped) server.shutdown()
            local.delete()
            // No source cleanup is allowed if the actual retained producer did not acknowledge release.
            if (safeCleanup) ownedFolder.deleteRecursively()
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
