package com.mangalens.orez

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.SerialExecutorImpl
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Executes CoroutineWorker.startWork/cancellation and its production transfer without sockets. */
@RunWith(AndroidJUnit4::class)
@SuppressLint("RestrictedApi")
class OrezModelTransferWorkerLifecycleTest {
    @Test fun heldWorkerPauseResumePreservesOnePartialAndResumedIdentity() {
        val context = IsolatedContext()
        val descriptor = OrezModelCatalog.lite
        val part = File(context.filesDir, "orez_models/" + descriptor.fileName + ".part").apply {
            parentFile!!.mkdirs()
            writeText("AB")
        }
        val kept = File(part.parentFile, "saved-model.gguf").apply { writeText("saved model bytes") }
        val held = HeldInput()
        val oldConnection = FakeConnection(held, 2, descriptor.bytes)
        val resumedOpened = CountDownLatch(1)
        val controls = OrezModelTransferPreferences(context)
        val oldId = UUID.randomUUID()
        assertTrue(controls.prepare(oldId.toString(), OrezModelTier.LITE, 2, descriptor.bytes))
        val old = worker(context, oldId) { oldConnection }.startWork()
        var resumed: com.google.common.util.concurrent.ListenableFuture<ListenableWorker.Result>? = null
        try {
            assertTrue("worker did not reach the held HTTP read", held.entered.await(10, TimeUnit.SECONDS))
            controls.pause()
            old.cancel(true)
            val newId = UUID.randomUUID()
            assertTrue(OrezModelTransferPreferences(context).prepare(newId.toString(), OrezModelTier.LITE, part.length(), descriptor.bytes))
            resumed = worker(context, newId) {
                resumedOpened.countDown()
                FakeConnection(ByteArrayInputStream("D".toByteArray()), part.length(), descriptor.bytes)
            }.startWork()
            assertTrue("cancel did not disconnect the blocked HTTP connection", oldConnection.disconnected.await(5, TimeUnit.SECONDS))
            assertFalse("resumed worker opened a still-owned partial", resumedOpened.await(200, TimeUnit.MILLISECONDS))
            held.release.countDown()
            assertEquals(ListenableWorker.Result.retry(), resumed.get(20, TimeUnit.SECONDS))
            assertTrue(held.closed)
            assertEquals("ABD", part.readText())
            assertEquals("saved model bytes", kept.readText())
            val prefs = context.getSharedPreferences("orez_model", Context.MODE_PRIVATE)
            assertEquals(newId.toString(), prefs.getString("transfer_id", null))
            assertTrue("older cancellation cleared resumed progress", prefs.getBoolean("downloading", false))
            assertEquals(3L, prefs.getLong("bytes", -1))
            assertTrue(prefs.getString("error", "")!!.contains("stopped early"))
        } finally {
            held.release.countDown()
            old.cancel(true)
            resumed?.cancel(true)
            context.cleanup()
        }
    }

    @Test fun pausedIdentityCannotBeReadoptedByReconstructedWorkerOrStaleWorkObserver() {
        val context = IsolatedContext()
        val descriptor = OrezModelCatalog.lite
        val oldId = UUID.randomUUID()
        val controls = OrezModelTransferPreferences(context)
        assertTrue(controls.prepare(oldId.toString(), OrezModelTier.LITE, 0, descriptor.bytes))
        controls.pause()
        try {
            val reloaded = OrezModelTransferPreferences(context)
            reloaded.synchronize(listOf(androidx.work.WorkInfo(oldId, androidx.work.WorkInfo.State.RUNNING, emptySet())))
            val result = worker(context, oldId) { throw AssertionError("paused request opened a connection") }
                .startWork().get(10, TimeUnit.SECONDS)
            assertEquals(ListenableWorker.Result.failure(workDataOf(OrezModelDownloadWorker.KEY_ERROR to "Model transfer was paused or replaced.")), result)
            assertFalse(context.getSharedPreferences("orez_model", Context.MODE_PRIVATE).getBoolean("downloading", true))
        } finally { context.cleanup() }
    }

    @Test fun journalSurvivesOldPreferencesAndPendingWorkGapWithoutRevivingPersistedPause() {
        val context = IsolatedContext()
        val restarted = IsolatedContext()
        val pausedRestart = IsolatedContext()
        val descriptor = OrezModelCatalog.lite
        val oldId = UUID.randomUUID()
        val newId = UUID.randomUUID()
        try {
            val controls = OrezModelTransferPreferences(context)
            assertTrue(controls.prepare(oldId.toString(), OrezModelTier.LITE, 2, descriptor.bytes))
            controls.pause()
            assertTrue(controls.prepare(newId.toString(), OrezModelTier.LITE, 2, descriptor.bytes))
            // Rebuild a process image with durable G2 controls but an older G1 prefs snapshot.
            copyJournal(context, restarted)
            restarted.getSharedPreferences("orez_model", Context.MODE_PRIVATE).edit()
                .putString("transfer_id", oldId.toString()).putString("transfer_work_id", oldId.toString())
                .putBoolean("transfer_active", true).putBoolean("downloading", true).commit()
            val restored = OrezModelTransferPreferences(restarted)
            assertTrue(restored.claim(newId.toString(), newId.toString(), OrezModelTier.LITE))
            assertFalse(restored.claim(oldId.toString(), oldId.toString(), OrezModelTier.LITE))
            val pending = restored.synchronize(emptyList())
            assertEquals("accepted control with no WorkSpec must retain the exact replay ID", newId.toString(), pending!!.id)
            assertTrue(pending.pendingEnqueue)
            restored.synchronize(listOf(androidx.work.WorkInfo(newId, androidx.work.WorkInfo.State.RUNNING, emptySet())))
            restored.pause()
            copyJournal(restarted, pausedRestart)
            pausedRestart.getSharedPreferences("orez_model", Context.MODE_PRIVATE).edit()
                .putString("transfer_id", newId.toString()).putString("transfer_work_id", newId.toString())
                .putBoolean("transfer_active", true).putBoolean("downloading", true).commit()
            val inactive = OrezModelTransferPreferences(pausedRestart)
            assertNull(inactive.synchronize(listOf(androidx.work.WorkInfo(newId, androidx.work.WorkInfo.State.RUNNING, emptySet()))))
            assertFalse(inactive.claim(newId.toString(), newId.toString(), OrezModelTier.LITE))
            assertFalse(pausedRestart.getSharedPreferences("orez_model", Context.MODE_PRIVATE).getBoolean("downloading", true))
        } finally {
            context.cleanup(); restarted.cleanup(); pausedRestart.cleanup()
        }
    }

