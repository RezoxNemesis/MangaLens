package com.mangalens.core.translation.memory

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** All coordinates refer to the original source image, never a viewport or translated crop. */
data class MemoryRegionBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun validate(width: Int, height: Int) {
        require(width in 1..100_000 && height in 1..100_000 && width.toLong() * height <= 100_000_000L)
        require(left >= 0 && top >= 0 && right > left && bottom > top && right <= width && bottom <= height) {
            "The selected region no longer matches its source page."
        }
    }
}

data class MemorySourceProof(
    val chapterId: String,
    val pageIndex: Int,
    val sourcePath: String,
    val sourceSha256: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val bounds: MemoryRegionBounds
) {
    fun validate() {
        require(memoryValidId(chapterId) && pageIndex in 0 until 2_000)
        require(sourcePath.isNotBlank() && memoryValidHash(sourceSha256))
        bounds.validate(imageWidth, imageHeight)
    }
}

/** The editor captures this receipt and supplies a freshly validated current receipt when saving. */
data class MemoryPublicationReceipt(
    val source: MemorySourceProof,
    val taskId: String,
    val generation: String,
    val targetLanguage: String,
    val configurationIdentity: String,
    val originalOcr: String,
    val originalTranslation: String? = null,
    val outputPath: String? = null,
    val outputSha256: String? = null,
    val seriesId: String? = null,
    val nativeAuthorityVersion: Int = 0,
    val ownerRequestId: String? = null,
    val presentationEpoch: Long? = null,
    val associationRevision: Long? = null
) {
    fun validate() {
        source.validate()
        require(nativeAuthorityVersion in 0..1)
        require(ownerRequestId == null || ownerRequestId.isNotBlank() && ownerRequestId.length <= 160 && ownerRequestId.none { it.isISOControl() })
        require(associationRevision == null || associationRevision >= 0)
        require(presentationEpoch == null || presentationEpoch > 0)
        if (nativeAuthorityVersion == 1) require(presentationEpoch != null && associationRevision != null)
        require(seriesId == null || memoryValidId(seriesId))
        require(memoryValidId(taskId) && memoryValidId(generation))
        require(targetLanguage.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,31}")))
        require(configurationIdentity.length in 1..2_048 && !configurationIdentity.contains('\u0000'))
        memoryValidateText(originalOcr)
        originalTranslation?.let(::memoryValidateText)
        require((outputPath == null) == (outputSha256 == null))
        if (outputPath != null) require(outputPath.isNotBlank() && memoryValidHash(outputSha256!!))
    }

    /** Generation/output changes do not change the source-bound correction's durable identity. */
    val bubbleId: String get() = memoryHash(listOf(source.chapterId, source.pageIndex.toString(), source.sourceSha256,
        source.imageWidth.toString(), source.imageHeight.toString(), source.bounds.left.toString(), source.bounds.top.toString(),
        source.bounds.right.toString(), source.bounds.bottom.toString(), targetLanguage.lowercase(Locale.ROOT), configurationIdentity, seriesId ?: "chapter-only"))
}

data class MemoryCorrectionEdit(val correctedOcr: String? = null, val translated: String? = null, val hindiDraft: String? = null) {
    fun validate() {
        correctedOcr?.let(::memoryValidateText)
        translated?.let(::memoryValidateText)
        hindiDraft?.let(::memoryValidateText)
        require(hindiDraft == null || translated != null) { "Hindi evidence must accompany a translated correction." }
    }
}

data class MemoryCorrectionRevision(val revision: Int, val edit: MemoryCorrectionEdit, val editedAt: Long)

data class MemoryCorrection(val original: MemoryPublicationReceipt, val revisions: List<MemoryCorrectionRevision>) {
    val revision: Int get() = revisions.lastOrNull()?.revision ?: 0
    val edit: MemoryCorrectionEdit get() = revisions.lastOrNull()?.edit ?: MemoryCorrectionEdit()
}

