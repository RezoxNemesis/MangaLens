package com.mangalens.core.translation

import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.memory.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Capture uses actual durable native bytes; runtime document/cache fingerprints are never authority. */
internal class NativeGenerationMemoryCapture(private val memory: SeriesMemoryStore, private val native: ChapterTranslationStore,
    private val hashSelectedSource: (File) -> String = ChapterTranslationStore::sha256,
    private val prepareProof: (ReaderTranslationReceipt, Int) -> NativeMemoryPageProof? = native::prepareMemoryPublication) {
    suspend fun capture(chapter: SavedChapter, configuration: ChapterTranslationConfig, firstPage: Int): CapturedSeriesMemoryPacket? = withContext(Dispatchers.IO) {
        val base = configuration.copy(memoryPacket = null).normalized()
        val files = try { chapter.pages.sortedBy { it.index }.map { page -> page to page.localPath?.let { File(it).canonicalFile } } }
            catch (_: java.io.IOException) { return@withContext null }
        var sourceBytes = 0L
        val admitted = HashMap<String, NativeMemoryFileStamp>()
        try {
            for (file in files.mapNotNull { it.second }.distinctBy { it.path }.filter(File::isFile)) {
                val stamp = sourceStamp(file)
                if (stamp.size < 0 || stamp.size > MAX_ARTIFACT_BYTES - sourceBytes) return@withContext null
                admitted[file.path] = stamp
                sourceBytes += stamp.size
            }
        } catch (_: java.io.IOException) { return@withContext null }
          catch (_: IllegalArgumentException) { return@withContext null }
        val hashes = HashMap<String, String>()
        val sourceRows = try {
            files.map { (page, file) ->
                val expected = file?.let { admitted[it.path] }
                val hash = if (file == null || expected == null) {
                    require(file?.isFile != true) { "A source appeared after memory size admission." }
                    null
                } else {
                    require(sourceStamp(file) == expected) { "Source changed after memory size admission." }
                    hashes.getOrPut(file.path) { hashSelectedSource(file) }.also {
                        require(sourceStamp(file) == expected) { "Source changed during the memory hash." }
                    }
                }
                ReaderTranslationSource(page.index, file?.path, hash)
            }
        } catch (_: java.io.IOException) { return@withContext null }
          catch (_: IllegalArgumentException) { return@withContext null }
        val identity = memorySourceIdentity(chapter.id, sourceRows)
        val configIdentity = native.memoryConfigurationIdentity(base)
        val proofs = HashMap<Pair<String, Int>, NativeMemoryPageProof?>()
        memory.captureGeneration(chapter.id, identity, base.targetLanguage, configIdentity, firstPage, MAX_ARTIFACT_BYTES - sourceBytes) { snapshot, bubble ->
            val receipt = bubble.receipt
            if (receipt.nativeAuthorityVersion != 1) null else {
                val key = receipt.taskId + ":" + receipt.generation to receipt.source.pageIndex
                if (!proofs.containsKey(key)) proofs[key] =
                    native.get(receipt.taskId)?.takeIf { it.generation == receipt.generation && !it.validationPending && it.config.copy(memoryPacket = null) == base }
                        ?.let { task -> prepareProof(ReaderTranslationPresentation.receipt(task), receipt.source.pageIndex) }
                val proof = proofs[key]
                val index = proof?.takeIf { nativeReceiptMatches(receipt, it, native.memoryConfigurationIdentity(it.task.config)) }
                    ?.page?.lettering?.indexOfFirst { it.source == receipt.originalOcr && it.translated == receipt.originalTranslation &&
                        it.originalSourceBounds?.let { bounds -> MemoryRegionBounds(bounds.left, bounds.top, bounds.right, bounds.bottom) } == receipt.source.bounds }
                    ?.takeIf { it >= 0 }
                if (proof == null || index == null) null else {
                    val accepted = PersonalMemoryOverlayPolicy.project(proof, native.memoryConfigurationIdentity(proof.task.config), snapshot)[index]
                    // Invalid personal target data cannot become generation context; its native baseline remains eligible.
                    runCatching {
                        var captured: MemoryIndexedBubble? = null
                        native.commitMemoryPublication(ReaderTranslationPresentation.receipt(proof.task), proof) {
                            captured = if (bubble.correction == null || accepted != null) bubble else bubble.copy(correction = null)
                        }
                        captured
                    }.getOrNull()
                }
            }
        }
    }
    private fun sourceStamp(file: File): NativeMemoryFileStamp {
        val attributes = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        require(attributes.isRegularFile)
        return NativeMemoryFileStamp(file.path, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime())
    }
    private companion object { const val MAX_ARTIFACT_BYTES = 64L * 1024 * 1024 }
}

internal fun memorySourceIdentity(chapterId: String, rows: List<ReaderTranslationSource>): String = TranslationRefinementPolicy.hash(
    (listOf("chapter-memory-source-v1", chapterId, rows.size.toString()) + rows.sortedBy { it.index }.flatMap {
        listOf(it.index.toString(), it.path.orEmpty(), it.sha256.orEmpty())
    }).joinToString("") { value -> "${value.toByteArray(Charsets.UTF_8).size}:$value" })

internal fun nativeReceiptMatches(receipt: MemoryPublicationReceipt, proof: NativeMemoryPageProof, configurationIdentity: String): Boolean {
    val task = proof.task; val page = proof.page
    return receipt.nativeAuthorityVersion == 1 && receipt.taskId == task.id && receipt.generation == task.generation &&
        receipt.ownerRequestId == task.ownerRequestId && receipt.source.chapterId == task.chapterId && receipt.source.pageIndex == page.index &&
        receipt.targetLanguage == task.config.targetLanguage && receipt.configurationIdentity == configurationIdentity &&
        receipt.source.sourcePath == page.sourcePath && receipt.source.sourceSha256 == page.sourceSha256 &&
        receipt.outputPath == page.cleanedPath && receipt.outputSha256 == page.cleanedSha256 &&
        receipt.source.imageWidth == page.originalWidth && receipt.source.imageHeight == page.originalHeight &&
        page.lettering.count { text -> text.source == receipt.originalOcr && text.translated == receipt.originalTranslation &&
            text.originalSourceBounds?.let { bounds -> MemoryRegionBounds(bounds.left, bounds.top, bounds.right, bounds.bottom) } == receipt.source.bounds } == 1
}
