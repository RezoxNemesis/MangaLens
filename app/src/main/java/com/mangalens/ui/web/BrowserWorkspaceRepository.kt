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
import kotlinx.coroutines.launch

internal object BrowserWorkspaceRepository {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var shared: BrowserWorkspaceSession? = null

    fun session(context: Context): BrowserWorkspaceSession = shared ?: synchronized(this) {
        shared ?: BrowserWorkspaceSession(AtomicBrowserWorkspaceIo(File(context.applicationContext.filesDir, "browser_workspace/session.json")),
            applicationScope).also { shared = it }
    }
    private val profiles = mutableMapOf<String, BrowserWorkspaceSession>()
    private val privateIo = mutableMapOf<String, PrivateBrowserWorkspaceIo>()
    fun profileSession(context: Context, choice: BrowserProfileChoice): BrowserWorkspaceSession {
        choice.validate()
        if (choice.kind == BrowserProfileKind.NORMAL) return session(context)
        val app = context.applicationContext
        return synchronized(this) {
            profiles[choice.key] ?: run {
                check(profiles.size < BrowserProfilePolicy.MAX_CUSTOM + 2) { "Profile session capacity reached." }
                val io = if (choice.ephemeral) PrivateBrowserWorkspaceIo().also { privateIo[choice.key] = it }
                    else BrowserProfileJournal(File(app.filesDir, BrowserProfilePolicy.workspaceDirectory(choice) + "/session.json"))
                BrowserWorkspaceSession(io, applicationScope, choice.key, choice.ephemeral).also { profiles[choice.key] = it }
            }
        }
    }
    fun retirePrivate(choice: BrowserProfileChoice) {
        if (!choice.ephemeral) return
        val protectionRetired = BrowserProtectionRepository.retirePrivate(choice)
        val retired = synchronized(this) { profiles.remove(choice.key) to privateIo.remove(choice.key) }
        if (retired.first == null && retired.second == null && protectionRetired == null) return
        BrowserProfileRuntime.memoryClosing(choice)
        retired.first?.retireEphemeral()
        // Private IO is memory-only. Clear after the actual accepted actor returns, entirely on IO.
        applicationScope.launch {
            retired.first?.awaitRetired(); protectionRetired?.awaitRetired(); retired.second?.retire()
            BrowserProfileRuntime.memoryClosed(choice)
        }
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
