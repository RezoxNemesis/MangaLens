package com.mangalens.ui.video

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.media.session.MediaController
import android.media.session.MediaSession
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mangalens.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/** Real Android service/session/Activity controls. Tone fixtures prove playback ownership, not ASR quality. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PlayerLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as Application
    private val device get() = UiDevice.getInstance(instrumentation)

    @Before fun allowNotificationsForThisControlledMediaServiceFixture() {
        if (android.os.Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun actualForegroundServiceOwnsPlaybackAndMediaControlsAfterScreenClose() = withTonePlayer { scenario, vm, _, store ->
        val player = vm.player
        onMain { player.setPlaybackSpeed(1.5f); assertTrue(vm.requestBackground(true)) }
        await { onMainValue { vm.lifecycle.value.backgroundActive } }
        scenario.moveToState(Lifecycle.State.CREATED)
        onMain { assertSame(player, vm.player); assertTrue(player.playWhenReady); assertEquals(1.5f, player.playbackParameters.speed) }
        val connection = OwnedConnection()
        var bound = false
        try {
            assertTrue(context.bindService(Intent(context, VideoPlaybackService::class.java), connection, Context.BIND_AUTO_CREATE))
            bound = true
            assertTrue("No actual media-session binder", connection.ready.await(5, TimeUnit.SECONDS))
            val controller = MediaController(context, requireNotNull(connection.token))
            // The started foreground service owns the session. A test-only binding
            // must not keep the stopped service alive while asserting its release.
            context.unbindService(connection); bound = false
            controller.transportControls.pause()
            await { onMainValue { !player.playWhenReady } }
            controller.transportControls.seekTo(6000)
            await { onMainValue { player.currentPosition in 5800..6500 } }
            onMain { vm.detachPresentation(); store.clear() }
            assertNotNull(onMainValue { PlaybackSessions.find(vm.session.policy.sessionId) })
            controller.transportControls.play()
            await { onMainValue { player.playWhenReady } }
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
                .singleOrNull { it.id == VideoPlaybackService.NOTIFICATION_ID }
            assertNotNull("Foreground media notification missing", notification)
            assertEquals("MangaLens video", notification!!.notification.extras.getString("android.title"))
            controller.transportControls.stop()
            await { onMainValue { PlaybackSessions.find(vm.session.policy.sessionId) == null } }
        } finally { if (bound) context.unbindService(connection) }
    }

    @Test fun oldNotificationAndLateServiceShutdownCannotAffectNewSourceAndRequest() = withTonePlayer { _, vm, first, _ ->
        onMain { assertTrue(vm.requestBackground(true)) }
        await { onMainValue { vm.lifecycle.value.backgroundActive } }
        val old = onMainValue { vm.session.policy.controlReceipt() }
        val firstEpoch = onMainValue { vm.session.policy.backgroundEpoch }
        val oldPause = VideoPlaybackService.commandIntent(context, old, VideoPlaybackService.ACTION_PAUSE, firstEpoch)
        val second = File(first.parentFile, "${UUID.randomUUID()}.wav").apply { first.copyTo(this) }
        try {
            onMain {
                vm.requestBackground(false); vm.requestBackground(true)
                val newEpoch = vm.session.policy.backgroundEpoch
                assertTrue(newEpoch > firstEpoch)
                vm.session.serviceStopped(old.sessionId, firstEpoch)
                assertTrue(vm.session.policy.backgroundRequested)
                vm.open(Uri.fromFile(second)); vm.player.play()
            }
            await { onMainValue { vm.lifecycle.value.backgroundActive } }
            oldPause.send()
            instrumentation.waitForIdleSync()
            onMain {
                assertTrue("Old notification paused the replacement", vm.player.playWhenReady)
                assertEquals(Uri.fromFile(second), vm.player.currentMediaItem?.localConfiguration?.uri)
                assertTrue(vm.sourceRevision > old.sourceRevision)
            }
        } finally { second.delete() }
    }

    @Test fun productionOpaqueResumeRouteOrientationAndRecreationKeepTheSamePlayer() = withTonePlayer { _, vm, _, _ ->
        val original = vm.player
        val revision = vm.sourceRevision
        onMain { original.setPlaybackSpeed(1.25f); original.seekTo(5000); original.pause() }
        val route = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("mangalens://video/session/${vm.session.policy.sessionId}"))
        ActivityScenario.launch<MainActivity>(route).use { resumed ->
            val tools = device.wait(Until.findObject(By.text("Tools")), 8000)
            assertNotNull("Production local player was not opened", tools); tools!!.click()
            val landscape = device.wait(Until.findObject(By.textContains("Landscape")), 8000)
            assertNotNull("Production orientation control missing", landscape); landscape!!.click()
            resumed.onActivity { activity ->
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
                assertSame(original, PlaybackSessions.find(vm.session.policy.sessionId)!!.player)
                assertEquals(revision, vm.sourceRevision)
            }
            resumed.recreate()
            instrumentation.waitForIdleSync()
            resumed.onActivity { activity ->
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
                assertSame(original, PlaybackSessions.find(vm.session.policy.sessionId)!!.player)
                assertEquals(1.25f, original.playbackParameters.speed)
                assertFalse(original.playWhenReady)
                assertEquals(revision, vm.sourceRevision)
                assertTrue(original.currentPosition in 4800..5500)
            }
        }
    }

    @Test fun realVideoPipAndItsAndroidActionRetainSourceRevisionSpeedAndSeek() {
        val raw = requireNotNull(InstrumentationRegistry.getArguments().getString("sample_video")) {
            "Stage the existing controlled sample_video for actual video PiP; no missing-fixture pass is permitted."
        }
        val file = File(raw).canonicalFile
        require(file.isFile && file.length() > 0 && file.toPath().startsWith(context.filesDir.canonicalFile.toPath()))
        val store = ViewModelStore()
        lateinit var vm: LocalVideoPlayerViewModel
        var renderedFrame = false
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            onMain {
                vm = LocalVideoPlayerViewModel(app); store.put("pip", vm)
                vm.player.addListener(object : Player.Listener { override fun onRenderedFirstFrame() { renderedFrame = true } })
                vm.open(Uri.fromFile(file)); vm.player.volume = 0f
            }
            val route = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("mangalens://video/session/${vm.session.policy.sessionId}"))
            val active = ActivityScenario.launch<MainActivity>(route).also { scenario = it }
            assertNotNull("PiP must contain the production player", device.wait(Until.findObject(By.text("Tools")), 8000))
            await { onMainValue { renderedFrame && vm.player.playbackState == Player.STATE_READY && vm.player.videoSize.width > 0 } }
            val revision = vm.sourceRevision
            onMain { vm.player.setPlaybackSpeed(1.25f); vm.player.seekTo(3000) }
            active.onActivity { assertTrue("Actual Android PiP entry denied", it.playbackWindow.enterPictureInPicture()) }
            await { onMainValue { vm.lifecycle.value.inPictureInPicture } }
            active.onActivity { assertTrue(it.isInPictureInPictureMode) }
            val receipt = onMainValue { vm.session.policy.controlReceipt() }
            PlaybackControlReceiver.pendingIntent(context, receipt, VideoPlaybackService.ACTION_PAUSE).send()
            await { onMainValue { !vm.player.playWhenReady } }
            onMain {
                assertEquals(revision, vm.sourceRevision)
                assertEquals(1.25f, vm.player.playbackParameters.speed)
                assertTrue(vm.player.currentPosition >= 2800)
                assertEquals(file.toURI().path, vm.player.currentMediaItem?.localConfiguration?.uri?.path)
            }
        } finally { try { scenario?.close() } finally { onMain { store.clear() } } }
    }

    @Test fun actualImportedNativeCueKeepsPausedPositionSpeedSourceAndRejectsAnOldSelection() = withTonePlayer { _, vm, tone, _ ->
        val directory = File(context.filesDir, "captions/imported").apply { mkdirs() }
        val content = "1\n00:00:00,000 --> 00:00:10,000\nLifecycle native cue\n\n2\n00:00:11,000 --> 00:00:12,000\n${UUID.randomUUID()}\n"
        val bytes = content.toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val subtitle = File(directory, "$sha.srt").apply { writeBytes(bytes) }
        val track = PlayerCaptionTrack(Uri.fromFile(subtitle), "application/x-subrip", "en", "Lifecycle test", sha, bytes.size.toLong())
        try {
            val source = onMainValue { vm.session.sourceSnapshot() }
            val revision = vm.sourceRevision
            var cueSeen = false
            onMain {
                vm.player.pause(); vm.player.setPlaybackSpeed(1.25f); vm.player.seekTo(3000)
                vm.player.addListener(object : Player.Listener {
                    override fun onCues(group: androidx.media3.common.text.CueGroup) {
                        if (group.cues.any { it.text?.toString() == "Lifecycle native cue" }) cueSeen = true
                    }
                })
                val old = requireNotNull(vm.captureCaptionSource())
                val current = requireNotNull(vm.captureCaptionSource())
                assertFalse(vm.applyImportedCaption(old, track))
                assertTrue(vm.applyImportedCaption(current, track))
                assertEquals(revision, vm.sourceRevision)
                assertEquals(source, vm.session.sourceSnapshot())
                assertFalse(vm.player.playWhenReady)
                assertEquals(1.25f, vm.player.playbackParameters.speed)
            }
            await { onMainValue { cueSeen } }
            onMain {
                assertTrue(vm.player.currentPosition in 2800..3500)
                val ticket = requireNotNull(vm.captureCaptionSource())
                vm.open(Uri.fromFile(File(tone.parentFile, "missing-new-source.wav")))
                assertFalse(vm.captionSourceMatches(ticket))
                assertFalse(vm.applyImportedCaption(ticket, track))
            }
        } finally { subtitle.delete() }
    }

    @Test fun actualGeneratorCompletionKeepsNativeTextUntilExplicitVerifiedApplySelectsGeneratedCues() = withTonePlayer { _, vm, tone, _ ->
        val source = SubtitleMediaSource(Uri.fromFile(tone).toString())
        val identity = runBlocking { SubtitleInputs.capture(context, source) }
        val config = SubtitleInputs.config(context, vm.speech.language)
        val store = SubtitleGenerationStore.shared(context)
        val task = store.start(identity, config, ownerRequestId = "lifecycle-${UUID.randomUUID()}")
        store.running(task.id, task.generation)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var generator: FullVideoSubtitleGenerator
        var subtitle: File? = null
        try {
            onMain {
                generator = FullVideoSubtitleGenerator(context, vm.speech, scope, vm::canAttachGenerated, vm::selectGeneratedCaption)
                generator.bind(source)
            }
            await { generator.state.value.generation == task.generation }
            subtitle = managedNativeTrack()
            val bytes = subtitle.readBytes()
            val track = PlayerCaptionTrack(Uri.fromFile(subtitle), "application/x-subrip", "en", "Native fixture", sha(bytes), bytes.size.toLong())
            onMain {
                vm.player.pause(); vm.player.seekTo(1000); vm.player.setPlaybackSpeed(1.25f)
                assertTrue(vm.applyImportedCaption(requireNotNull(vm.captureCaptionSource()), track))
            }
            completeControlledTask(store, task, tone)
            await { generator.state.value.status == SubtitleGenerationStatus.COMPLETED }
            onMain {
                assertEquals(track, vm.currentCaptionTrack)
                assertTrue(vm.speech.state.value.cues.isEmpty())
                assertFalse(vm.player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT))
                generator.applyToPlayer()
            }
            await { onMainValue { vm.currentCaptionTrack == null && vm.speech.state.value.generated } }
            onMain {
                assertTrue(vm.player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT))
                assertEquals("Controlled durable speech cue.", vm.speech.state.value.cues.single().text)
                assertFalse(vm.player.playWhenReady)
                assertEquals(1.25f, vm.player.playbackParameters.speed)
                assertTrue(vm.player.currentPosition in 800..1500)
            }
        } finally { scope.cancel(); subtitle?.delete() }
    }

    @Test fun actualOldGeneratorObserverAndCancelLeaveTheNewOwnerGenerationAndFilesUntouched() = withTonePlayer { _, vm, tone, _ ->
        val source = SubtitleMediaSource(Uri.fromFile(tone).toString())
        val identity = runBlocking { SubtitleInputs.capture(context, source) }
        val config = SubtitleInputs.config(context, vm.speech.language)
        val store = SubtitleGenerationStore.shared(context)
        val old = store.start(identity, config, ownerRequestId = "lifecycle-old-${UUID.randomUUID()}")
        store.running(old.id, old.generation)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var generator: FullVideoSubtitleGenerator
        try {
            onMain {
                generator = FullVideoSubtitleGenerator(context, vm.speech, scope, vm::canAttachGenerated, vm::selectGeneratedCaption)
                generator.bind(source)
            }
            await { generator.state.value.generation == old.generation }
            val replacement = store.start(identity, config, force = true, ownerRequestId = "orez-new-${UUID.randomUUID()}")
            store.running(replacement.id, replacement.generation)
            val complete = completeControlledTask(store, replacement, tone)
            val journal = File(context.filesDir, "subtitle_jobs/${complete.id}.json")
            val savedJournal = journal.readBytes()
            onMain { generator.cancel() }
            // Drain the actual accepted command lane before inspecting native state.
            runBlocking { kotlinx.coroutines.withTimeout(15_000) { generator.awaitAcceptedCommands() } }
            assertEquals(old.generation, generator.state.value.generation)
            instrumentation.waitForIdleSync()
            assertEquals(complete, store.get(complete.id))
            assertArrayEquals(savedJournal, journal.readBytes())
            assertTrue(File(complete.srtPath!!).isFile)
            assertTrue(File(complete.vttPath!!).isFile)
            onMain { assertFalse(vm.speech.state.value.generated); assertTrue(vm.speech.state.value.cues.isEmpty()) }
        } finally { scope.cancel() }
    }

    @Test fun anOldPresentationDisposeOnTheSameViewModelDoesNotDetachTheReplacementSurface() = withTonePlayer { scenario, vm, _, _ ->
        scenario.onActivity { activity ->
            val revision = vm.sourceRevision
            val old = activity.playbackWindow.attach(vm)
            val replacement = activity.playbackWindow.attach(vm)
            vm.player.play()
            activity.playbackWindow.detach(vm, old)
            assertTrue(vm.session.policy.ownsPresentation(replacement))
            assertTrue(vm.player.playWhenReady)
            assertEquals(revision, vm.sourceRevision)
        }
    }

    @Test fun actualHttpMedia3RefreshPreservesPausedSeekSpeedHeadersAndRejectsStaleRevision() = withTonePlayer { _, vm, tone, _ ->
        val bytes = tone.readBytes()
        val requests = java.util.concurrent.ConcurrentLinkedQueue<RecordedRequest>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val range = request.getHeader("Range")
                val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                if (range != null && match == null) return MockResponse().setResponseCode(416)
                val start = match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val end = match?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull()
                    ?.coerceAtMost(bytes.size - 1L) ?: bytes.size - 1L
                if (start < 0 || start >= bytes.size || end < start)
                    return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${bytes.size}")
                val response = MockResponse().setHeader("Content-Type", "audio/wav").setHeader("Accept-Ranges", "bytes")
                    .setBody(Buffer().write(bytes, start.toInt(), (end - start + 1).toInt()))
                return if (range == null) response else response.setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
            }
        }
        try {
            server.start()
            val first = server.url("/original.wav").toString()
            val replacement = server.url("/refresh.wav").toString()
            onMain { assertTrue(vm.openHttp(first, headers = mapOf("X-Lifecycle-Fixture" to "first"))) }
            await { onMainValue { vm.player.playbackState == Player.STATE_READY && vm.player.duration >= 31_000 } }
            val capturedRevision = onMainValue { vm.sourceRevision }
            val headers = linkedMapOf("X-Lifecycle-Fixture" to "refresh")
            onMain {
                vm.player.pause(); vm.player.seekTo(7300); vm.player.setPlaybackSpeed(1.5f)
                assertTrue(vm.openHttp(replacement, headers = headers, refreshFromRevision = capturedRevision))
                headers["X-Lifecycle-Fixture"] = "caller-mutated"
                assertFalse("Source refresh resumed the user's paused player", vm.player.playWhenReady)
                assertEquals(1.5f, vm.player.playbackParameters.speed)
            }
            await { onMainValue { vm.player.playbackState == Player.STATE_READY &&
                vm.player.currentMediaItem?.localConfiguration?.uri?.toString() == replacement } }
            onMain {
                assertFalse(vm.player.playWhenReady)
                assertTrue(vm.player.currentPosition in 7000..7700)
                assertEquals(1.5f, vm.player.playbackParameters.speed)
                assertTrue(vm.sourceRevision > capturedRevision)
                val accepted = vm.session.sourceSnapshot()
                assertEquals("refresh", accepted?.headers?.get("X-Lifecycle-Fixture"))
                assertFalse(vm.openHttp(first, headers = mapOf("X-Lifecycle-Fixture" to "stale"), refreshFromRevision = capturedRevision))
                assertEquals(accepted, vm.session.sourceSnapshot())
                assertFalse(vm.player.playWhenReady)
                assertTrue(vm.player.currentPosition in 7000..7700)
                assertEquals(1.5f, vm.player.playbackParameters.speed)
            }
            assertTrue("No real refreshed HTTP source request retained its captured header",
                requests.any { it.path == "/refresh.wav" && it.getHeader("X-Lifecycle-Fixture") == "refresh" })
        } finally {
            onMain { if (!vm.session.policy.closed) vm.player.stop() }
            server.shutdown()
        }
    }

    private fun completeControlledTask(store: SubtitleGenerationStore, task: SubtitleGenerationTask, tone: File): SubtitleGenerationTask {
        // Real source bytes, full window/tail timings and durable Store/exports. Text is controlled;
        // this fixture proves presentation/control ownership and makes no speech-quality claim.
        val pcm = tone.readBytes()
        val samples = FloatArray((pcm.size - 44) / 2) { index ->
            val offset = 44 + index * 2
            ((pcm[offset].toInt() and 255) or (pcm[offset + 1].toInt() shl 8)).toShort().toFloat() / 32768f
        }
        var start = 0; var index = 0
        while (start < samples.size) {
            val end = minOf(start + 8 * 16_000, samples.size)
            val cues = if (index == 0) listOf(SpeechCue(0, 2500, "Controlled durable speech cue.")) else emptyList()
            assertTrue(store.checkpoint(task.id, task.generation,
                SubtitleWindow(index, start * 1000L / 16_000, end * 1000L / 16_000,
                    SubtitleInputs.pcmHash(samples.copyOfRange(start, end)), cues), 32_000))
            index++; if (end == samples.size) break; start += 7 * 16_000
        }
        store.completeAudio(task.id, task.generation, index)
        return requireNotNull(store.finish(task.id, task.generation))
    }
    private fun managedNativeTrack(): File {
        val bytes = "1\n00:00:00,000 --> 00:00:10,000\nLifecycle native cue\n\n2\n00:00:11,000 --> 00:00:12,000\n${UUID.randomUUID()}\n".toByteArray()
        return File(File(context.filesDir, "captions/imported").apply { mkdirs() }, "${sha(bytes)}.srt")
            .apply { writeBytes(bytes) }
    }
    private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun withTonePlayer(block: (ActivityScenario<MainActivity>, LocalVideoPlayerViewModel, File, ViewModelStore) -> Unit) {
        val file = File(context.filesDir, "lifecycle-${UUID.randomUUID()}.wav")
        val store = ViewModelStore()
        var scenario: ActivityScenario<MainActivity>? = null
        var owner: LocalVideoPlayerViewModel? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            tone(file)
            scenario.onActivity { activity ->
                val vm = LocalVideoPlayerViewModel(app); owner = vm; store.put("fixture", vm)
                vm.open(Uri.fromFile(file)); vm.player.volume = 0f
                activity.playbackWindow.attach(vm)
            }
            val vm = requireNotNull(owner)
            await { onMainValue { vm.player.playbackState == Player.STATE_READY && vm.player.duration >= 31_000 } }
            block(scenario, vm, file, store)
        } finally {
            onMain { owner?.let { if (!it.session.policy.closed) it.requestBackground(false) } }
            context.stopService(Intent(context, VideoPlaybackService::class.java))
            scenario?.close(); onMain { store.clear() }; file.delete()
        }
    }
    private inner class OwnedConnection : ServiceConnection {
        val ready = CountDownLatch(1)
        @Volatile var token: MediaSession.Token? = null
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            token = (service as VideoPlaybackService.LocalBinder).sessionToken(); ready.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) { token = null }
    }
    private fun await(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            if (android.os.SystemClock.elapsedRealtime() >= until) fail("Playback lifecycle condition did not settle in its bounded wait")
            Thread.sleep(25)
        }
    }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun <T> onMainValue(block: () -> T): T {
        var result: T? = null
        onMain { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun tone(file: File) {
        val sampleRate = 16_000; val samples = sampleRate * 32
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { bytes.putShort((kotlin.math.sin(it * 2.0 * Math.PI * 320 / sampleRate) * 1024).toInt().toShort()) }
        file.writeBytes(bytes.array())
    }
}