/** The edit version survives explicit history removal so held editors cannot revive it. */
data class MemoryIndexedBubble(val receipt: MemoryPublicationReceipt, val correction: MemoryCorrection? = null,
    val editRevision: Int = correction?.revision ?: 0) {
    val sourceText: String get() = correction?.edit?.correctedOcr ?: receipt.originalOcr
    val translatedText: String? get() = correction?.edit?.translated ?: receipt.originalTranslation
}

data class MemoryChapterAssociation(val chapterId: String, val seriesId: String, val ordinal: Int?) {
    fun validate() {
        require(memoryValidId(chapterId) && memoryValidId(seriesId))
        require(ordinal == null || ordinal in 0..1_000_000)
    }
}

data class MemoryLocation(val chapterId: String, val pageIndex: Int) {
    fun validate() { require(memoryValidId(chapterId) && pageIndex in 0 until 2_000) }
}

enum class MemoryTermKind { NAME, ALIAS, PLACE, ORGANIZATION, POWER, ATTACK, HONORIFIC, PHRASE, CUSTOM }

data class SeriesGlossaryTerm(
    val id: String,
    val source: String,
    val preferred: String,
    val targetLanguage: String,
    val kind: MemoryTermKind = MemoryTermKind.NAME,
    val aliases: List<String> = emptyList(),
    val origin: MemoryLocation? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val originSourceSha256: String? = null
) {
    fun validate() {
        require(memoryValidId(id) && updatedAt >= 0)
        require(source.isNotBlank() && source.length <= 256 && preferred.isNotBlank() && preferred.length <= 256)
        require(targetLanguage.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,31}")))
        require(aliases.size <= 16 && aliases.all { it.isNotBlank() && it.length <= 256 })
        require(listOf(source, preferred).plus(aliases).none { it.contains('\u0000') })
        origin?.validate()
        require(originSourceSha256 == null || (origin != null && memoryValidHash(originSourceSha256)))
    }
}

data class SeriesStylePreference(val styleId: String, val targetLanguage: String, val customInstructions: String = "") {
    fun validate() {
        require(styleId.length in 1..64 && targetLanguage.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,31}")))
        require(customInstructions.length <= 2_048 && !customInstructions.contains('\u0000'))
    }
}

data class SeriesMemoryProfile(val id: String, val title: String, val glossary: List<SeriesGlossaryTerm> = emptyList(),
    val style: SeriesStylePreference? = null, val removed: Boolean = false) {
    fun validate() {
        require(memoryValidId(id) && title.isNotBlank() && title.length <= 256 && !title.contains('\u0000'))
        require(glossary.size <= 512 && glossary.map { it.id }.distinct().size == glossary.size)
        glossary.forEach { it.validate() }; style?.validate()
    }
}

data class MemoryRetrievalRequest(val chapterId: String, val pageIndex: Int, val sourceText: String,
    val targetLanguage: String, val configurationIdentity: String)

enum class MemorySearchKind { OCR, TRANSLATION, CORRECTED_OCR, CORRECTED_TRANSLATION }

data class MemorySearchHit(val bubbleId: String, val source: MemorySourceProof, val seriesId: String?,
    val kind: MemorySearchKind, val text: String, val revision: Int, val targetLanguage: String,
    val configurationIdentity: String)

data class RelevantSeriesMemory(val seriesId: String?, val glossary: Map<String, String>, val priorDialogue: List<MemorySearchHit>)

internal data class MemoryChapterJournal(val chapterId: String, val association: MemoryChapterAssociation? = null,
    val bubbles: List<MemoryIndexedBubble> = emptyList(), val removed: Boolean = false, val associationRevision: Long = 0)

internal fun memoryValidId(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,128}"))
internal fun memoryValidHash(value: String) = value.matches(Regex("[a-f0-9]{64}"))
internal fun memoryValidateText(value: String) {
    require(value.isNotBlank() && value.length <= 4_096 && !value.contains('\u0000')) { "Memory text must be between 1 and 4096 characters." }
}
internal fun memoryTextKey(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
internal fun memoryHash(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update((bytes.size.toString() + ":").toByteArray(Charsets.US_ASCII)); digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
