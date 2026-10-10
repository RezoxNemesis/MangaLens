package com.mangalens.orez.agent

import com.mangalens.core.io.AndroidFileOpenFlags
import android.system.Os
import android.system.OsConstants
import com.mangalens.core.reader.ChapterCbzResources
import com.mangalens.ui.downloads.OwnedSavedVideoProbe
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** One application producer retains its slot until actual IO and every registered close return. */
internal object OrezLibraryOwnedIo {
    private val probe = OwnedSavedVideoProbe()
    private val projectionProbe = OwnedSavedVideoProbe()
    private val readerProbe = OwnedSavedVideoProbe()
    suspend fun <T> run(files: File, body: suspend (Access) -> T): T = runOwned(probe,files,true,body)
    suspend fun <T> project(files: File, body: suspend (Access) -> T): T = runOwned(projectionProbe,files,false,body)
    suspend fun <T> readerCheckpoint(files: File, body: suspend (Access) -> T): T = runOwned(readerProbe,files,true,body)
    private suspend fun <T> runOwned(producer: OwnedSavedVideoProbe,files: File,write: Boolean,body: suspend (Access) -> T): T = withTimeout(30_000L) {
        producer.run { owner ->
            val resources = ChapterCbzResources(); owner.own(resources); resources.beginPrivateWork()
            try { body(Access(files.canonicalFile,owner,resources,write)) } finally { resources.finishPrivateWork() }
        }
    }
    internal data class Stamp(val path: String, val fileKey: Any, val size: Long, val modified: java.nio.file.attribute.FileTime,
        val device: Long, val inode: Long, val changedSeconds: Long) {
        fun isCurrent(): Boolean = try {
            val file = java.nio.file.Paths.get(path)
            val attrs = Files.readAttributes(file,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            val current = Os.lstat(path)
            attrs.isRegularFile && !attrs.isSymbolicLink && attrs.fileKey() == fileKey && attrs.size() == size && attrs.lastModifiedTime() == modified &&
                current.st_dev == device && current.st_ino == inode && current.st_ctime == changedSeconds && current.st_size == size
        } catch (_: Exception) { false }
    }
    internal data class Read(val bytes: ByteArray, val sha256: String, val stamp: Stamp)
    internal data class Prepared(val file: File,val stamp: Stamp)
    internal class Access(private val files: File, private val owner: OwnedSavedVideoProbe.Owner, val resources: ChapterCbzResources,private val writeAllowed: Boolean) {
        var receivedBytes = 0L; private set
        val remainingBytes get() = OrezLibraryScope.MAX_BYTES - receivedBytes
        fun checkpoint() = owner.checkActive()
        private class DescriptorLease : AutoCloseable {
            var descriptor: java.io.FileDescriptor? = null
            override fun close() { descriptor?.let(Os::close) }
        }
        private fun parent(file: File, stage: Boolean = false): File {
            val permitted = if (stage) listOf(File(files,"orez_library_tools_stage")) else listOf(
                File(files,"chapter_library"),File(files,"reader_memory/chapters"),File(files,"reader_memory/series"))
            val parent = requireNotNull(file.absoluteFile.parentFile)
            require(parent in permitted.map { it.absoluteFile } && file.canonicalFile.parentFile == parent.canonicalFile &&
                parent.canonicalFile.path.startsWith(files.path + File.separator)) { "Library metadata is outside its native managed namespace." }
            if (parent.parentFile != files) require(!Files.isSymbolicLink(parent.parentFile.toPath()))
            require(OsConstants.S_ISDIR(Os.lstat(parent.path).st_mode) && !Files.isSymbolicLink(parent.toPath()))
            return parent
        }
        private fun stamp(file: File, input: FileInputStream): Stamp {
            val current = Os.lstat(file.absolutePath); val held = Os.fstat(input.fd)
            val attrs = Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            require(OsConstants.S_ISREG(held.st_mode) && OsConstants.S_ISREG(current.st_mode) && attrs.isRegularFile && !attrs.isSymbolicLink &&
                held.st_dev == current.st_dev && held.st_ino == current.st_ino && held.st_size == current.st_size && held.st_size == attrs.size() &&
                held.st_mtime == current.st_mtime && held.st_ctime == current.st_ctime)
            return Stamp(file.absolutePath,requireNotNull(attrs.fileKey()),held.st_size,attrs.lastModifiedTime(),held.st_dev,held.st_ino,held.st_ctime)
        }
        private fun open(file: File, maximum: Int): FileInputStream {
            checkpoint(); parent(file)
            val lease = resources.ownPrivate(DescriptorLease())
            try {
                val descriptor = Os.open(file.absolutePath,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or AndroidFileOpenFlags.CLOSE_ON_EXEC,0)
                lease.descriptor = descriptor
                val held = Os.fstat(descriptor); val current = Os.lstat(file.absolutePath)
                require(OsConstants.S_ISREG(held.st_mode) && OsConstants.S_ISREG(current.st_mode) && held.st_dev == current.st_dev &&
                    held.st_ino == current.st_ino && held.st_size == current.st_size && held.st_size in 1..maximum.toLong()) { "Metadata is not an admitted bounded regular file." }
                return object : FileInputStream(descriptor) { override fun close() = resources.closePrivate(lease) }
            } catch (failure: Throwable) { resources.closePrivate(lease); throw failure }
        }
        /** AtomicFile .bak recovery is performed explicitly under Library's existing mutation fence. */
        fun readLibrary(file: File, maxBytes: Int): Read {
            checkpoint(); require(maxBytes in 1..2_000_000); parent(file)
            val backup = File(file.path + ".bak")
            if (Files.exists(backup.toPath(),LinkOption.NOFOLLOW_LINKS)) {
                parent(backup); require(OsConstants.S_ISREG(Os.lstat(backup.path).st_mode)); Os.rename(backup.path,file.path)
            }
            return read(file,maxBytes)
        }
        fun readMemory(file: File, maxBytes: Int): Read { require(maxBytes in 1..2_097_152); return read(file,maxBytes) }
        private fun read(file: File, maximum: Int): Read {
            checkpoint(); require(remainingBytes > 0)
            val limit = minOf(maximum.toLong(),remainingBytes).toInt()
            val input = open(file,limit)
            try {
                val before = stamp(file,input)
                val bytes = ByteArrayOutputStream(); val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192)
                while (bytes.size().toLong() < before.size) {
                    checkpoint(); val count = input.read(buffer,0,minOf(buffer.size.toLong(),before.size - bytes.size()).toInt())
                    require(count >= 0) { "Metadata ended before its admitted size." }
                    require(count > 0); receivedBytes += count
                    require(receivedBytes <= OrezLibraryScope.MAX_BYTES && bytes.size() <= limit - count) { "Library metadata exceeds its actual aggregate read budget." }
                    bytes.write(buffer,0,count); digest.update(buffer,0,count)
                }
                require(bytes.size().toLong() == before.size && stamp(file,input) == before) { "Metadata changed during the held-descriptor read." }
                input.close(); checkpoint(); require(before.isCurrent())
                return Read(bytes.toByteArray(),digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT,it.toInt() and 255) },before)
            } finally { input.close() }
        }
        fun inventory(directory: File): Pair<List<String>,Boolean> {
            checkpoint(); if (!directory.exists()) return emptyList<String>() to false
            require(directory.absoluteFile == File(files,"chapter_library").absoluteFile && !Files.isSymbolicLink(directory.toPath()) &&
                OsConstants.S_ISDIR(Os.lstat(directory.path).st_mode))
            val stream = resources.ownPrivate(Files.newDirectoryStream(directory.toPath()))
            try {
                val names = linkedSetOf<String>(); val iterator = stream.iterator(); var inspected = 0; var limited = false
                while (iterator.hasNext()) {
                    checkpoint(); if (inspected++ >= OrezLibraryScope.MAX_DIRECTORY_ENTRIES) { limited = true; break }
                    val name = iterator.next().fileName.toString().removeSuffix(".bak")
                    if (Regex("[a-f0-9]{32}\\.json").matches(name)) {
                        if (names.size >= OrezLibraryScope.MAX_MANIFESTS && name !in names) { limited = true; break }
                        names += name
                    }
                }
                return names.sorted().map { it.removeSuffix(".json") } to limited
            } finally { resources.closePrivate(stream) }
        }
        fun prepare(kind: String, bytes: ByteArray): Prepared {
            checkpoint(); require(writeAllowed); require(kind in setOf("library","profile","reader")); require(bytes.size in 1..2_097_152)
            val directory = File(files,"orez_library_tools_stage"); check(directory.mkdirs() || directory.isDirectory)
            val stage = File(directory,"prepared-$kind.pending"); parent(stage,true)
            val lease = resources.ownPrivate(DescriptorLease())
            try {
                // Do not truncate until the real descriptor is proven to be our regular, unshared stage.
                val descriptor = Os.open(stage.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or AndroidFileOpenFlags.CLOSE_ON_EXEC,384)
                lease.descriptor = descriptor
                val held = Os.fstat(descriptor); val current = Os.lstat(stage.path)
                require(OsConstants.S_ISREG(held.st_mode) && OsConstants.S_ISREG(current.st_mode) && held.st_dev == current.st_dev && held.st_ino == current.st_ino && held.st_nlink == 1L)
                Os.ftruncate(descriptor,0L)
                val output = object : FileOutputStream(descriptor) { override fun close() = resources.closePrivate(lease) }
                try {
                    output.write(bytes); output.fd.sync()
                    val current = Os.lstat(stage.path); val actual = Os.fstat(descriptor)
                    val attrs = Files.readAttributes(stage.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
                    require(attrs.isRegularFile && !attrs.isSymbolicLink && actual.st_dev == current.st_dev && actual.st_ino == current.st_ino && actual.st_size == bytes.size.toLong())
                    val stamp = Stamp(stage.path,requireNotNull(attrs.fileKey()),actual.st_size,attrs.lastModifiedTime(),actual.st_dev,actual.st_ino,actual.st_ctime)
                    output.close(); checkpoint(); require(stamp.isCurrent()); return Prepared(stage,stamp)
                } finally { output.close() }
            } catch (failure: Throwable) { try { resources.closePrivate(lease) } finally { if (resources.privateReleaseProven()) stage.delete() }; throw failure }
        }
        fun cleanup(stage: File?) { if (stage != null && resources.privateReleaseProven()) stage.delete() }
        fun publicationReady() { checkpoint(); check(resources.privateReleaseProven()) { "Native metadata descriptor close is unproven. Restart before another Library operation." } }
    }
}
