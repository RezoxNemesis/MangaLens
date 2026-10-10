package com.mangalens.download

import android.content.Context
import android.os.Build
import java.io.File
import java.util.UUID

internal enum class NativeMediaTool(val fileName: String) { FFMPEG("libmangalens_ffmpeg.so"), FFPROBE("libmangalens_ffprobe.so") }
internal data class NativeOriginalMediaInstallation(val binaryDirectory: File, val libraryDirectories: List<File>)

/** Only app-owned checksum-pinned APK executables and Android system dependencies. */
internal object NativeOriginalMediaRuntime {
    private var installed: Pair<String, NativeOriginalMediaInstallation>? = null

    @Synchronized
    fun initialize(context: Context, session: MediaResolutionSession): NativeOriginalMediaInstallation {
        session.checkActive()
        val binaryDirectory = File(context.applicationInfo.nativeLibraryDir)
        installed?.takeIf { it.first == binaryDirectory.absolutePath }?.let { return it.second }
        val pins = context.assets.open(PackagedOriginalMediaAdmission.ASSET).use {
            PackagedOriginalMediaAdmission.readPins(it, session::checkActive)
        }
        PackagedOriginalMediaAdmission.admit(binaryDirectory, pins, Build.SUPPORTED_ABIS.toList(), session::checkActive)
        session.checkActive()
        return NativeOriginalMediaInstallation(binaryDirectory, emptyList())
            .also { installed = binaryDirectory.absolutePath to it }
    }

    fun execute(installation: NativeOriginalMediaInstallation, tool: NativeMediaTool, arguments: List<String>,
                session: MediaResolutionSession, consumeLine: (String) -> Unit = {}) = executeWithLifecycle(
        installation, tool, arguments, session, ProcessBuilder::start,
        { process -> process?.destroyForcibly(); true }, consumeLine)

    /** Injectable platform operations make the late-launch ownership race deterministic in tests. */
    internal fun executeWithLifecycle(installation: NativeOriginalMediaInstallation, tool: NativeMediaTool,
                                     arguments: List<String>, session: MediaResolutionSession,
                                     launch: (ProcessBuilder) -> Process, stop: (Process?) -> Boolean,
                                     consumeLine: (String) -> Unit = {}) {
        session.checkActive()
        val budgetBeforeStart = session.remainingMillis()
        val builder = ProcessBuilder(listOf(File(installation.binaryDirectory, tool.fileName).absolutePath) + arguments)
            .redirectError(File("/dev/null"))
        PackagedOriginalMediaEnvironment.configure(builder.environment())
        val running = launch(builder)
        // start() may return after cancellation. Bind cleanup to the returned process
        // before checking activity, so an earlier null cleanup cannot consume its stop.
        var guard: MediaProcessGuard? = null
        val resources = OwnedOriginalMediaProcessResources(running, session::retainPrivateFiles)
        try {
            guard = MediaProcessGuard(session, "original-media-${UUID.randomUUID()}", budgetBeforeStart) { stop(running) }
            session.checkActive()
            resources.closeInput()
            val reader = resources.reader()
            while (true) {
                session.checkActive()
                val line = reader.readLine() ?: break
                consumeLine(line)
            }
            resources.closeOutput()
            check(running.waitFor() == 0) {
                "Original media processing failed. No lower-quality source was substituted."
            }
            session.checkActive()
        } finally {
            if (guard != null) guard.close()
            else {
                // Registration failed after launch: stop only this owned child on this background producer.
                try { stop(running) } catch (_: Throwable) { session.retainPrivateFiles() }
            }
            try { resources.close() }
            finally {
                // Deadline/caller already settles independently. A successful destroy dispatch
                // or terminal WorkInfo is not actual child-exit evidence.
                if (session.privateFileOwner != null) awaitOwnedProcessExit(running, session)
            }
        }
    }

    internal fun awaitOwnedProcessExit(process: Process, session: MediaResolutionSession) {
        var interrupted = false
        try {
            while (true) {
                try { process.waitFor(); return }
                catch (_: InterruptedException) { interrupted = true }
                catch (failure: Throwable) { session.retainPrivateFiles(); throw failure }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

/** Each actual process stream has one close claim, including cancellation before first read. */
internal class OwnedOriginalMediaProcessResources(
    private val process: Process, private val failedClose: () -> Unit
) : AutoCloseable {
    private var inputClosed = false
    private var outputClosed = false
    private var errorClosed = false
    private var outputReader: java.io.BufferedReader? = null
    fun reader(): java.io.BufferedReader {
        check(!outputClosed)
        return outputReader ?: process.inputStream.bufferedReader().also { outputReader = it }
    }
    fun closeInput() {
        if (inputClosed) return
        inputClosed = true
        try { process.outputStream.close() }
        catch (failure: Throwable) { failedClose(); throw failure }
    }
    fun closeOutput() {
        if (outputClosed) return
        outputClosed = true
        try { val actual: java.io.Closeable = outputReader ?: process.inputStream; actual.close() }
        catch (failure: Throwable) { failedClose(); throw failure }
    }
    override fun close() {
        var failure: Throwable? = null
        fun attempt(block: () -> Unit) {
            try { block() } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure?.addSuppressed(problem)
            }
        }
        attempt { closeInput() }; attempt { closeOutput() }
        if (!errorClosed) {
            errorClosed = true
            attempt {
                try { process.errorStream.close() }
                catch (problem: Throwable) { failedClose(); throw problem }
            }
        }
        failure?.let { throw it }
    }
}
