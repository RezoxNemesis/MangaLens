package com.mangalens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.download.ResumableMediaTransfer
import com.mangalens.ui.video.LocalVideoPlayerViewModel
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** User sample is staged locally; never bundled or uploaded into the repository. */
@RunWith(AndroidJUnit4::class)
class UploadedMediaTest {
    @Test fun suppliedVideoDownloadsByteExactlyAndPlaysOffline() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("sample_video")
        assumeTrue("Stage sample_video to exercise uploaded media", path != null)
        val app = instrumentation.targetContext.applicationContext as Application
        val source = File(path!!)
        assertTrue(source.isFile)
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager).build()
        val server = MockWebServer().apply {
            useHttps(serverTrust.sslSocketFactory(), false)
            enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setHeader("ETag", "\"user-sample\"")
                .setBody(Buffer().write(source.readBytes())))
            start()
        }
        val downloaded = File(app.cacheDir, "sample-download.mp4")
        val validator = File(app.cacheDir, "sample-download.validator")
        val store = ViewModelStore()
        lateinit var vm: LocalVideoPlayerViewModel
        try {
            ResumableMediaTransfer(client).download(server.url("/sample.mp4").toString(), downloaded, validator)
            assertEquals(source.length(), downloaded.length())
            fun digest(file: File): String {
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
                    val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n)
                } }
                return hash.digest().joinToString("") { "%02x".format(it) }
            }
            assertEquals(digest(source), digest(downloaded))
            server.shutdown() // Playback must use only the downloaded file.
            instrumentation.runOnMainSync {
                vm = LocalVideoPlayerViewModel(app); store.put("uploaded_sample", vm)
                vm.open(Uri.fromFile(downloaded))
            }
            withTimeout(60000) {
                var ready = false
                while (!ready) {
                    instrumentation.runOnMainSync {
                        assertNull("Uploaded sample failed playback", vm.player.playerError)
                        ready = vm.player.playbackState == Player.STATE_READY && vm.player.duration in 23000..24000
                    }
                    if (!ready) delay(100)
                }
            }
            var duration = 0L
            instrumentation.runOnMainSync { duration = vm.player.duration; vm.player.seekTo(12000); vm.player.play() }
            delay(1500)
            instrumentation.runOnMainSync { assertTrue("Playback did not advance", vm.player.currentPosition > 12000); assertNull(vm.player.playerError) }
            File(app.filesDir, "sample-download-playback.txt").writeText("SHA256=${digest(source)}\nbytes=${downloaded.length()}\ndurationMs=$duration\nofflinePlayback=true\n")
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            runCatching { server.shutdown() }
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            downloaded.delete(); validator.delete()
        }
    }
}