    @Test fun queuedLatestPauseSurvivesOlderPauseAndResumeAcknowledgements() {
        val context = IsolatedContext()
        val descriptor = OrezModelCatalog.lite
        val oldId = UUID.randomUUID().toString()
        val resumedId = UUID.randomUUID().toString()
        try {
            val controls = OrezModelTransferPreferences(context)
            assertTrue(controls.prepare(oldId, OrezModelTier.LITE, 2, descriptor.bytes))
            val firstPause = controls.requestPause()
            val latestPause = controls.requestPause()
            // Emulate the held IO queue: Pause1 then Resume2 acknowledge after Pause3 was clicked.
            controls.pause(firstPause)
            assertTrue(controls.prepare(resumedId, OrezModelTier.LITE, 2, descriptor.bytes))
            assertFalse("new-ID commit released a later Pause3", controls.claim(resumedId, resumedId, OrezModelTier.LITE))
            assertFalse("older enqueue completion released Pause3", controls.enqueued(resumedId))
            try { controls.checkpoint(resumedId); fail("later Pause must fence a worker read") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            controls.pause(latestPause)
            assertFalse(controls.claim(resumedId, resumedId, OrezModelTier.LITE))
            assertFalse(context.getSharedPreferences("orez_model", Context.MODE_PRIVATE).getBoolean("downloading", true))
        } finally { context.cleanup() }
    }

    private fun copyJournal(from: Context, to: Context) {
        File(from.filesDir, "orez_models/transfer.control").copyTo(
            File(to.filesDir, "orez_models/transfer.control").apply { parentFile!!.mkdirs() }, overwrite = true)
    }

    private fun worker(context: Context, id: UUID, connection: (URL) -> HttpURLConnection): OrezModelDownloadWorker {
        val executor = Executor { it.run() }
        val taskExecutor = object : TaskExecutor {
            private val serial = SerialExecutorImpl(executor)
            override fun getMainThreadExecutor() = executor
            override fun getSerialTaskExecutor() = serial
        }
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        }
        val progress = ProgressUpdater { _, _, _ -> SettableFuture.create<Void>().apply { set(null) } }
        val foreground = ForegroundUpdater { _, _, _ -> SettableFuture.create<Void>().apply { set(null) } }
        val input: Data = workDataOf(OrezModelDownloadWorker.KEY_ID to id.toString(), OrezModelDownloadWorker.KEY_TIER to OrezModelTier.LITE.name)
        return OrezModelDownloadWorker(context, WorkerParameters(id, input, emptySet(), WorkerParameters.RuntimeExtras(),
            0, 0, executor, Dispatchers.IO, taskExecutor, factory, progress, foreground), connection)
    }

    private class HeldInput : InputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var closed = false
        private var emitted = false
        override fun read(): Int = error("bulk reads only")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (emitted) return -1
            entered.countDown()
            check(release.await(20, TimeUnit.SECONDS)) { "test did not release held read" }
            emitted = true
            buffer[offset] = 'C'.code.toByte()
            return 1
        }
        override fun close() { closed = true }
    }

    private class FakeConnection(private val stream: InputStream, private val offset: Long, private val total: Long) :
        HttpURLConnection(URL("https://fixture.invalid/model")) {
        val disconnected = CountDownLatch(1)
        override fun connect() { connected = true }
        // Deliberately emulate a native read that has not returned despite disconnect.
        override fun disconnect() { disconnected.countDown() }
        override fun usingProxy() = false
        override fun getResponseCode() = HTTP_PARTIAL
        override fun getContentLengthLong() = total - offset
        override fun getHeaderField(name: String?): String? = if (name == "Content-Range") "bytes $offset-${total - 1}/$total" else null
        override fun getInputStream() = stream
    }

    private class IsolatedContext : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        private val prefix = "model-transfer-" + UUID.randomUUID()
        private val directory = File(baseContext.cacheDir, prefix).apply { mkdirs() }
        override fun getFilesDir() = directory
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(prefix + "-" + name, mode)
        fun cleanup() {
            getSharedPreferences("orez_model", Context.MODE_PRIVATE).edit().clear().commit()
            directory.deleteRecursively()
        }
    }
}
