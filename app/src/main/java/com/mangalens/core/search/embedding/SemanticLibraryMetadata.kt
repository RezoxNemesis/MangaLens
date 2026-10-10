package com.mangalens.core.search.embedding

import com.mangalens.core.reader.SavedChapter
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Identity is actual Library metadata, never page/OCR/native publication authority. */
internal data class SemanticLibraryMetadataEntry(val id: String, val title: String, val series: String,
    val notes: String, val sourceUrl: String, val addedAt: Long, val sourceSha256: String,
    val textSha256: String, val text: String, val bodyTruncated: Boolean) {
    val cacheKey: String get() = SemanticModelArtifactStore.sha256((SemanticEmbeddingPin.cachePin + ":" + sourceSha256 + ":" + textSha256).toByteArray(Charsets.UTF_8))
    fun matchesCurrent(chapter: SavedChapter): Boolean = chapter.id == id && chapter.title == title && chapter.seriesTitle == series &&
        chapter.notes == notes && chapter.sourceUrl == sourceUrl && chapter.addedAt == addedAt
}
internal data class SemanticLibraryMetadataSnapshot(val entries: List<SemanticLibraryMetadataEntry>, val omitted: Int, val inventoryLimited: Boolean)
internal object SemanticLibraryMetadata {
    const val CANDIDATES = 1_024
    private const val METADATA_BYTES = 16L * 1024 * 1024
    fun query(value: String): String = value.trim().also {
        require(it.length in 1..160 && it.none { character -> character.code < 32 || character.code == 127 }) { "Enter an English phrase of 1–160 characters." }
        require(englishCandidate(it)) { "Use an English phrase for semantic search; ordinary search supports other scripts." }
    }
    fun current(library: List<SavedChapter>, entry: SemanticLibraryMetadataEntry): Boolean =
        library.take(CANDIDATES).singleOrNull { it.id == entry.id }?.let(entry::matchesCurrent) == true
    fun snapshot(library: List<SavedChapter>, checkpoint: () -> Unit = {}): SemanticLibraryMetadataSnapshot {
        val entries = arrayListOf<SemanticLibraryMetadataEntry>(); var received = 0L; var omitted = 0
        var limited = library.size > CANDIDATES
        for (chapter in library.take(CANDIDATES)) {
            checkpoint()
            if (!chapter.id.matches(Regex("[a-f0-9]{32}")) || chapter.title.length > 2_048 || chapter.seriesTitle.length > 2_048 || chapter.notes.length > 8_192 ||
                chapter.sourceUrl.length > 8_192 || chapter.addedAt < 0 || listOf(chapter.title, chapter.seriesTitle, chapter.notes, chapter.sourceUrl).any { '\u0000' in it }) {
                omitted++; limited = true; continue
            }
            val raw = chapter.title + "\nSeries: " + chapter.seriesTitle + "\nNotes: " + chapter.notes
            if (!englishCandidate(chapter.title + " " + chapter.seriesTitle + " " + chapter.notes)) { omitted++; continue }
            val sourceBytes = fields("chapter-library-metadata-v1", chapter.id, chapter.sourceUrl, chapter.addedAt.toString())
            val textBytes = raw.toByteArray(Charsets.UTF_8)
            val bytes = sourceBytes.size.toLong() + textBytes.size
            if (received + bytes > METADATA_BYTES) { omitted++; limited = true; continue }
            received += bytes
            var end = minOf(raw.length, SemanticEmbeddingPin.MAX_INPUT_CHARS)
            if (end < raw.length && end > 0 && raw[end - 1].isHighSurrogate() && raw[end].isLowSurrogate()) end--
            entries += SemanticLibraryMetadataEntry(chapter.id, chapter.title, chapter.seriesTitle, chapter.notes, chapter.sourceUrl, chapter.addedAt,
                SemanticModelArtifactStore.sha256(sourceBytes), SemanticModelArtifactStore.sha256(textBytes), raw.substring(0, end), end < raw.length)
        }
        require(entries.map { it.id }.distinct().size == entries.size)
        return SemanticLibraryMetadataSnapshot(entries.toList(), omitted, limited)
    }
    private fun fields(vararg values: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output -> values.forEach { value -> val encoded = value.toByteArray(Charsets.UTF_8); output.writeInt(encoded.size); output.write(encoded) } }
    }.toByteArray()
    private fun englishCandidate(text: String): Boolean = text.codePoints().anyMatch { Character.isLetter(it) && Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN }
}
