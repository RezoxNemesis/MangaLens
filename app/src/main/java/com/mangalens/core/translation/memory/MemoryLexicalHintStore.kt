package com.mangalens.core.translation.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.channels.Channels
import java.nio.file.attribute.BasicFileAttributes

/** Derived private cache only. It reads journal bytes, never saved source/surface bytes. */
internal class MemoryLexicalHintStore(filesRoot: File, private val writer: MemoryJournalWriter = AtomicMemoryJournalWriter) {
    private val root = filesRoot.canonicalFile
    private val memory = File(root, "reader_memory")
    private val chapters = File(memory, "chapters")
    private val lexical = File(memory, "lexical")
    private val cache = File(lexical, "index.json")
    private var warm: MemoryLexicalHintIndex? = null

    suspend fun find(query: String, limit: Int = 32, forceRefresh: Boolean = false,
        kinds: Set<MemorySearchKind>? = null): MemoryLexicalHintBatch = withContext(Dispatchers.IO) {
        // Query errors cannot create or rewrite a cache.
        val normalized = query.trim()
        require(normalized.isNotBlank() && normalized.length <= 256 && '\u0000' !in normalized && limit in 1..32)
        cacheGate.withLock {
            currentCoroutineContext().ensureActive()
            val inventory = inventory()
            val existing = if (forceRefresh) null else (warm?.takeIf { it.inventory == inventory.identity }
                ?: readCache()?.takeIf { it.inventory == inventory.identity })
            val index = existing ?: rebuild(inventory)
            warm = index
            index.find(normalized, limit, kinds)
        }
    }

    private data class Entry(val file: File, val stamp: MemoryReadDeliveryStamp)
    private data class Inventory(val entries: List<Entry>, val identity: String, val incomplete: Boolean)
    private fun inventory(): Inventory {
        requireManaged(memory, root); requireManaged(chapters, memory)
        val raw = chapters.listFiles().orEmpty().filter { it.name.endsWith(".json") && memoryValidId(it.name.removeSuffix(".json")) }.sortedBy { it.name }
        require(raw.size <= MAX_CHAPTERS) { "Saved text index has too many journal identities." }
        var incomplete = false
        val entries = raw.mapNotNull { file ->
            runCatching {
                val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                require(attributes.isRegularFile && !attributes.isSymbolicLink && file.canonicalFile.parentFile == chapters.canonicalFile)
                Entry(file, MemoryReadDeliveryStamp.capture(file))
            }.getOrElse { incomplete = true; null }
        }
        // Unreadable names are part of the inventory too, so omissions cannot hide an inventory change.
        val identity = memoryHash(raw.map { file ->
            val stamp = entries.singleOrNull { it.file.name == file.name }?.stamp
            listOf(file.name, stamp?.canonical.orEmpty(), stamp?.fileKey?.toString().orEmpty(),
                stamp?.size?.toString().orEmpty(), stamp?.modified?.toString().orEmpty()).joinToString("\u0000")
        })
        return Inventory(entries, identity, incomplete)
    }

