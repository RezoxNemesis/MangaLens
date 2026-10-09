package com.mangalens

import android.content.Context
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.mangalens.core.model.ContentType
import com.mangalens.ui.MangaLensUiState
import com.mangalens.ui.MangaLensViewModel
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

/** Interacts with the actual shared-link and local-import routes; no resolver/player injection. */
@OptIn(UnstableApi::class)
internal class ProviderUiHarness(private val screenshots: File) : AutoCloseable {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val device = UiDevice.getInstance(instrumentation)
    private val configurator = Configurator.getInstance()
    private val previousIdleTimeout = configurator.waitForIdleTimeout
    private val prefs = context.getSharedPreferences("mangalens_preferences", Context.MODE_PRIVATE)
    private val previousPrefs = listOf("last_url", "translation_video").associateWith { prefs.all[it] }
    private val ownerId = UUID.randomUUID().toString()
    private val lifecycle = ProviderUiLifecycle<MainActivity>()
    private val application = context.applicationContext as Application
    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = record(activity, ProviderUiLifecycle.State.CREATED)
        override fun onActivityStarted(activity: Activity) = record(activity, ProviderUiLifecycle.State.STARTED)
        override fun onActivityResumed(activity: Activity) = record(activity, ProviderUiLifecycle.State.RESUMED)
        override fun onActivityPaused(activity: Activity) = record(activity, ProviderUiLifecycle.State.PAUSED)
        override fun onActivityStopped(activity: Activity) = record(activity, ProviderUiLifecycle.State.STOPPED)
        override fun onActivityDestroyed(activity: Activity) = record(activity, ProviderUiLifecycle.State.DESTROYED)
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
    init {
        screenshots.mkdirs()
        main { application.registerActivityLifecycleCallbacks(lifecycleCallbacks) }
        configurator.setWaitForIdleTimeout(100)
    }

    private fun record(activity: Activity, state: ProviderUiLifecycle.State) {
        if (activity !is MainActivity || activity.intent?.getStringExtra(OWNER_EXTRA) != ownerId) return
        val epoch = activity.intent.getLongExtra(EPOCH_EXTRA, 0L)
        if (epoch <= 0L) return
        if (lifecycle.observe(activity, epoch, state) && !activity.isFinishing) activity.finish()
        detachIfClosed()
    }

    private fun detachIfClosed() {
        if (lifecycle.canDetach()) application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    fun openSource(fixture: ProviderFixture) {
        prefs.edit().putBoolean("translation_video", false).commit()
        launch(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND; type = "text/plain"; putExtra(Intent.EXTRA_TEXT, fixture.source)
        })
        waitFor("Shared provider link did not enter the video route", 15_000) {
            val state = stateIfResumed()
            state?.mode == ContentType.VIDEO_STREAM && state.url == fixture.source
        }
    }

