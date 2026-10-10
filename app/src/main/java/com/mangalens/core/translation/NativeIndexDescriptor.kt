package com.mangalens.core.translation

import com.mangalens.core.io.AndroidFileOpenFlags
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.Closeable
import java.io.File

/** The opened descriptor, rather than a later path lookup, supplies inode identity. */
internal data class NativeIndexDescriptorIdentity(val device: Long, val inode: Long, val size: Long, val modifiedSeconds: Long)
internal interface NativeIndexReadHandle : Closeable {
    val identity: NativeIndexDescriptorIdentity
    fun matchesPath(): Boolean
    fun seek(offset: Long)
    fun read(bytes: ByteArray, count: Int): Int
}
internal class AndroidNativeIndexReadHandle(private val file: File) : NativeIndexReadHandle {
    private val descriptor = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or AndroidFileOpenFlags.CLOSE_ON_EXEC, 0)
    override val identity: NativeIndexDescriptorIdentity
    init {
        try {
            identity = stamp(Os.fstat(descriptor))
            require(matchesPath()) { "Saved file was replaced while opening its descriptor." }
        } catch (failure: Throwable) { Os.close(descriptor); throw failure }
    }
    override fun matchesPath(): Boolean = runCatching {
        stamp(Os.fstat(descriptor)) == identity && stamp(Os.lstat(file.absolutePath)) == identity
    }.getOrDefault(false)
    override fun seek(offset: Long) { require(Os.lseek(descriptor, offset, OsConstants.SEEK_SET) == offset) }
    override fun read(bytes: ByteArray, count: Int): Int = Os.read(descriptor, bytes, 0, count)
    override fun close() { Os.close(descriptor) }
    private fun stamp(stat: StructStat): NativeIndexDescriptorIdentity {
        require((stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG)
        return NativeIndexDescriptorIdentity(stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime)
    }
}
