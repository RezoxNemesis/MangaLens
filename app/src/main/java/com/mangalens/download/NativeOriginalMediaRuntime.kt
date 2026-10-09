package com.mangalens.download

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class NativeMediaTool(val fileName: String) { FFMPEG("libffmpeg.so"), FFPROBE("libffprobe.so") }
internal data class NativeOriginalMediaInstallation(val binaryDirectory: File, val libraryDirectories: List<File>)

/** No self-update or Python execution: only checksum-pinned APK executables and ELF dependencies. */
internal object NativeOriginalMediaRuntime {
    private var installed: Pair<String, NativeOriginalMediaInstallation>? = null

    @Synchronized
    fun initialize(context: Context, session: MediaResolutionSession): NativeOriginalMediaInstallation {
        session.checkActive()
        val binaryDirectory = File(context.applicationInfo.nativeLibraryDir)
        installed?.takeIf { it.first == binaryDirectory.absolutePath }?.let { return it.second }
        val asset = context.assets.open("media/ffmpeg-0.18.1-pins.json").use { input ->
            val bytes = input.readBytes(); require(bytes.size <= 256 * 1024); JSONObject(bytes.toString(Charsets.UTF_8))
        }
        val archive = File(binaryDirectory, "libffmpeg.zip.so")
        val archiveHash = NativeMediaArchiveInstaller.sha256(archive, session::checkActive)
        val rows = asset.getJSONArray("abis")
        val row = (0 until rows.length()).map(rows::getJSONObject)
            .singleOrNull { it.getString("archiveSha256") == archiveHash }
            ?: error("This packaged original-media runtime is not pinned for the installed ABI.")
        val binaries = row.getJSONObject("binaries")
        for (tool in NativeMediaTool.entries) {
            session.checkActive()
            val pin = binaries.getJSONObject(tool.fileName)
            val binary = File(binaryDirectory, tool.fileName)
            require(binary.length() == pin.getLong("bytes") &&
                NativeMediaArchiveInstaller.sha256(binary, session::checkActive) == pin.getString("sha256")) {
                "Packaged original-media executable failed its integrity check."
            }
        }
        fun entries(json: JSONObject): List<NativeMediaArchiveEntry> {
            val source = json.getJSONArray("entries")
            return (0 until source.length()).map { index -> source.getJSONObject(index).let {
                NativeMediaArchiveEntry(it.getString("path"), it.getLong("bytes"), it.getString("sha256"),
                    it.optString("symlink").takeIf(String::isNotBlank))
            } }
        }
        val directory = File(context.noBackupFilesDir, "original_media_runtime")
        val ffmpeg = NativeMediaArchiveInstaller.install(File(directory, "ffmpeg"), archive,
            NativeMediaArchivePin(row.getLong("archiveBytes"), archiveHash, entries(row)), session::checkActive)
        val pythonPin = row.getJSONObject("pythonDependencies")
        val support = NativeMediaArchiveInstaller.install(File(directory, "support"), File(binaryDirectory, "libpython.zip.so"),
            NativeMediaArchivePin(pythonPin.getLong("archiveBytes"), pythonPin.getString("archiveSha256"),
                entries(pythonPin), completeInventory = false), session::checkActive)
        session.checkActive()
        return NativeOriginalMediaInstallation(binaryDirectory, listOf(File(ffmpeg, "usr/lib"), File(support, "usr/lib")))
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
        val environment = builder.environment()
        environment["LD_LIBRARY_PATH"] = (installation.libraryDirectories + installation.binaryDirectory)
            .joinToString(":") { it.absolutePath }
        environment["PATH"] = "/system/bin"
        for (name in listOf("http_proxy", "https_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")) environment.remove(name)
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
