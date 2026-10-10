package com.mangalens.core.reader

import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pages only: no browser/private notes, translated output, correction or task journals. */
internal object ChapterCbzPolicy {
    const val MAX_PAGES = 1_000
    const val MAX_PAGE_BYTES = 40L * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    const val MAX_SOURCE_PIXELS = 100_000_000L
    const val COPY_BUFFER_BYTES = 64 * 1024
    fun admitPage(currentBytes: Long, pageBytes: Long): Long {
        if (currentBytes < 0 || pageBytes !in 1..MAX_PAGE_BYTES || currentBytes > MAX_ARCHIVE_BYTES - pageBytes)
            throw IOException("Original pages exceed the 40 MiB page or 512 MiB chapter export limit.")
        return currentBytes + pageBytes
    }
    fun entryName(ordinal: Int, extension: String): String {
        require(ordinal in 0 until MAX_PAGES && extension in setOf("png", "jpg", "webp", "gif", "bmp"))
        return (ordinal + 1).toString().padStart(5, '0') + "." + extension
    }
    fun suggestedName(title: String): String = title.take(120).map {
        if (it.isLetterOrDigit() || it in " -_().") it else '_'
    }.joinToString("").trim().take(80).ifBlank { "MangaLens-chapter" } + ".cbz"
}

internal data class ChapterCbzPage(
    val index: Int, val sourceUrl: String, val path: String, val document: ChapterDocumentSource?
)

/** Captured cheap values; all canonicalization, hashing and Library access happen on producer IO. */
internal class ChapterCbzScope private constructor(
    val chapterId: String, val title: String, val sourceUrl: String, pages: List<ChapterCbzPage>
) {
    val pages: List<ChapterCbzPage> = Collections.unmodifiableList(ArrayList(pages))
    fun matches(current: SavedChapter?): Boolean = current != null && current.id == chapterId &&
        current.sourceUrl == sourceUrl && current.pages.size == pages.size &&
        current.pages.zip(pages).all { (page, captured) -> page.index == captured.index &&
            page.sourceUrl == captured.sourceUrl && page.localPath == captured.path && page.documentSource == captured.document }
    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DataOutputStream(DigestOutputStream(object : OutputStream() { override fun write(value: Int) {}; override fun write(bytes: ByteArray, offset: Int, length: Int) {} }, digest)).use { out ->
            fun field(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); out.writeInt(bytes.size); out.write(bytes) }
            field("mangalens-original-pages-cbz-v1"); field(chapterId); field(sourceUrl); out.writeInt(pages.size)
            pages.forEach { page ->
                out.writeInt(page.index); field(page.sourceUrl); field(page.path)
                out.writeBoolean(page.document != null)
                page.document?.let { doc -> field(doc.uri); field(doc.kind); out.writeInt(doc.pageIndex); field(doc.documentSha256)
                    out.writeBoolean(doc.archiveEntryName != null); doc.archiveEntryName?.let(::field); out.writeBoolean(doc.persistedReadPermission) }
            }
        }
        return digest.digest().cbzHex()
    }
    companion object {
        fun capture(chapter: SavedChapter): ChapterCbzScope {
            require(chapter.id.matches(Regex("[0-9a-f]{32}"))) { "This chapter cannot be exported. Reopen the Library." }
            require(chapter.pages.size in 1..ChapterCbzPolicy.MAX_PAGES) { "Export supports 1–1000 offline pages per chapter." }
            require(chapter.sourceUrl.length <= 32_768 && chapter.pages.map { it.index }.distinct().size == chapter.pages.size)
            val captured = chapter.pages.map { page ->
                require(page.index >= 0 && page.sourceUrl.length <= 32_768)
                val path = page.localPath ?: throw IOException("Some originals are unavailable offline. Retry their download before exporting.")
                require(path.length in 1..32_768)
                ChapterCbzPage(page.index, page.sourceUrl, path, page.documentSource?.copy())
            }
            return ChapterCbzScope(chapter.id, chapter.title.take(1024), chapter.sourceUrl, captured)
        }
    }
}

