package com.mangalens

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.view.LayoutInflater
import android.view.TextureView
import androidx.lifecycle.ViewModelStore
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.engine.AdvancedTranslationEngine
import com.mangalens.ui.video.LocalVideoPlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoFrameCaptureTest {
    @Test fun decodedVideoFrameCanBeCapturedAndRecognizedByLiveOcr() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val file = File(app.cacheDir, "qa-original-ocr-video.mp4")
        val fixture = instrumentation.context.assets.open("video/ocr-frame.mp4.base64").bufferedReader().use { it.readText() }
        file.writeBytes(Base64.decode(fixture, Base64.DEFAULT))
        val store = ViewModelStore()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var playerView: PlayerView? = null
        lateinit var vm: LocalVideoPlayerViewModel
        var frame: Bitmap? = null
        try {
            scenario.onActivity { activity ->
                val view = LayoutInflater.from(activity).inflate(R.layout.ocr_player_view, null) as PlayerView
                playerView = view
                activity.setContentView(view)
                vm = LocalVideoPlayerViewModel(app)
                store.put("qa-frame-player", vm)
                vm.bind(view)
                vm.player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                vm.open(Uri.fromFile(file))
            }
            withTimeout(30_000) {
                while (frame == null) {
                    instrumentation.runOnMainSync {
                        assertNull(vm.player.playerError)
                        val texture = playerView!!.videoSurfaceView as TextureView
                        if (texture.isAvailable && vm.player.videoSize.width > 0 && vm.player.currentPosition > 300) {
                            val captured = texture.getBitmap(640, 360)
                            if (captured != null) {
                                var ink = 0
                                for (y in 100 until 240 step 2) for (x in 60 until 580 step 2) {
                                    val color = captured.getPixel(x, y)
                                    if (android.graphics.Color.red(color) < 100) ink++
                                }
                                if (ink > 50 && android.graphics.Color.red(captured.getPixel(40, 40)) > 220) frame = captured
                                else captured.recycle()
                            }
                        }
                    }
                    if (frame == null) delay(100)
                }
            }
            val regions = AdvancedTranslationEngine(app).recognize(frame!!)
            assertTrue("Captured decoded frame did not contain readable lettering: $regions",
                regions.joinToString(" ") { it.source }.contains("MangaLens", true))
            File(app.getExternalFilesDir(null), "video-captured-frame.png")
                .outputStream().use { frame!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            instrumentation.runOnMainSync { playerView?.player = null; store.clear() }
            scenario.close(); frame?.recycle(); file.delete()
        }
    }
}
