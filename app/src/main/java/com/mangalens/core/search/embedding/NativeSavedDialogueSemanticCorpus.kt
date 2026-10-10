package com.mangalens.core.search.embedding

import com.mangalens.core.translation.*
import com.mangalens.core.translation.memory.MemorySearchKind
import com.mangalens.orez.agent.OrezChapterSource
import com.mangalens.orez.agent.OrezChapterSourceEvidence
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Derived selectors carry no native/Reader/editor authority. Fresh read proof is always required. */
internal data class NativeSemanticField(val hint: NativeLexicalHint, val sourceSha256: String,
    val textSha256: String, val input: String, val bodyTruncated: Boolean) {
    val key: String get() = SemanticModelArtifactStore.sha256((SemanticEmbeddingPin.cachePin + ":" + sourceSha256 + ":" + textSha256).toByteArray(Charsets.UTF_8))
    fun matches(row: NativeIndexedSavedTextRow): Boolean = row.warm.task === hint.task && row.proof.page.index == hint.pageIndex &&
        row.letteringIndex == hint.letteringIndex && row.kind == hint.kind && row.text == hint.text
}
internal data class NativeSemanticCatalogue(val fields: List<NativeSemanticField>, val visits: Int,
    val checkedFields: Int, val omitted: Int, val invalidTasks: Int, val receivedBytes: Long, val inventoryLimited: Boolean)

internal object NativeSavedDialogueSemanticCorpus {
    const val CANDIDATES = 1_024
    const val VISITS = 4_096
    const val TEXT_BYTES = 8L * 1024 * 1024
    fun snapshot(tasks: List<ChapterTranslationTask>, configuration: (ChapterTranslationConfig) -> String,
        checkpoint: () -> Unit = {}): NativeSemanticCatalogue {
        val result = arrayListOf<NativeSemanticField>(); var visits = 0; var checked = 0; var omitted = 0; var invalidTasks = 0; var bytes = 0L; var limited = false
        outer@ for (task in tasks.take(64)) {
            checkpoint()
            val fingerprint = try { OrezChapterSourceEvidence.snapshot(task.chapterId, task.title,
                task.pages.map { OrezChapterSource(it.index, it.sourcePath, it.sourceSha256) }).sourceFingerprint }
            catch (_: Exception) { invalidTasks++; limited = true; continue }
            val config = configuration(task.config)
            for (page in task.pages) {
                checkpoint()
                if (++visits > VISITS) { visits = VISITS; limited = true; break@outer }
                if (page.status !in setOf(ChapterTranslationPageStatus.COMPLETED, ChapterTranslationPageStatus.PARTIAL)) continue
                for ((ordinal, letter) in page.lettering.withIndex()) for ((kind, text) in listOf(
                    MemorySearchKind.OCR to letter.source, MemorySearchKind.TRANSLATION to letter.translated)) {
                    checkpoint()
                    if (++visits > VISITS || result.size == CANDIDATES) { visits = minOf(visits, VISITS); limited = true; break@outer }
                    checked++
                    if (text.isBlank() || text.length > 8_000 || '\u0000' in text || !englishCandidate(text)) { omitted++; continue }
                    val identity = fields("native-saved-dialogue-v1", task.chapterId, task.id, task.generation, task.ownerRequestId.orEmpty(),
                        config, fingerprint, page.index.toString(), ordinal.toString(), kind.name,
                        page.sourcePath.orEmpty(), page.sourceSha256.orEmpty(), page.cleanedPath.orEmpty(), page.cleanedSha256.orEmpty())
                    val encoded = text.toByteArray(Charsets.UTF_8)
                    val received = identity.size.toLong() + encoded.size
                    if (bytes + received > TEXT_BYTES) { limited = true; break@outer }
                    bytes += received
                    var end = minOf(text.length, SemanticEmbeddingPin.MAX_INPUT_CHARS)
                    if (end < text.length && end > 0 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                    result += NativeSemanticField(NativeLexicalHint(task, page.index, ordinal, kind, text),
                        SemanticModelArtifactStore.sha256(identity), SemanticModelArtifactStore.sha256(encoded), text.substring(0, end), end < text.length)
                }
            }
        }
        checkpoint()
        require(result.map { it.key }.distinct().size == result.size)
        return NativeSemanticCatalogue(result.toList(), visits, checked, omitted, invalidTasks, bytes, limited || tasks.size > 64)
    }
    private fun englishCandidate(text: String): Boolean = text.codePoints().anyMatch {
        Character.isLetter(it) && Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN
    }
    private fun fields(vararg values: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output -> values.forEach { value -> val encoded = value.toByteArray(Charsets.UTF_8); output.writeInt(encoded.size); output.write(encoded) } }
    }.toByteArray()
}
