package com.mangalens.ui.ai.voice

import com.mangalens.core.io.AndroidFileOpenFlags
import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.security.MessageDigest

internal class VoiceModelRequiredException : Exception(
    "Offline microphone input requires the verified multilingual Tiny model (74 MiB). Open speech models to install it explicitly.")

/** Owns a stable actual inode through model load, inference, engine free and final FD close. */
internal class PinnedVoiceModel {
    private var raw: FileDescriptor? = null
    private var rawCloseAttempted = false
    private var held: ParcelFileDescriptor? = null
    private var heldCloseAttempted = false
    private var identity: Pair<Long, Long>? = null
    private var unproven = false
    private var fdNumber = -1
    val loadFile: File get() { check(fdNumber >= 0 && held != null && !heldCloseAttempted); return File("/proc/self/fd/$fdNumber") }

    fun open(context: Context) {
        check(raw == null && held == null)
        val directory = File(context.filesDir, "speech")
        val model = File(directory, "whisper.bin")
        try {
            val dirStat = Os.lstat(directory.absolutePath)
            if (!OsConstants.S_ISDIR(dirStat.st_mode)) throw VoiceModelRequiredException()
            val before = Os.lstat(model.absolutePath)
            if (!OsConstants.S_ISREG(before.st_mode) || before.st_size != OfflineOrezVoicePolicy.TINY_BYTES)
                throw VoiceModelRequiredException()
            raw = Os.open(model.absolutePath, OsConstants.O_RDONLY or AndroidFileOpenFlags.CLOSE_ON_EXEC or OsConstants.O_NOFOLLOW, 0)
            val descriptor = requireNotNull(raw)
            val captured = Os.fstat(descriptor)
            if (captured.st_dev != before.st_dev || captured.st_ino != before.st_ino ||
                !OsConstants.S_ISREG(captured.st_mode) || captured.st_size != OfflineOrezVoicePolicy.TINY_BYTES)
                throw VoiceModelRequiredException()
            identity = captured.st_dev to captured.st_ino
            held = ParcelFileDescriptor.dup(descriptor)
            fdNumber = requireNotNull(held).fd
            closeRaw()
            if (unproven) error("The speech model descriptor could not be safely transferred.")
        } catch (missing: android.system.ErrnoException) { throw VoiceModelRequiredException() }
    }

    /** Full pin is checked before each actual load/inference, including after native admission waits. */
    fun verify(current: () -> Boolean) {
        check(current()) { "Microphone request was retired." }
        val descriptor = requireNotNull(held).fileDescriptor
        check(!heldCloseAttempted && !unproven)
        val before = Os.fstat(descriptor)
        check((before.st_dev to before.st_ino) == identity && before.st_size == OfflineOrezVoicePolicy.TINY_BYTES &&
            OsConstants.S_ISREG(before.st_mode)) { "The captured speech model changed." }
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(65_536)
        var offset = 0L
        while (offset < before.st_size) {
            check(current()) { "Microphone request was retired." }
            val count = Os.pread(descriptor, bytes, 0, minOf(bytes.size.toLong(), before.st_size - offset).toInt(), offset)
            check(count > 0) { "The captured speech model is incomplete." }
            digest.update(bytes, 0, count); offset += count
        }
        val after = Os.fstat(descriptor)
        check((after.st_dev to after.st_ino) == identity && after.st_size == before.st_size &&
            after.st_mtime == before.st_mtime && after.st_ctime == before.st_ctime &&
            OfflineOrezVoicePolicy.acceptsModel(offset, digest.digest().joinToString("") { "%02x".format(it) })) {
            "The installed speech model is not the verified Tiny model. Install it explicitly in speech models."
        }
        check(current()) { "Microphone request was retired." }
    }

    private fun closeRaw() {
        val descriptor = raw ?: return
        if (rawCloseAttempted) return
        rawCloseAttempted = true
        try { Os.close(descriptor) } catch (_: Exception) { unproven = true }
    }
    fun closeAfterEngine(): Boolean {
        closeRaw()
        val descriptor = held
        if (descriptor != null && !heldCloseAttempted) {
            heldCloseAttempted = true
            try { descriptor.close() } catch (_: Exception) { unproven = true }
        }
        return !unproven
    }
}