internal data class ChapterCbzFileIdentity(val device: Long, val inode: Long, val bytes: Long, val modifiedSeconds: Long, val changedSeconds: Long = 0)
internal data class ChapterCbzPagePin(val sha256: String, val bytes: Long, val crc32: Long,
    val extension: String, val identity: ChapterCbzFileIdentity)
internal interface ChapterCbzHeldOriginal : AutoCloseable {
    val pin: ChapterCbzPagePin
    /** Must read the same held original and reject a changed identity/content. */
    fun copyTo(output: OutputStream, check: () -> Unit)
}
internal interface ChapterCbzOriginals {
    fun open(page: ChapterCbzPage, check: () -> Unit): ChapterCbzHeldOriginal
    fun verify(page: ChapterCbzPage, pin: ChapterCbzPagePin, check: () -> Unit)
}

internal enum class ChapterCbzStage { READY, VERIFYING, PREPARING, RECHECKING, WRITING, FINISHING, COMPLETE }
internal data class ChapterCbzProgress(val stage: ChapterCbzStage = ChapterCbzStage.READY, val pages: Int = 0, val totalPages: Int = 0)
/** The retained producer captures this scalar state, never a Compose callback or Activity. */
internal class ChapterCbzOperation {
    private val mutable = MutableStateFlow(ChapterCbzProgress())
    val progress: StateFlow<ChapterCbzProgress> = mutable.asStateFlow()
    fun update(stage: ChapterCbzStage, pages: Int, total: Int) { mutable.value = ChapterCbzProgress(stage, pages, total) }
}
internal data class ChapterCbzPageReceipt(val ordinal: Int, val sourceIndex: Int, val sha256: String, val bytes: Long)
internal class ChapterCbzReceipt(val chapterId: String, val sourceFingerprint: String,
    val pages: Int, val originalBytes: Long, val archiveBytes: Long, val archiveSha256: String, pageHashes: List<ChapterCbzPageReceipt>) {
    val pageHashes: List<ChapterCbzPageReceipt> = Collections.unmodifiableList(ArrayList(pageHashes))
}
internal class ChapterCbzArtifact(val file: File, val receipt: ChapterCbzReceipt, pins: List<ChapterCbzPagePin>) {
    val pins: List<ChapterCbzPagePin> = Collections.unmodifiableList(ArrayList(pins))
}

