package com.mangalens.orez.agent

import com.mangalens.core.io.AndroidFileOpenFlags
import android.system.Os
import android.system.OsConstants
import com.mangalens.core.reader.ChapterAcquisitionPrivateFiles
import com.mangalens.core.reader.ChapterCbzOriginalSource
import com.mangalens.core.reader.ChapterCbzPage
import com.mangalens.core.reader.ChapterCbzResources
import com.mangalens.core.reader.ChapterLibraryJournalIo
import com.mangalens.core.reader.SavedChapter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Locale

/** Retains every failed-close handle; the application admission retains this entire owner. */
internal class OrezAcquisitionPrivateOwner(private val files: File) : ChapterAcquisitionPrivateFiles {
    private val resources = ChapterCbzResources().also { it.beginPrivateWork() }
    private val images = File(files, "chapters")
    var publicationGuard: () -> Unit = { }
    fun beforePublication() { check(privateReleaseProven()) { "Private chapter close is unproven." }; publicationGuard() }
    override fun <T : AutoCloseable, R> usePrivate(value: T, action: (T) -> R): R = resources.usePrivate(value, action)
    override fun privateReleaseProven() = resources.privateReleaseProven()
    override fun verifiedRevision(file: File, checkActive: () -> Unit): String {
        val held = ChapterCbzOriginalSource(images, resources, openInput = { openRegular(it, 40L * 1024 * 1024, images) }).open(ChapterCbzPage(0, "", file.path, null), checkActive)
        try { return held.pin.sha256 } finally { held.close() }
    }
    fun finish() {
        resources.finishPrivateWork()
        try { resources.close() }
        catch (failure: Throwable) { throw java.io.IOException("Native chapter private cleanup is unproven. Restart MangaLens before another acquisition.", failure) }
    }
    private class DescriptorLease : AutoCloseable {
        var descriptor: java.io.FileDescriptor? = null
        override fun close() { descriptor?.let { Os.close(it) } }
    }
    private fun openRegular(file: File, maximumBytes: Long, parent: File): FileInputStream {
        require(OsConstants.S_ISDIR(Os.lstat(parent.absolutePath).st_mode) && file.absoluteFile.parentFile == parent.absoluteFile &&
            file.canonicalFile.parentFile == parent.canonicalFile) { "Private chapter input is outside its managed directory." }
        val lease = resources.ownPrivate(DescriptorLease())
        try {
            val descriptor = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or AndroidFileOpenFlags.CLOSE_ON_EXEC, 0)
            lease.descriptor = descriptor
            val held = Os.fstat(descriptor); val current = Os.lstat(file.absolutePath)
            require(OsConstants.S_ISREG(held.st_mode) && OsConstants.S_ISREG(current.st_mode) && held.st_dev == current.st_dev &&
                held.st_ino == current.st_ino && held.st_size == current.st_size && held.st_size in 1..maximumBytes) { "Private chapter input is not a bounded regular file." }
            // The registered descriptor lease exists before stream construction. Its one close owns the FD.
            return object : FileInputStream(descriptor) { override fun close() = resources.closePrivate(lease) }
        } catch (failure: Throwable) { resources.closePrivate(lease); throw failure }
    }

    override fun openOutput(file: File, append: Boolean): FileOutputStream {
        val parent = requireNotNull(file.absoluteFile.parentFile)
        require(parent in listOf(images.absoluteFile, File(files, "chapter_library").absoluteFile, File(files, "orez_chapter_acquisitions").absoluteFile) &&
            OsConstants.S_ISDIR(Os.lstat(parent.path).st_mode) && file.canonicalFile.parentFile == parent.canonicalFile) { "Private chapter output is outside its managed directory." }
        val lease = resources.ownPrivate(DescriptorLease())
        try {
            val flags = OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or AndroidFileOpenFlags.CLOSE_ON_EXEC or
                (if (append) OsConstants.O_APPEND else OsConstants.O_TRUNC)
            val descriptor = Os.open(file.absolutePath, flags, 384)
            lease.descriptor = descriptor
            val held = Os.fstat(descriptor); val current = Os.lstat(file.absolutePath)
            require(OsConstants.S_ISREG(held.st_mode) && OsConstants.S_ISREG(current.st_mode) && held.st_dev == current.st_dev && held.st_ino == current.st_ino) {
                "Private chapter output is not the owned regular stage."
            }
            return object : FileOutputStream(descriptor) { override fun close() = resources.closePrivate(lease) }
        } catch (failure: Throwable) { resources.closePrivate(lease); throw failure }
    }
    fun input(file: File): InputStream {
        val parent = requireNotNull(file.absoluteFile.parentFile)
        require(parent == File(files, "chapter_library").absoluteFile || parent == File(files, "orez_chapter_acquisitions").absoluteFile)
        return openRegular(file, 2_000_000, parent)
    }

    internal data class Stamp(val path: String, val device: Long, val inode: Long, val bytes: Long,
        val modifiedSeconds: Long, val changedSeconds: Long, val key: Any, val modified: FileTime)
    internal data class Proof(val chapter: OrezChapterSnapshot, val bytes: Long, val stamps: List<Stamp>)
    private fun stamp(file: File, input: FileInputStream? = null): Stamp {
        val current = Os.lstat(file.absolutePath)
        val held = input?.let { Os.fstat(it.fd) } ?: current
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(OsConstants.S_ISREG(current.st_mode) && OsConstants.S_ISREG(held.st_mode) && attributes.isRegularFile &&
            !attributes.isSymbolicLink && attributes.fileKey() != null && held.st_dev == current.st_dev && held.st_ino == current.st_ino &&
            held.st_size == current.st_size && held.st_mtime == current.st_mtime && held.st_ctime == current.st_ctime &&
            attributes.size() == held.st_size && held.st_size in 1..40L * 1024 * 1024 &&
            OsConstants.S_ISDIR(Os.lstat(images.absolutePath).st_mode) && file.canonicalFile.parentFile == images.canonicalFile) {
            "A chapter original changed, is a link, or is outside managed storage."
        }
        return Stamp(file.absolutePath, held.st_dev, held.st_ino, held.st_size, held.st_mtime, held.st_ctime,
            requireNotNull(attributes.fileKey()), attributes.lastModifiedTime())
    }
    fun requireCurrent(proof: Proof?) { proof?.stamps?.forEach { require(stamp(File(it.path)) == it) { "Original bytes changed after their actual held-FD hash." } } }
    suspend fun inspect(chapter: SavedChapter, budget: OrezChapterSourceReadBudget): Proof {
        require(chapter.pages.size in 1..1000 && chapter.pages.map { it.index }.distinct().size == chapter.pages.size)
        val context = currentCoroutineContext(); val checked = ArrayList<OrezChapterSource>(); val stamps = ArrayList<Stamp>(); var total = 0L
        for (page in chapter.pages) {
            context.ensureActive()
            val file = File(requireNotNull(page.localPath))
            usePrivate(openRegular(file, 40L * 1024 * 1024, images)) { input ->
                val admitted = stamp(file, input); val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    context.ensureActive()
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), budget.remainingBytes + 1).toInt())
                    if (read < 0) break
                    budget.consume(read); count += read; total += read
                    require(count <= admitted.bytes) { "Original grew during its actual hash." }
                    digest.update(buffer, 0, read)
                }
                require(count == admitted.bytes && stamp(file, input) == admitted) { "Original changed during its actual hash." }
                checked += OrezChapterSource(page.index, file.absolutePath, digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) })
                stamps += admitted
            }
        }
        val proof = Proof(OrezChapterSourceEvidence.snapshot(chapter.id, chapter.title, checked), total, stamps.toList())
        requireCurrent(proof); return proof
    }
}

/** Close is proven before rename. A failed close retains the owned stage and blocks later admission. */
internal class OrezOwnedChapterJournalIo(private val owner: OrezAcquisitionPrivateOwner) : ChapterLibraryJournalIo {
    override fun read(file: File): InputStream {
        val backup = File(file.path + ".bak")
        if (backup.exists()) Os.rename(backup.absolutePath, file.absolutePath)
        return owner.input(file)
    }
    override fun write(file: File, bytes: ByteArray) {
        val stage = File(file.path + ".new")
        check(owner.privateReleaseProven()) { "Private chapter cleanup is unproven. Restart before another acquisition." }
        check(!stage.exists() || stage.delete()) { "Unable to clear an earlier closed chapter journal stage." }
        try {
            owner.usePrivate(owner.openOutput(stage)) { output -> output.write(bytes); output.fd.sync() }
            check(owner.privateReleaseProven()) { "Chapter journal close was not proven." }
            owner.beforePublication()
            Os.rename(stage.absolutePath, file.absolutePath)
        } finally { if (owner.privateReleaseProven()) stage.delete() }
    }
    override fun delete(file: File) { error("Native chapter acquisition cannot delete Library or original records.") }
}
