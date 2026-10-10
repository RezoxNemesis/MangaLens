package com.mangalens.ui.web

import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Application IO actor calls only; failed real close retains handle and blocks further opens. */
internal class BrowserProfileJournal(
    private val file: File,
    private val openRead: (File) -> FileInputStream = { FileInputStream(it) },
    private val openWrite: (File) -> FileOutputStream = { FileOutputStream(it) },
    private val promote: (File, File) -> Unit = { from, to -> Os.rename(from.absolutePath, to.absolutePath) }
) : BrowserWorkspaceIo {
    private var unproven: AutoCloseable? = null
    @Synchronized override fun read(maxBytes: Int): ByteArray? {
        checkAvailable(); require(maxBytes in 1..BrowserWorkspaceLimits.ENCODED_BYTES)
        if (!file.exists()) return null
        return useOwned(openRead(file)) { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                if (count < 0) break
                if (output.size() > maxBytes - count) throw IOException("Profile journal exceeds its safe limit.")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    @Synchronized override fun write(bytes: ByteArray) {
        checkAvailable(); require(bytes.size <= BrowserWorkspaceLimits.ENCODED_BYTES)
        val directory = requireNotNull(file.parentFile); check(directory.mkdirs() || directory.isDirectory)
        val stage = File(directory, file.name + ".pending")
        try {
            useOwned(openWrite(stage)) { output -> output.write(bytes); output.fd.sync() }
            promote(stage, file)
        } finally { if (unproven == null) stage.delete() }
    }
    private fun checkAvailable() { if (unproven != null) throw IOException("Profile storage did not close safely. Restart the app before retrying.") }
    private fun <T : AutoCloseable, R> useOwned(value: T, body: (T) -> R): R {
        check(unproven == null); unproven = value
        try { return body(value) }
        finally {
            try { value.close(); unproven = null }
            catch (failure: Throwable) { throw IOException("Profile storage did not close safely. Restart the app before retrying.", failure) }
        }
    }
}
internal class PrivateBrowserWorkspaceIo : BrowserWorkspaceIo {
    private var bytes: ByteArray? = null
    private var retired = false
    @Synchronized override fun read(maxBytes: Int): ByteArray? { check(!retired); return bytes?.copyOf()?.also { require(it.size <= maxBytes) } }
    @Synchronized override fun write(bytes: ByteArray) { check(!retired); require(bytes.size <= BrowserWorkspaceLimits.ENCODED_BYTES); this.bytes?.fill(0); this.bytes = bytes.copyOf() }
    @Synchronized fun retire() { retired = true; bytes?.fill(0); bytes = null }
}