    private suspend fun rebuild(captured: Inventory): MemoryLexicalHintIndex {
        var scanned = 0L
        var incomplete = captured.incomplete
        var encodedBytes = 512L // Exact row sizes plus a conservative fixed v1 envelope.
        val rows = ArrayList<MemoryLexicalHint>()
        for ((file, stamp) in captured.entries) {
            currentCoroutineContext().ensureActive()
            val size = stamp.size ?: 0
            if (size !in 1..MAX_CHAPTER_BYTES.toLong() || scanned + size > MAX_SCAN_BYTES) { incomplete = true; continue }
            val chapter = try {
                require(stamp.isCurrent())
                val bytes = readBounded(file, minOf(MAX_CHAPTER_BYTES.toLong(), MAX_SCAN_BYTES - scanned).toInt()) { count ->
                    // Charge actual reads even if a growing/corrupt journal fails its captured size or schema.
                    if (count > MAX_SCAN_BYTES - scanned) {
                        scanned = MAX_SCAN_BYTES
                        error("Saved text journals exceed the bounded index input budget.")
                    }
                    scanned += count
                }
                require(bytes.size.toLong() == size && stamp.isCurrent())
                SeriesMemoryCodec.readChapter(bytes).also { require(it.chapterId + ".json" == file.name) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { incomplete = true; continue }
            if (chapter.removed) continue
            for (bubble in chapter.bubbles.sortedWith(compareBy<MemoryIndexedBubble> { it.receipt.source.pageIndex }.thenBy { it.receipt.bubbleId })) {
                currentCoroutineContext().ensureActive()
                // A stale explicit link remains a hint omission, never a borrowed series identity.
                if (bubble.receipt.seriesId != chapter.association?.seriesId ||
                    bubble.receipt.associationRevision?.let { it != chapter.associationRevision } == true) { incomplete = true; continue }
                val fields = listOfNotNull(MemorySearchKind.OCR to bubble.receipt.originalOcr,
                    bubble.receipt.originalTranslation?.let { MemorySearchKind.TRANSLATION to it },
                    bubble.correction?.edit?.correctedOcr?.let { MemorySearchKind.CORRECTED_OCR to it },
                    bubble.correction?.edit?.translated?.let { MemorySearchKind.CORRECTED_TRANSLATION to it })
                for ((kind, text) in fields) {
                    if (rows.size >= MemoryLexicalHintIndex.MAX_HINTS) { incomplete = true; break }
                    val hint = MemoryLexicalHint(chapter.chapterId, bubble.receipt.bubbleId, kind, bubble.editRevision,
                        chapter.association?.seriesId, chapter.associationRevision, memoryTextKey(text))
                    if (runCatching { hint.validate() }.isFailure) { incomplete = true; continue }
                    val rowBytes = MemoryLexicalHintCodec.encodedRowBytes(hint).toLong() + 1
                    if (encodedBytes + rowBytes > MemoryLexicalHintCodec.MAX_BYTES) { incomplete = true; continue }
                    encodedBytes += rowBytes
                    rows += hint
                }
            }
        }
        currentCoroutineContext().ensureActive()
        require(inventory().identity == captured.identity) { "Saved text journals changed during indexing. Please refresh." }
        val index = MemoryLexicalHintIndex(captured.identity, rows, incomplete)
        val bytes = MemoryLexicalHintCodec.encode(index)
        requireManaged(memory, root); requireManaged(lexical, memory)
        check(lexical.mkdirs() || lexical.isDirectory)
        requireManaged(lexical, memory)
        writer.prepare(cache, bytes).use { pending ->
            currentCoroutineContext().ensureActive()
            require(inventory().identity == captured.identity) { "Saved text journals changed before index publication." }
            pending.commit()
        }
        return index
    }

    private suspend fun readCache(): MemoryLexicalHintIndex? = try {
        requireManaged(memory, root); requireManaged(lexical, memory)
        if (!cache.exists()) null else {
            val attrs = Files.readAttributes(cache.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(attrs.isRegularFile && !attrs.isSymbolicLink && cache.canonicalFile.parentFile == lexical.canonicalFile)
            val before = MemoryReadDeliveryStamp.capture(cache)
            MemoryLexicalHintCodec.decode(readBounded(cache, MemoryLexicalHintCodec.MAX_BYTES)).also { require(before.isCurrent()) }
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private suspend fun readBounded(file: File, cap: Int, consume: (Int) -> Unit = {}): ByteArray =
        Channels.newInputStream(Files.newByteChannel(file.toPath(), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))).use { input ->
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (output.size() <= cap) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer, 0, minOf(buffer.size, cap + 1 - output.size()))
            if (count < 0) break
            if (count > 0) { consume(count); output.write(buffer, 0, count) }
        }
        require(output.size() <= cap) { "Saved text index input exceeds its bounded schema." }
        output.toByteArray()
    }

    private fun requireManaged(directory: File, parent: File) {
        require(directory.canonicalFile.parentFile == parent.canonicalFile)
        if (directory.exists()) {
            val attrs = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(attrs.isDirectory && !attrs.isSymbolicLink)
        }
    }

    companion object {
        // One bounded index refresh across independently recreated hosts; no UI effect runs here.
        private val cacheGate = Mutex()
        private const val MAX_CHAPTERS = 1024
        private const val MAX_CHAPTER_BYTES = 2 * 1024 * 1024
        private const val MAX_SCAN_BYTES = 32L * 1024 * 1024
    }
}
