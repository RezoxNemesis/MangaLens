package com.mangalens.ui.video

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import com.mangalens.MainActivity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real production controls, real ActivityResultRegistry restoration, and Media3 subtitle rendering.
 * Only the external document application is held by an Instrumentation monitor. Returned documents
 * use the actual app FileProvider; no picker callback, saved receipt, parser, or player is replaced.
 * Controlled tone/text fixtures prove lifecycle/ownership, not speech or translation quality.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class ImportedCaptionRecreationAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    @Before fun allowNotificationsWithoutAnotherPendingActivityResult() {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun realSrtPickerRequestSurvivesActivityRecreationAndRendersItsNativeCue() = fixture { f ->
        val cue = "Accepted picker cue ${f.id}"
        val document = f.document("picked.srt", "1\n00:00:00,000 --> 00:00:10,000\n$cue\n")
        val seen = ArrayList<String>()
        f.main {
            f.vm.player.pause(); f.vm.player.seekTo(3000); f.vm.player.setPlaybackSpeed(1.25f)
            f.vm.player.addListener(object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) { seen += cueGroup.cues.mapNotNull { it.text?.toString() } }
            })
        }
        val source = f.mainValue { f.vm.session.sourceSnapshot() }
        val revision = f.mainValue { f.vm.sourceRevision }
        f.holdRequest(Intent.ACTION_OPEN_DOCUMENT) { held ->
            f.tap("Choose SRT / VTT")
            val request = f.requestCode()
            assertTrue("The actual OPEN_DOCUMENT launch was not held", held.hits > 0)
            f.recreateControls()
            assertEquals("Recreation lost the real registry request", request, f.requestCode())
            f.deliver(request, document)
            f.await("The restored picker never attached a verified caption") {
                f.mainValue { f.vm.currentCaptionTrack != null && f.vm.player.playbackState == Player.STATE_READY }
            }
            f.await("Media3 never rendered the selected SRT's real native text cue") { f.mainValue { cue in seen } }
            val track = f.mainValue { requireNotNull(f.vm.currentCaptionTrack) }
            f.ownedCaptionFiles += File(requireNotNull(track.uri.path))
            assertTrue(runBlocking { ImportedCaptionStore(context).verify(track) })
            f.main {
                assertEquals(source, f.vm.session.sourceSnapshot())
                assertEquals(revision, f.vm.sourceRevision)
                assertFalse("Import resumed the paused player", f.vm.player.playWhenReady)
                assertEquals(1.25f, f.vm.player.playbackParameters.speed)
                assertTrue(f.vm.player.currentPosition in 2800L..3500L)
                val native = f.vm.player.currentMediaItem!!.localConfiguration!!.subtitleConfigurations.single()
                assertEquals(track.uri, native.uri)
                assertEquals("application/x-subrip", native.mimeType)
                assertFalse(f.vm.speech.state.value.generated)
                assertTrue(f.vm.speech.state.value.cues.isEmpty())
            }
        }
    }

    @Test fun pickerResultRestoredAfterASourceChangeCannotAttachToTheReplacement() = fixture { f ->
        val document = f.document("stale.srt", "1\n00:00:00,000 --> 00:00:10,000\nRejected old cue ${f.id}\n")
        f.holdRequest(Intent.ACTION_OPEN_DOCUMENT) { held ->
            f.tap("Choose SRT / VTT")
            val request = f.requestCode()
            assertTrue(held.hits > 0)
            val oldRevision = f.mainValue { f.vm.sourceRevision }
            val replacement = File(f.directory, "replacement.wav").apply { f.tone.copyTo(this) }
            f.main { f.vm.open(Uri.fromFile(replacement)); f.vm.player.pause() }
            f.await("Replacement media never became ready") { f.mainValue { f.vm.player.playbackState == Player.STATE_READY } }
            val accepted = f.mainValue { f.vm.session.sourceSnapshot() }
            val replacementRevision = f.mainValue { f.vm.sourceRevision }
            assertTrue(replacementRevision > oldRevision)
            f.recreateControls()
            assertEquals(request, f.requestCode())
            // The real registered callback runs synchronously on Main; rejected ownership
            // must not start file IO or rebuild a media item for the newer source.
            f.deliver(request, document)
            f.main {
                assertNull(f.vm.currentCaptionTrack)
                assertEquals(accepted, f.vm.session.sourceSnapshot())
                assertEquals(replacementRevision, f.vm.sourceRevision)
                assertEquals(Uri.fromFile(replacement), f.vm.player.currentMediaItem!!.localConfiguration!!.uri)
                assertTrue(f.vm.player.currentMediaItem!!.localConfiguration!!.subtitleConfigurations.isEmpty())
                assertFalse(f.vm.player.playWhenReady)
            }
            assertFalse("Registry still has an undelivered old picker request", f.hasLaunchedRequest())
        }
    }

    @Test fun realExportPickerRequestKeepsItsVerifiedBytesAcrossActivityRecreation() = fixture { f ->
        val captions = ImportedCaptionFile.parse("1\n00:00:00,000 --> 00:00:10,000\nAccepted export ${f.id}\n")
        val track = runBlocking { ImportedCaptionStore(context).publish(captions, "en", "Recreation fixture") }
        f.ownedCaptionFiles += File(requireNotNull(track.uri.path))
        f.main { assertTrue(f.vm.applyImportedCaption(requireNotNull(f.vm.captureCaptionSource()), track)) }
        val expected = runBlocking { ImportedCaptionStore(context).readExport(track) }
        val destination = File(f.directory, "exported.srt").apply { writeBytes(byteArrayOf()) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.downloads", destination)
        val source = f.mainValue { f.vm.session.sourceSnapshot() }
        val revision = f.mainValue { f.vm.sourceRevision }
        f.holdRequest(Intent.ACTION_CREATE_DOCUMENT) { held ->
            f.tap("Export SRT")
            val request = f.requestCode()
            assertTrue("The actual CREATE_DOCUMENT launch was not held", held.hits > 0)
            f.recreateControls()
            assertEquals(request, f.requestCode())
            f.deliver(request, uri)
            f.await("The restored export never wrote the complete verified caption") { destination.length() == expected.size.toLong() }
            assertArrayEquals(expected, destination.readBytes())
            assertEquals(sha(expected), sha(destination.readBytes()))
            f.main {
                assertEquals(track, f.vm.currentCaptionTrack)
                assertEquals(source, f.vm.session.sourceSnapshot())
                assertEquals(revision, f.vm.sourceRevision)
            }
        }
    }

    private fun fixture(check: (Fixture) -> Unit) {
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "downloads/caption-recreation-$id").apply { kotlin.check(mkdirs()) }
        val tone = File(directory, "tone.wav").apply { writeTone(this) }
        val store = ViewModelStore()
        var scenario: ActivityScenario<MainActivity>? = null
        var owner: LocalVideoPlayerViewModel? = null
        val files = ArrayList<File>()
        val ui = Configurator.getInstance()
        val previousIdleWait = ui.waitForIdleTimeout
        try {
            // Use the existing UI-driver idle policy; every real control/result/media
            // assertion retains its own bounded wait below.
            ui.waitForIdleTimeout = 100L
            val active = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).also { scenario = it }
            instrumentation.runOnMainSync {
                owner = LocalVideoPlayerViewModel(context.applicationContext as Application).also { vm ->
                    store.put("caption-recreation", vm); vm.open(Uri.fromFile(tone)); vm.player.volume = 0f
                }
            }
            val f = Fixture(active, requireNotNull(owner), id, directory, tone, files)
            f.renderControls()
            f.await("Real tone media failed to prepare") { f.mainValue { f.vm.player.playbackState == Player.STATE_READY && f.vm.player.duration >= 31_000 } }
            check(f)
        } finally {
            try { scenario?.close() } finally {
                instrumentation.runOnMainSync { store.clear() }
                files.distinctBy { it.absolutePath }.forEach { it.delete() }
                directory.deleteRecursively()
                ui.waitForIdleTimeout = previousIdleWait
            }
        }
    }

    private inner class Fixture(
        val scenario: ActivityScenario<MainActivity>, val vm: LocalVideoPlayerViewModel, val id: String,
        val directory: File, val tone: File, val ownedCaptionFiles: MutableList<File>
    ) {
        fun renderControls() = scenario.onActivity { activity -> activity.setContent {
            key("real-caption-recreation-controls") {
                MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    ImportedCaptionControls(vm)
                } }
            }
        } }
        fun recreateControls() { scenario.recreate(); renderControls(); tapReady("Choose SRT / VTT") }
        fun document(name: String, text: String): Uri {
            val file = File(directory, name).apply { writeText(text, Charsets.UTF_8) }
            return FileProvider.getUriForFile(context, "${context.packageName}.downloads", file)
        }
        fun holdRequest(action: String, block: (android.app.Instrumentation.ActivityMonitor) -> Unit) {
            val filter = IntentFilter(action).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") }
            val monitor = instrumentation.addMonitor(filter, null, true)
            try { block(monitor) } finally { instrumentation.removeMonitor(monitor) }
        }
        fun tapReady(text: String) {
            await("Missing enabled production caption control: $text") {
                device.findObject(By.text(text))?.let { it.isEnabled && !it.visibleBounds.isEmpty } == true
            }
        }
        fun tap(text: String) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val control = device.findObject(By.text(text))
                if (control != null && control.isEnabled && !control.visibleBounds.isEmpty) { control.click(); return }
                device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.6f)
                SystemClock.sleep(40)
            }
            fail("Missing clickable production caption control: $text")
        }
        fun requestCode(): Int {
            var code: Int? = null
            await("The real registry did not record exactly one held document request") {
                scenario.onActivity { activity ->
                    val codes = launchedCodes(activity.activityResultRegistry)
                    assertTrue("Another ActivityResult request overlaps this fixture", codes.size <= 1)
                    code = codes.singleOrNull()
                }
                code != null
            }
            return requireNotNull(code)
        }
        fun hasLaunchedRequest(): Boolean {
            var launched = false
            scenario.onActivity { launched = launchedCodes(it.activityResultRegistry).isNotEmpty() }
            return launched
        }
        fun deliver(request: Int, uri: Uri) = scenario.onActivity { activity ->
            assertTrue("No restored launch matched this platform result", activity.activityResultRegistry.dispatchResult(
                request, Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)))
        }
        fun await(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (predicate()) return
                SystemClock.sleep(40)
            }
            assertTrue(message, predicate())
        }
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun <T> mainValue(block: () -> T): T {
            var result: T? = null
            main { result = block() }
            @Suppress("UNCHECKED_CAST") return result as T
        }
    }

    private fun launchedCodes(registry: ActivityResultRegistry): List<Int> {
        // Observe the actual registry only; launch keys and state are never mutated by the fixture.
        val keys = ActivityResultRegistry::class.java.getDeclaredField("launchedKeys").apply { isAccessible = true }.get(registry) as List<*>
        val codes = ActivityResultRegistry::class.java.getDeclaredField("keyToRc").apply { isAccessible = true }.get(registry) as Map<*, *>
        return keys.map { codes[it] as Int }
    }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun writeTone(file: File) {
        val samples = 16_000 * 32
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { bytes.putShort((kotlin.math.sin(it * 2.0 * Math.PI * 320 / 16_000) * 1024).toInt().toShort()) }
        file.writeBytes(bytes.array())
    }
}
