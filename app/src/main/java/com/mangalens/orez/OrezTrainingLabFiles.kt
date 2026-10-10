package com.mangalens.orez

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import com.mangalens.ui.downloads.OwnedSavedVideoProbe
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import kotlinx.coroutines.withTimeout

/** One retained producer prevents blocked SAF reads/writes from spawning unbounded operations. */
internal object OrezTrainingLabFiles {
    private val producer = OwnedSavedVideoProbe()
    private const val DEADLINE_MS = 30_000L
    private fun store(context: Context) = OrezTrainingLabStore(File(context.applicationContext.filesDir, "orez/lab-evidence"))
    suspend fun list(context: Context): List<OrezLabEntry> {
        val app = context.applicationContext
        return withTimeout(DEADLINE_MS) { producer.run { owner -> store(app).list(owner::checkActive) } }
    }
    suspend fun importPackage(context: Context, uri: Uri): List<OrezLabEntry> = withTimeout(DEADLINE_MS) {
        require(uri.scheme == "content") { "Select a document provider for lab exchange" }
        val app = context.applicationContext; val signal = CancellationSignal()
        producer.run(cancelProvider = signal::cancel) { owner ->
            val descriptor = app.contentResolver.openAssetFileDescriptor(uri, "r", signal) ?: throw IOException("Lab package could not be opened")
            val handle = LabDescriptorHandle(descriptor); owner.own(handle)
            val input = handle.input(owner::checkActive)
            store(app).importPackage(input, owner::checkActive)
            owner.closeReadHandle(); store(app).list(owner::checkActive)
        }
    }
    suspend fun delete(context: Context, digest: String): List<OrezLabEntry> {
        val app = context.applicationContext
        return withTimeout(DEADLINE_MS) { producer.run { owner -> owner.checkActive(); store(app).delete(digest); store(app).list(owner::checkActive) } }
    }
    suspend fun export(context: Context, uri: Uri, digest: String) {
        val app = context.applicationContext
        write(app, uri) { check -> store(app).export(digest, check) }
    }
    suspend fun exportRequest(context: Context, uri: Uri, pin: OrezModelPin) = write(context, uri) { check ->
        check(); OrezTrainingLabRequest.create(pin)
    }
    private suspend fun write(context: Context, uri: Uri, prepare: (() -> Unit) -> ByteArray) = withTimeout(DEADLINE_MS) {
        require(uri.scheme == "content") { "Select a document provider for lab exchange" }
        val app = context.applicationContext; val signal = CancellationSignal()
        producer.run(cancelProvider = signal::cancel) { owner ->
            val bytes = prepare(owner::checkActive); owner.checkActive()
            val descriptor = app.contentResolver.openAssetFileDescriptor(uri, "wt", signal) ?: throw IOException("Lab export could not be opened")
            val handle = LabDescriptorHandle(descriptor); owner.own(handle)
            val output = handle.output(owner::checkActive)
            var offset = 0
            while (offset < bytes.size) { owner.checkActive(); val size = minOf(8192, bytes.size - offset); output.write(bytes, offset, size); offset += size }
            output.flush(); owner.closeReadHandle(); owner.checkActive()
        }
    }
}

/** Late descriptor/stream ownership and one close target follow the saved-file producer contract. */
private class LabDescriptorHandle(private val descriptor: AssetFileDescriptor) : AutoCloseable {
    private val guard = java.lang.Object()
    private var creating = false
    private var started = false
    private var closing = false
    private var stream: Closeable? = null
    private fun <T : Closeable> create(check: () -> Unit, make: () -> T): T {
        check()
        synchronized(guard) { if (closing || started) throw IOException("Lab file access retired"); started = true; creating = true }
        val value = try { make() } catch (failure: Throwable) { synchronized(guard) { creating = false; guard.notifyAll() }; throw failure }
        synchronized(guard) { stream = value; creating = false; guard.notifyAll() }
        check(); return value
    }
    fun input(check: () -> Unit): InputStream = create(check, descriptor::createInputStream)
    fun output(check: () -> Unit): OutputStream = create(check, descriptor::createOutputStream)
    override fun close() {
        val target = synchronized(guard) {
            if (closing) return
            closing = true
            while (creating) guard.wait()
            stream ?: descriptor
        }
        target.close()
    }
}
