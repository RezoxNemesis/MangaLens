package com.mangalens

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.view.TextureView
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
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
        var scenario: ActivityScenario<MainActivity>? = null
        var playerView: PlayerView? = null
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
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario!!.onActivity { activity ->
                playerView = LayoutInflater.from(activity).inflate(R.layout.ocr_player_view, null) as PlayerView
                activity.setContentView(playerView)
                vm = LocalVideoPlayerViewModel(app); store.put("uploaded_sample", vm)
                vm.bind(playerView!!)
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
            // Seeking is asynchronous and can rebuffer, especially on software emulators.
            // Verify eventual decoded playback rather than assuming a 1.5-second seek budget.
            withTimeout(60_000) {
                var advanced = false
                while (!advanced) {
                    instrumentation.runOnMainSync {
                        assertNull("Seek failed playback", vm.player.playerError)
                        advanced = vm.player.playbackState == Player.STATE_READY && vm.player.currentPosition > 12000
                    }
                    if (!advanced) delay(100)
                }
            }
            var frame: Bitmap? = null
            withTimeout(30_000) {
                while (frame == null) {
                    instrumentation.runOnMainSync {
                        assertNull(vm.player.playerError)
                        val texture = playerView!!.videoSurfaceView as TextureView
                        if (texture.isAvailable && vm.player.videoSize.width == 1280) {
                            val image = texture.getBitmap(640, 288)
                            if (image != null) {
                                val pixels = (0 until 288 step 8).flatMap { y -> (0 until 640 step 8).map { x -> image.getPixel(x, y) } }
                                val values = pixels.map { android.graphics.Color.red(it) + android.graphics.Color.green(it) + android.graphics.Color.blue(it) }
                                if (values.max() - values.min() > 60) frame = image else image.recycle()
                            }
                        }
                    }
                    if (frame == null) delay(100)
                }
            }
            try {
                File(app.getExternalFilesDir(null), "sample-native-frame.png").outputStream().use {
                    frame!!.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { frame?.recycle() }
            File(app.filesDir, "sample-download-playback.txt").writeText("SHA256=${digest(source)}\nbytes=${downloaded.length()}\ndurationMs=$duration\nofflinePlayback=true\nrenderedVideoFrame=true\n")
        } finally {
            instrumentation.runOnMainSync { playerView?.player = null; store.clear() }
            scenario?.close()
            runCatching { server.shutdown() }
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            downloaded.delete(); validator.delete()
        }
    }
}
