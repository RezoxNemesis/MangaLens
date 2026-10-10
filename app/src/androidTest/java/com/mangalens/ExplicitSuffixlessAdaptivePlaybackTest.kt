package com.mangalens

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.download.MangaLensDownloadService
import com.mangalens.ui.video.LocalVideoPlayerViewModel
import androidx.lifecycle.ViewModelStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Original two-second audio HLS fixture; no external source or account is involved. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExplicitSuffixlessAdaptivePlaybackTest {
    @Test fun suffixlessHlsRetainsExplicitMimeThroughAppPlayerAndPlaysAfterServerStops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val assets = instrumentation.context.assets
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val filename = request.requestUrl?.pathSegments?.lastOrNull().orEmpty()
                return when {
                    filename == "manifest" -> MockResponse().setHeader("Content-Type", MimeTypes.APPLICATION_M3U8)
                        .setBody(assets.open("hls/playlist.m3u8").bufferedReader().use { it.readText() })
                    filename.matches(Regex("mangalens-qa-[0-9]{2}\\.ts")) -> {
                        val encoded = assets.open("hls/$filename.base64").bufferedReader().use { it.readText() }
                        MockResponse().setHeader("Content-Type", "video/mp2t")
                            .setBody(Buffer().write(Base64.decode(encoded, Base64.DEFAULT)))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
        val source = server.url("/manifest").toString()
        val cache = MangaLensDownloadService.Holder.cache(app)
        val factory = CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(client))
        val downloader = HlsDownloader(MediaItem.Builder().setUri(source).setMimeType(MimeTypes.APPLICATION_M3U8).build(), factory)
        val store = ViewModelStore()
        val finished = CountDownLatch(1)
        val playbackError = AtomicReference<PlaybackException?>()
        val playbackUiAttached = AtomicBoolean(false)
        val offlinePlaybackStarted = AtomicBoolean(false)
        var foreground: ActivityScenario<MainActivity>? = null
        var presentation: ActivityScenario<MainActivity>? = null
        var stopped = false
        try {
            downloader.download(null)
            assertTrue("HLS manifest was not saved", cache.getCachedSpans(source).isNotEmpty())
            assertTrue("Fixture segments were not requested", server.requestCount >= 4)
            assertTrue("Downloads must live in durable files storage", File(app.filesDir, "media_download_cache").isDirectory)
            server.shutdown()
            stopped = true
            // Android 15 requires a foreground app/service for media audio focus.
            // Exercise the real activity and opaque production player route;
            // production audio focus and the offline completion oracle stay active.
            if (android.os.Build.VERSION.SDK_INT >= 33)
                instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            val visible = ActivityScenario.launch(MainActivity::class.java).also { foreground = it }
            lateinit var owner: LocalVideoPlayerViewModel
            visible.onActivity {
                val viewModel = LocalVideoPlayerViewModel(app)
                owner = viewModel
                store.put("qa-offline-player", viewModel)
                viewModel.player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackUiAttached.get() && playbackState == Player.STATE_ENDED &&
                            viewModel.player.playbackState == Player.STATE_ENDED) finished.countDown()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (playbackUiAttached.get() && isPlaying) offlinePlaybackStarted.set(true)
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        playbackError.set(error)
                        finished.countDown()
                    }
                })
                assertTrue(viewModel.openHttp(source, referer = source, videoMimeType = MimeTypes.APPLICATION_M3U8))
            }
            val route = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("mangalens://video/session/${owner.session.policy.sessionId}"))
            val active = ActivityScenario.launch<MainActivity>(route).also { presentation = it }
            var bound = false
            val uiDeadline = android.os.SystemClock.elapsedRealtime() + 8_000L
            while (!bound && android.os.SystemClock.elapsedRealtime() < uiDeadline) {
                active.onActivity { bound = findPlayerView(it.window.decorView)?.player === owner.player }
                if (!bound) android.os.SystemClock.sleep(100L)
            }
            assertTrue("The actual production PlayerView never bound the offline player", bound)
            active.onActivity {
                assertEquals(source, owner.player.currentMediaItem?.localConfiguration?.uri?.toString())
                assertEquals(MimeTypes.APPLICATION_M3U8, owner.player.currentMediaItem?.localConfiguration?.mimeType)
                owner.player.pause()
                owner.player.seekTo(0L)
                playbackUiAttached.set(true)
                owner.player.play()
            }
            assertTrue("Offline HLS playback never completed", finished.await(30, TimeUnit.SECONDS))
            assertNull("Playback attempted an unavailable source or could not decode the saved media", playbackError.get())
            assertTrue("Offline playback never started through the visible production player", offlinePlaybackStarted.get())
            active.onActivity { assertNotNull("The offline audio track was never decoded", owner.player.audioFormat) }
        } finally {
            try {
                try { presentation?.close() } finally { foreground?.close() }
            } finally {
                instrumentation.runOnMainSync { store.clear() }
                downloader.remove()
                if (!stopped) server.shutdown()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            findPlayerView(view.getChildAt(index))?.let { return it }
        return null
    }
}