/** Streaming STORED entries preserve original bytes and keep compression work bounded. */
internal class ChapterCbzArchive(
    private val privateDirectory: File,
    private val originals: ChapterCbzOriginals,
    private val resources: ChapterCbzResources = ChapterCbzResources(),
    private val current: (ChapterCbzScope) -> Boolean
) {
    fun prepare(scope: ChapterCbzScope, operation: ChapterCbzOperation, check: () -> Unit): ChapterCbzArtifact {
        checkCurrent(scope, check)
        scope.pages.forEach { check(); it.document?.validate() }
        kotlin.check(privateDirectory.mkdirs() || privateDirectory.isDirectory) { "The private export folder is unavailable." }
        val spool = File(privateDirectory, UUID.randomUUID().toString() + ".cbz.tmp")
        require(spool.createNewFile())
        try {
            var rawBytes = 0L
            val pins = ArrayList<ChapterCbzPagePin>()
            resources.usePrivate(FileOutputStream(spool)) { fileOutput ->
                val counted = ChapterCbzLimitedOutput(object : FilterOutputStream(fileOutput) { override fun close() { flush() } }, ChapterCbzPolicy.MAX_ARCHIVE_BYTES, check)
                // finish() writes the directory while the FileOutputStream is still open for fsync.
                val zip = resources.ownPrivate(ChapterCbzOwnedZip(counted))
                try {
                    scope.pages.forEachIndexed { ordinal, page ->
                        checkCurrent(scope, check)
                        operation.update(ChapterCbzStage.VERIFYING, ordinal, scope.pages.size)
                        originals.open(page, check).use { held ->
                            val pin = held.pin
                            require(pin.bytes in 1..ChapterCbzPolicy.MAX_PAGE_BYTES && pin.sha256.matches(Regex("[0-9a-f]{64}")))
                            rawBytes = ChapterCbzPolicy.admitPage(rawBytes, pin.bytes)
                            val entry = ZipEntry(ChapterCbzPolicy.entryName(ordinal, pin.extension)).apply {
                                method = ZipEntry.STORED; size = pin.bytes; compressedSize = pin.bytes; crc = pin.crc32; time = 0L
                            }
                            operation.update(ChapterCbzStage.PREPARING, ordinal, scope.pages.size)
                            zip.putNextEntry(entry); held.copyTo(zip, check); zip.closeEntry(); pins += pin
                        }
                    }
                    check(); zip.finish(); zip.flush(); fileOutput.fd.sync(); check()
                } finally { resources.closePrivate(zip) }
            }
            operation.update(ChapterCbzStage.RECHECKING, scope.pages.size, scope.pages.size)
            val archiveBytes = spool.length()
            require(archiveBytes in 1..ChapterCbzPolicy.MAX_ARCHIVE_BYTES)
            val sha = chapterCbzHash(spool, ChapterCbzPolicy.MAX_ARCHIVE_BYTES, resources, check)
            val artifact = ChapterCbzArtifact(spool, ChapterCbzReceipt(scope.chapterId, scope.fingerprint(), scope.pages.size, rawBytes, archiveBytes, sha, scope.pages.zip(pins).mapIndexed { ordinal, (page, pin) -> ChapterCbzPageReceipt(ordinal, page.index, pin.sha256, pin.bytes) }), pins)
            recheck(scope, artifact, check)
            return artifact
        } catch (failure: Throwable) { if (resources.privateReleaseProven()) spool.delete(); throw failure }
    }
    fun recheck(scope: ChapterCbzScope, artifact: ChapterCbzArtifact, check: () -> Unit) {
        checkCurrent(scope, check)
        require(artifact.pins.size == scope.pages.size && artifact.receipt.sourceFingerprint == scope.fingerprint())
        scope.pages.zip(artifact.pins).forEach { (page, pin) -> check(); originals.verify(page, pin, check) }
        checkCurrent(scope, check)
    }
    private fun checkCurrent(scope: ChapterCbzScope, check: () -> Unit) {
        check(); if (!current(scope)) throw IOException("The saved chapter changed. Reopen it in the Library and export again."); check()
    }
}

/**
 * Successful preparation explicitly calls finish(). Cleanup releases its owned deflater/parent
 * without trying to finalize an interrupted STORED entry or write through retired checkpoints.
 */
private class ChapterCbzOwnedZip(output: OutputStream) : ZipOutputStream(output) {
    private var released = false
    override fun close() {
        if (released) return
        released = true
        var failure: Throwable? = null
        try { def.end() } catch (caught: Throwable) { failure = caught }
        try { out.close() } catch (caught: Throwable) {
            if (failure == null) failure = caught else failure.addSuppressed(caught)
        }
        failure?.let { throw it }
    }
}

internal class ChapterCbzLimitedOutput(output: OutputStream, private val limit: Long, private val check: () -> Unit) : FilterOutputStream(output) {
    var count: Long = 0; private set
    override fun write(value: Int) { check(); if (count >= limit) throw IOException("The CBZ exceeds the 512 MiB export limit."); out.write(value); count++ }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(); if (length < 0 || count > limit - length) throw IOException("The CBZ exceeds the 512 MiB export limit.")
        out.write(bytes, offset, length); count += length
    }
}
internal fun chapterCbzHash(file: File, limit: Long, resources: ChapterCbzResources = ChapterCbzResources(), check: () -> Unit): String = resources.usePrivate(FileInputStream(file)) { input ->
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(ChapterCbzPolicy.COPY_BUFFER_BYTES); var count = 0L
    while (true) { check(); val size = input.read(buffer); if (size < 0) break; count += size
        if (count > limit) throw IOException("The export exceeds its byte limit."); digest.update(buffer, 0, size) }
    check(); digest.digest().cbzHex()
}
internal fun ByteArray.cbzHex(): String = joinToString("") { "%02x".format(it) }