    fun importSavedVideo(uri: Uri) {
        launch(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND; type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun launch(intent: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        }
        main {
            val epoch = lifecycle.beginLaunch()
            lifecycle.retired().filterNot { it.isFinishing || it.isDestroyed }.forEach { it.finish() }
            try {
                context.startActivity(intent.apply {
                    putExtra(OWNER_EXTRA, ownerId); putExtra(EPOCH_EXTRA, epoch)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                })
            } catch (failure: Throwable) {
                lifecycle.launchFailed(epoch)
                throw failure
            }
        }
    }

    private fun stateIfResumed(): MangaLensUiState? = main {
        lifecycle.resumed()?.let { ViewModelProvider(it)[MangaLensViewModel::class.java].state.value }
    }

    fun state(): MangaLensUiState {
        var result: MangaLensUiState? = null
        waitFor("Owned provider activity did not resume") { stateIfResumed().also { result = it } != null }
        return requireNotNull(result)
    }

    fun awaitResolved(): MangaLensUiState {
        waitFor("The 45-second provider video route did not leave loading", 50_000) { !state().loading }
        return state()
    }

    fun node(selector: BySelector, timeoutMs: Long = 8_000): UiObject2 =
        device.wait(Until.findObject(selector), timeoutMs) ?: throw AssertionError("Provider recovery control was not visible")

    fun tap(label: String) { node(By.text(label).pkg(context.packageName)).click() }

    fun waitFor(message: String, timeoutMs: Long = 8_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        check(predicate()) { message }
    }

    fun capture(label: String) {
        check(label.matches(Regex("[a-z0-9-]+")))
        check(device.takeScreenshot(File(screenshots, "$label.png"))) { "Provider screenshot was not captured" }
        // No hierarchy export: app nodes can contain provider responses or signed media values.
    }

    fun assertSourcePageOpened(fixture: ProviderFixture) {
        waitFor("Open source did not cancel ingestion and select the original Web page") {
            val state = state()
            !state.loading && state.mode == ContentType.GENERIC_WEB && state.videoPageUrl == fixture.source
        }
        node(By.clazz("android.webkit.WebView").pkg(context.packageName))
        waitFor("Source WebView did not receive the original supplied provider page") {
            main {
                val view = findWebView(activity().window.decorView) ?: return@main false
                val original = view.originalUrl ?: view.url ?: return@main false
                if (original == fixture.source) return@main true
                val uri = Uri.parse(original)
                val host = uri.host?.lowercase().orEmpty()
                when (fixture) {
                    ProviderFixture.YOUTUBE ->
                        (host == "youtu.be" && uri.path == "/${fixture.sourceId}") ||
                            ((host == "youtube.com" || host.endsWith(".youtube.com")) &&
                                uri.path == "/watch" && uri.getQueryParameter("v") == fixture.sourceId)
                    ProviderFixture.INSTAGRAM ->
                        (host == "instagram.com" || host.endsWith(".instagram.com")) &&
                            uri.path?.trimEnd('/') == "/reel/${fixture.sourceId}"
                }
            }
        }
    }

    fun assertBackReturnedHome(timeoutMs: Long = 8_000) {
        waitFor("Back did not return to the actual Home screen", timeoutMs) {
            main { lifecycle.resumed() != null } &&
                device.hasObject(By.text("READ · WATCH · BROWSE").pkg(context.packageName)) &&
                !device.hasObject(By.text("My Library").pkg(context.packageName)) &&
                !device.hasObject(By.text("Cancel detection").pkg(context.packageName)) &&
                !device.hasObject(By.text("Retry detection").pkg(context.packageName))
        }
    }

    fun verifyHomeOracleRejectsLibrary() {
        openHome()
        node(By.desc("Library").pkg(context.packageName)).click()
        node(By.text("My Library").pkg(context.packageName))
        check(device.hasObject(By.desc("Home").pkg(context.packageName))) { "Negative Home oracle was not on a bottom-nav route" }
        check(runCatching { assertBackReturnedHome(500) }.isFailure) { "Home oracle accepted the Library route" }
        node(By.desc("Home").pkg(context.packageName)).click()
        assertBackReturnedHome()
    }

    internal fun openHome() {
        launch(Intent(context, MainActivity::class.java))
        assertBackReturnedHome()
    }

    internal fun recreateOwnedActivity() {
        val previous = main { activity().also { it.recreate() } }
        waitFor("Owned provider activity recreation did not resume") {
            main { lifecycle.resumed()?.let { it !== previous } == true }
        }
        assertBackReturnedHome()
    }

    internal fun ownedActivity(): MainActivity = main { activity() }
    internal fun cleanupComplete(): Boolean = lifecycle.canDetach()

    fun probeActualPlayer(): JSONObject {
        waitFor("Actual native player view was not attached", 15_000) {
            main { lifecycle.resumed()?.let { findPlayerView(it.window.decorView) } != null }
        }
        main { player().seekTo(0); player().play() }
        waitFor("Provider media did not render video and decoded audio", 45_000) {
            val current = snapshot()
            !current.has("error_code") && current.optInt("video_output_buffers") > 0 && current.optInt("audio_output_buffers") > 0 &&
                (current.optLong("position_ms") > 300 || current.optInt("playback_state") == Player.STATE_ENDED)
        }
        val start = snapshot()
        main { player().pause() }
        val pausedAt = snapshot().getLong("position_ms")
        SystemClock.sleep(350)
        check(snapshot().getLong("position_ms") <= pausedAt + 200) { "Native pause did not stop provider playback" }
        main { player().play() }
        waitFor("Native resume did not advance actual media") {
            val current = snapshot(); current.optLong("position_ms") > pausedAt + 200 || current.optInt("playback_state") == Player.STATE_ENDED
        }
        val duration = snapshot().getLong("duration_ms")
        check(duration > 0) { "Native provider playback duration is unknown" }
        val middle = if (duration > 4_000) duration / 2 else 0L
        if (middle > 0) {
            main { player().seekTo(middle) }
            waitFor("Native seek did not reach the middle of the provider media", 30_000) {
                val current = snapshot(); current.optLong("position_ms") >= middle - 500 &&
                    current.optInt("playback_state") == Player.STATE_READY
            }
        }
        main { player().seekTo((duration - 1_500L).coerceAtLeast(0L)); player().play() }
        waitFor("Native provider media did not decode and finish its tail", 30_000) {
            val current = snapshot()
            check(!current.has("error_code")) { "Native player failed (${current.optString("error_code")})" }
            current.optInt("playback_state") == Player.STATE_ENDED
        }
        val end = snapshot()
        main { player().pause() }
        return JSONObject().put("head", start).put("tail", end).put("pause_resume_verified", true)
            .put("seek_middle_ms", middle).put("control_probe", "actual production player instance API")
    }

    private fun snapshot(): JSONObject = main {
        val player = player()
        val video = player.videoDecoderCounters?.apply { ensureUpdated() }
        val audio = player.audioDecoderCounters?.apply { ensureUpdated() }
        JSONObject().put("position_ms", player.currentPosition).put("duration_ms", player.duration)
            .put("width", player.videoSize.width).put("height", player.videoSize.height)
            .put("playback_state", player.playbackState).put("is_playing", player.isPlaying)
            .put("video_output_buffers", video?.renderedOutputBufferCount ?: 0)
            .put("audio_output_buffers", audio?.renderedOutputBufferCount ?: 0)
            .also { result -> player.playerError?.let { result.put("error_code", it.errorCodeName) } }
    }

    private fun activity(): MainActivity = requireNotNull(lifecycle.resumed()) { "Owned provider activity is not resumed" }

    private fun player(): ExoPlayer = findPlayerView(activity().window.decorView)?.player as? ExoPlayer
        ?: throw AssertionError("Production ExoPlayer is unavailable")

    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView && view.isShown && view.player != null) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findPlayerView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView && view.isShown) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun <T> main(block: () -> T): T {
        val enabled = AtomicBoolean(true)
        val result = AtomicReference<Result<T>?>()
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            if (enabled.compareAndSet(true, false)) result.set(runCatching(block))
            done.countDown()
        }
        if (!done.await(5, TimeUnit.SECONDS)) {
            enabled.set(false)
            throw AssertionError("Android main thread did not answer the bounded provider probe")
        }
        return requireNotNull(result.get()).getOrThrow()
    }

    override fun close() {
        // Publish the close fence even when Main is busy; accepted late creation is still owned.
        val finishing = lifecycle.close()
        try {
            Handler(Looper.getMainLooper()).post {
                finishing.filterNot { it.isFinishing || it.isDestroyed }.forEach { it.finish() }
                detachIfClosed()
            }
            main { }
        } finally {
            val edit = prefs.edit()
            previousPrefs.forEach { (key, value) -> when (value) {
                null -> edit.remove(key)
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                else -> throw AssertionError("Unexpected QA preference type")
            } }
            edit.commit()
            configurator.setWaitForIdleTimeout(previousIdleTimeout)
        }
    }

    private companion object {
        const val OWNER_EXTRA = "com.mangalens.qa.PROVIDER_UI_OWNER"
        const val EPOCH_EXTRA = "com.mangalens.qa.PROVIDER_UI_EPOCH"
    }
}
