package com.mangalens.ui.video

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Descriptor-first regular-file admission for this application's retained fragment source. */
internal object SubtitleFragmentFiles {
    internal data class Stamp(val device: Long, val inode: Long, val size: Long, val modified: Long, val changed: Long)
    private fun stamp(value: StructStat) = Stamp(value.st_dev, value.st_ino, value.st_size, value.st_mtime, value.st_ctime)
    private val EXTRA = OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC

    internal class Descriptor(val file: File, val fd: FileDescriptor) {
        val captured: Stamp = stamp(Os.fstat(fd))
        fun checkPathIdentity() {
            val path = Os.lstat(file.absolutePath)
            require(OsConstants.S_ISREG(path.st_mode) && path.st_dev == captured.device && path.st_ino == captured.inode) {
                "Subtitle source changed regular-file ownership."
            }
        }
        fun checkIdentity() {
            val actual = Os.fstat(fd)
            require(OsConstants.S_ISREG(actual.st_mode) && actual.st_dev == captured.device && actual.st_ino == captured.inode) {
                "Subtitle source changed regular-file ownership."
            }
            checkPathIdentity()
        }
        fun checkStable() {
            checkIdentity()
            require(stamp(Os.fstat(fd)) == captured && stamp(Os.lstat(file.absolutePath)) == captured) {
                "Held subtitle source identity or bytes changed during its read."
            }
        }
    }
    internal class Registration(private val fd: FileDescriptor) : Closeable {
        var streamClose: (() -> Unit)? = null
        override fun close() { streamClose?.invoke() ?: Os.close(fd) }
    }

    private fun raw(file: File, flags: Int, owner: OwnedFragmentSubtitleWork.Owner): Pair<Descriptor, Registration> {
        owner.checkActive()
        val fd = Os.open(file.absolutePath, flags or EXTRA, 0x180) // Owner-only 0600 for new files.
        // Registered before fstat/lstat and stream construction, including their failure paths.
        val registration = Registration(fd)
        owner.own(registration)
        val descriptor = Descriptor(file, fd)
        descriptor.checkIdentity()
        return descriptor to registration
    }
    fun input(file: File, owner: OwnedFragmentSubtitleWork.Owner): Input {
        val (descriptor, registration) = raw(file, OsConstants.O_RDONLY, owner)
        return Input(descriptor.fd, descriptor, registration, owner).also { registration.streamClose = it::closeDirect }
    }
    fun output(file: File, append: Boolean, owner: OwnedFragmentSubtitleWork.Owner): Output {
        val flags = OsConstants.O_WRONLY or OsConstants.O_CREAT or if (append) OsConstants.O_APPEND else 0
        val (descriptor, registration) = raw(file, flags, owner)
        // Do not truncate a pathname before proving the opened descriptor is the expected regular file.
        owner.checkActive()
        if (!append) Os.ftruncate(descriptor.fd, 0L)
        descriptor.checkIdentity()
        return Output(descriptor.fd, descriptor, registration, owner).also { registration.streamClose = it::closeDirect }
    }
    internal class Input internal constructor(fd: FileDescriptor, private val descriptor: Descriptor,
        private val registration: Registration, private val owner: OwnedFragmentSubtitleWork.Owner) : FileInputStream(fd) {
        fun checkStable() = descriptor.checkStable()
        fun capturedSize(): Long = descriptor.captured.size
        override fun close() = owner.close(registration)
        internal fun closeDirect() = super.close()
    }
    internal class Output internal constructor(fd: FileDescriptor, private val descriptor: Descriptor,
        private val registration: Registration, private val owner: OwnedFragmentSubtitleWork.Owner) : FileOutputStream(fd) {
        fun checkIdentity() = descriptor.checkIdentity()
        fun checkClosedPathIdentity() = descriptor.checkPathIdentity()
        override fun close() = owner.close(registration)
        internal fun closeDirect() = super.close()
    }

    fun randomAccess(file: File, mode: String, owner: OwnedFragmentSubtitleWork.Owner): RandomAccessFile {
        require(mode == "rw")
        val (descriptor, registration) = raw(file, OsConstants.O_RDWR or OsConstants.O_CREAT, owner)
        val anchor = ParcelFileDescriptor.dup(descriptor.fd)
        owner.own(anchor)
        owner.close(registration)
        owner.checkActive(); descriptor.checkPathIdentity()
        // This kernel-owned path names the held, already regular descriptor, not the mutable pathname.
        // Its anchor remains held until the RAF's actual close has returned successfully.
        val access = Access("/proc/self/fd/${anchor.fd}", owner, anchor)
        access.register()
        val actual = Os.fstat(access.fd); val held = Os.fstat(anchor.fileDescriptor)
        require(OsConstants.S_ISREG(actual.st_mode) && actual.st_dev == held.st_dev && actual.st_ino == held.st_ino)
        descriptor.checkPathIdentity()
        return access
    }
    private class Access(path: String, private val owner: OwnedFragmentSubtitleWork.Owner,
        private val anchor: ParcelFileDescriptor) : RandomAccessFile(path, "rw") {
        private val registration = Closeable { closeDirect(); owner.close(anchor) }
        private fun closeDirect() = super.close()
        fun register() = owner.own(registration)
        override fun close() = owner.close(registration)
    }
}
