package com.mangalens.ui.web

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal object BrowserWorkspaceRepository {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var shared: BrowserWorkspaceSession? = null

    fun session(context: Context): BrowserWorkspaceSession = shared ?: synchronized(this) {
        shared ?: BrowserWorkspaceSession(AtomicBrowserWorkspaceIo(File(context.applicationContext.filesDir, "browser_workspace/session.json")),
            applicationScope).also { shared = it }
    }
}

internal class AtomicBrowserWorkspaceIo(private val file: File) : BrowserWorkspaceIo {
    override fun read(maxBytes: Int): ByteArray? {
        val atomic = AtomicFile(file)
        val stream = try { atomic.openRead() }
        catch (missing: FileNotFoundException) {
            if (!file.exists() && !File(file.path + ".bak").exists()) return null
            throw missing
        }
        return stream.use { input ->
            if (input.channel.size() > maxBytes) return@use ByteArray(0)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16_384)
            while (output.size() <= maxBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                if (count < 0) break
                if (count > 0) output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    override fun write(bytes: ByteArray) {
        if (file.parentFile?.let { it.mkdirs() || it.isDirectory } != true) throw IOException("Browser directory unavailable")
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }
}
