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
        val guard = MediaProcessGuard(session, "original-media-${UUID.randomUUID()}", budgetBeforeStart) { stop(running) }
        try {
            session.checkActive()
            running.outputStream.close()
            running.inputStream.bufferedReader().use { reader ->
                while (true) {
                    session.checkActive()
                    val line = reader.readLine() ?: break
                    consumeLine(line)
                }
            }
            check(running.waitFor() == 0) {
                "Original media processing failed. No lower-quality source was substituted."
            }
            session.checkActive()
        } finally { guard.close() }
    }
}
