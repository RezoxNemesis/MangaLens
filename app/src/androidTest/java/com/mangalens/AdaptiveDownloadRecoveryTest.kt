package com.mangalens

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.download.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AdaptiveDownloadRecoveryTest {
    @Test fun realAdaptiveDownloadsRetryPauseResumeAndRemoveWithoutStateResurrection() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val assets = instrumentation.context.assets
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val fail = AtomicBoolean(true)
        val slow = AtomicBoolean(false)
        val segmentStarted = AtomicReference(CountDownLatch(1))
        val requests = ConcurrentLinkedQueue<RecordedRequest>()
        val server = MockWebServer().apply {
            useHttps(serverTrust.sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    val filename = request.requestUrl?.pathSegments?.lastOrNull().orEmpty()
                    if (filename == "playlist.m3u8") return MockResponse().setHeader("Content-Type", MimeTypes.APPLICATION_M3U8)
                        .setBody(assets.open("hls/playlist.m3u8").bufferedReader().use { it.readText() })
                    if (!filename.matches(Regex("mangalens-qa-[0-9]{2}\\.ts"))) return MockResponse().setResponseCode(404)
                    segmentStarted.get().countDown()
                    if (fail.get()) return MockResponse().setResponseCode(404)
                    val bytes = Base64.decode(assets.open("hls/$filename.base64").bufferedReader().use { it.readText() }, Base64.DEFAULT)
                    return MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", "video/mp2t")
                        .apply { if (slow.get()) throttleBody(512, 1, TimeUnit.SECONDS) }
                }
            }
            start()
        }
        val client = OkHttpClient.Builder().sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager).build()
        val executor = Executors.newSingleThreadExecutor()
        lateinit var nativeManager: DownloadManager
        fun createNativeManager() = instrumentation.runOnMainSync {
            nativeManager = MangaLensDownloadService.Holder.createManager(app, OkHttpDataSource.Factory(client), executor)
            nativeManager.minRetryCount = 0
            nativeManager.setRequirements(Requirements(0))
            nativeManager.resumeDownloads()
        }
        createNativeManager()
        val commands = object : AdaptiveDownloadCommands {
            override fun add(id: String, url: String, mime: String) = instrumentation.runOnMainSync {
                nativeManager.addDownload(DownloadRequest.Builder(id, Uri.parse(url)).setMimeType(mime).build())
            }
            override fun pause(id: String) = instrumentation.runOnMainSync { nativeManager.setStopReason(id, 1) }
            override fun resume(id: String) = instrumentation.runOnMainSync { nativeManager.setStopReason(id, 0) }
            override fun remove(id: String) = instrumentation.runOnMainSync { nativeManager.removeDownload(id) }
        }
        val manager = MediaDownloadManager(app, commands)
        val dao = DownloadDatabase.get(app).downloads()
        val ids = mutableListOf<String>()
        suspend fun begin(path: String): String {
            val id = UUID.randomUUID().toString().also { ids.add(it) }
            val source = server.url("/$path/playlist.m3u8").toString()
            dao.upsert(DownloadEntity(id, source, "Recovery fixture", MimeTypes.APPLICATION_M3U8))
            commands.add(id, source, MimeTypes.APPLICATION_M3U8)
            return id
        }
        suspend fun awaitRow(id: String, state: DownloadState) = withTimeout(20_000) {
            while (dao.get(id)?.state != state) delay(20)
        }
        try {
            val failed = begin("retry")
            awaitRow(failed, DownloadState.FAILED)
            assertEquals(Download.STATE_FAILED, nativeManager.downloadIndex.getDownload(failed)!!.state)
            fail.set(false)
            manager.resume(failed)
            awaitRow(failed, DownloadState.COMPLETED)
            assertTrue(dao.get(failed)!!.bytesDownloaded > 0L)
            assertEquals(1f, dao.get(failed)!!.progress, 0f)
            // Simulate an app shutdown between Media3's terminal index write and Room's callback.
            instrumentation.runOnMainSync { nativeManager.release() }
            dao.upsert(dao.get(failed)!!.copy(state = DownloadState.DOWNLOADING, bytesDownloaded = 0, totalBytes = -1))
            createNativeManager()
            awaitRow(failed, DownloadState.COMPLETED)
            assertEquals("Completed index entry was not reconciled", 1f, dao.get(failed)!!.progress, 0f)

            slow.set(true)
            segmentStarted.set(CountDownLatch(1))
            val paused = begin("pause")
            assertTrue("No real segment transfer began", segmentStarted.get().await(10, TimeUnit.SECONDS))
            var snapshot: Download? = null
            withTimeout(10_000) {
                while (true) {
                    instrumentation.runOnMainSync { snapshot = nativeManager.currentDownloads.find { it.request.id == paused } }
                    if ((snapshot?.bytesDownloaded ?: 0) > 0) break
                    delay(20)
                }
            }
            MangaLensDownloadService.Holder.persistProgress(app, listOf(snapshot!!)).join()
            assertTrue("Live transfer bytes did not reach the download list", dao.get(paused)!!.bytesDownloaded > 0)
            manager.pause(paused)
            withTimeout(10_000) { while (nativeManager.downloadIndex.getDownload(paused)?.state != Download.STATE_STOPPED) delay(20) }
            assertEquals(DownloadState.PAUSED, dao.get(paused)!!.state)
            MangaLensDownloadService.Holder.persistProgress(app, listOf(snapshot!!)).join()
            assertEquals("Late progress undid a user pause", DownloadState.PAUSED, dao.get(paused)!!.state)
            instrumentation.runOnMainSync { nativeManager.release() }
            val segment = server.url("/pause/mangalens-qa-00.ts").toString()
            assertTrue("Pause did not preserve real cached bytes", MangaLensDownloadService.Holder.cache(app).getCachedSpans(segment).sumOf { it.length } > 0)
            createNativeManager()
            withTimeout(10_000) {
                while (true) {
                    var initialized = false
                    instrumentation.runOnMainSync { initialized = nativeManager.isInitialized }
                    if (initialized) break
                    delay(20)
                }
            }
            assertEquals("Restart discarded the stop reason", Download.STATE_STOPPED, nativeManager.downloadIndex.getDownload(paused)!!.state)
            assertEquals(DownloadState.PAUSED, dao.get(paused)!!.state)
            slow.set(false)
            manager.resume(paused)
            assertEquals("A late stopped callback overrode resume", 0,
                dao.adaptiveStateIfActive(paused, 0, -1, DownloadState.PAUSED, null))
            awaitRow(paused, DownloadState.COMPLETED)
            assertTrue("Resume did not reuse the persisted partial segment", requests.any {
                it.requestUrl?.encodedPath?.startsWith("/pause/") == true &&
                    Regex("bytes=([0-9]+)-.*").matchEntire(it.getHeader("Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.let { start -> start > 0 } == true
            })

            slow.set(true)
            segmentStarted.set(CountDownLatch(1))
            val removed = begin("remove")
            assertTrue(segmentStarted.get().await(10, TimeUnit.SECONDS))
            manager.remove(removed)
            withTimeout(20_000) { while (nativeManager.downloadIndex.getDownload(removed) != null) delay(20) }
            delay(200)
            assertNull("A removal callback recreated the deleted row", dao.get(removed))
        } finally {
            slow.set(false)
            for (id in ids) { commands.remove(id); dao.delete(id) }
            withTimeout(20_000) { while (ids.any { nativeManager.downloadIndex.getDownload(it) != null }) delay(20) }
            instrumentation.runOnMainSync { nativeManager.release() }
            executor.shutdownNow()
            server.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
