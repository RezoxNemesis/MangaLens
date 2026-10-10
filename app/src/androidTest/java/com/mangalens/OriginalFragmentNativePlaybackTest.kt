package com.mangalens

import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.download.OriginalFragmentPlan
import com.mangalens.download.OriginalMediaFragment
import com.mangalens.ui.video.LocalVideoPlayerViewModel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Authored UNRUN. Pinned original AVC/AAC bytes, real app route/decoder/clock; no public-provider claim. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class OriginalFragmentNativePlaybackTest {
    @Test fun selectedByteSequenceAndIndependentAudioReachTheVisibleProductionPlayerAndFullTail() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        fun fixture(name: String, sha: String): ByteArray = instrumentation.context.assets.open("original-media/$name").use { it.readBytes() }.also {
            assertEquals(sha, MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) })
        }
        val video = fixture("video-h264-720.mp4", "9fb465a7980b07698683ee2268c0531ec788328433c180b4fcff1111848dbeb1")
        val audio = fixture("audio-aac-8.m4a", "788a4d7b262ee0b28119de8ac5193260f1e46e3d35829ca0ee7be8a5e33a7a20")
        val parts = linkedMapOf<String, ByteArray>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            val name = request.requestUrl?.encodedPath.orEmpty()
            return parts[name]?.let { MockResponse().setHeader("Content-Type", if (name.startsWith("/audio")) "audio/mp4" else "video/mp4").setBody(Buffer().write(it)) }
                ?: MockResponse().setResponseCode(404).setBody("The anchor is deliberately not media")
        } }
        server.start()
        fun plan(bytes: ByteArray, role: String, mime: String): OriginalFragmentPlan {
            val boundaries = listOf(0, minOf(1024, bytes.size), bytes.size / 2, bytes.size).distinct().sorted()
            val fragments = boundaries.zipWithNext().mapIndexed { index, (start, end) ->
                val path = "/$role-$index"; val fragment = bytes.copyOfRange(start, end); parts[path] = fragment
                OriginalMediaFragment(server.url(path).toString(), expectedBytes = fragment.size.toLong())
            }
            return OriginalFragmentPlan(server.url("/$role-anchor").toString(), role, mime, 8_000_000L, fragments)
        }
        val vp = plan(video, "video", "video/mp4"); val ap = plan(audio, "audio", "audio/mp4")
        val store = ViewModelStore(); val ended = CountDownLatch(1); val firstFrame = AtomicBoolean(false)
        var foreground: ActivityScenario<MainActivity>? = null; var presentation: ActivityScenario<MainActivity>? = null
        lateinit var vm: LocalVideoPlayerViewModel
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            val visible = ActivityScenario.launch(MainActivity::class.java).also { foreground = it }
            visible.onActivity {
                vm = LocalVideoPlayerViewModel(app); store.put("fragment-player", vm)
                vm.player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { firstFrame.set(true) }
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended.countDown() }
                })
                assertTrue(vm.openHttp(vp.sourceUrl, referer = server.url("/source-page").toString(), audioUrl = ap.sourceUrl,
                    videoMimeType = "video/mp4", audioMimeType = "audio/mp4", videoFragments = vp, audioFragments = ap))
                vm.player.pause()
            }
            val active = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("mangalens://video/session/${vm.session.policy.sessionId}"))).also { presentation = it }
            var view: PlayerView? = null
            await(8_000L, "Actual production PlayerView did not bind the selected fragmented source") {
                active.onActivity { view = find(it.window.decorView)?.takeIf { playerView -> playerView.player === vm.player && playerView.isShown } }
                view != null
            }
            active.onActivity { vm.player.seekTo(0L); vm.player.play() }
            var advanced = false
            await(30_000L, "Native original video/audio clock never advanced after measured-byte seek") {
                active.onActivity {
                    assertNull(vm.player.playerError)
                    if (vm.player.playbackState == Player.STATE_READY && vm.player.currentPosition > 1_000) advanced = true
                }
                advanced
            }
            active.onActivity { vm.player.seekTo(3_000L); vm.player.play() }
            var afterSeek = false
            await(30_000L, "The actual fragmented playback seek did not advance") {
                active.onActivity { assertNull(vm.player.playerError); afterSeek = vm.player.currentPosition > 3_000L }
                afterSeek
            }
            assertTrue("Selected original playback never reached its full tail", ended.await(30, TimeUnit.SECONDS))
            assertTrue("Original encoded video produced no actual decoded frame", firstFrame.get())
            var nonuniform = false
            await(30_000L, "No nonuniform decoded native video frame was visible") {
                active.onActivity {
                    assertNull(vm.player.playerError)
                    (view?.videoSurfaceView as? TextureView)?.takeIf { it.isAvailable }?.getBitmap(320, 180)?.let { bitmap ->
                        try {
                            val values = (0 until 180 step 8).flatMap { y -> (0 until 320 step 8).map { x -> bitmap.getPixel(x, y).let { Color.red(it) + Color.green(it) + Color.blue(it) } } }
                            nonuniform = values.max() - values.min() > 60
                        } finally { bitmap.recycle() }
                    }
                }; nonuniform
            }
            active.onActivity {
                assertEquals(vp.sourceUrl, vm.player.currentMediaItem?.localConfiguration?.uri?.toString())
                assertEquals(720, vm.player.videoSize.height); assertEquals(MimeTypes.VIDEO_H264, vm.player.videoFormat?.sampleMimeType)
                assertEquals(MimeTypes.AUDIO_AAC, vm.player.audioFormat?.sampleMimeType)
                assertTrue(vm.player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected })
                assertTrue(vm.player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected })
                assertTrue(kotlin.math.abs(vm.player.duration - 8_000L) <= 1_000L)
            }
            val requested = (0 until server.requestCount).map { server.takeRequest().requestUrl?.encodedPath }
            assertTrue(parts.keys.all { it in requested }); assertFalse(requested.any { it?.endsWith("-anchor") == true })
        } finally {
            try { presentation?.close() } finally { foreground?.close() }
            instrumentation.runOnMainSync { store.clear() }; server.shutdown()
        }
    }
    private fun await(ms: Long, message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        while (!condition()) { if (SystemClock.elapsedRealtime() >= end) fail(message); SystemClock.sleep(100) }
    }
    private fun find(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
        return null
    }
}
