package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*

data class PersonalMangaLettering(val original: SavedMangaLettering, val personal: SavedMangaLettering, val revision: Int)

/** The caller supplies a freshly verified native page, never reconstructed viewport geometry. */
internal object PersonalMemoryOverlayPolicy {
    fun project(proof: NativeMemoryPageProof, configurationIdentity: String, chapter: MemoryChapterSnapshot): Map<Int, PersonalMangaLettering> {
        val page = proof.page; val task = proof.task
        if (chapter.removed || chapter.chapterId != task.chapterId || page.originalWidth == null || page.originalHeight == null) return emptyMap()
        return page.lettering.mapIndexedNotNull { index, native ->
            val bounds = native.originalSourceBounds ?: return@mapIndexedNotNull null
            val matching = chapter.bubbles.filter { bubble ->
                val receipt = bubble.receipt; val source = receipt.source
                receipt.nativeAuthorityVersion == 1 && receipt.taskId == task.id && receipt.generation == task.generation &&
                    receipt.ownerRequestId == task.ownerRequestId && receipt.targetLanguage == task.config.targetLanguage &&
                    receipt.configurationIdentity == configurationIdentity && receipt.seriesId == chapter.association?.seriesId &&
                    receipt.associationRevision == chapter.associationRevision && receipt.originalOcr == native.source &&
                    receipt.originalTranslation == native.translated && receipt.outputPath == page.cleanedPath && receipt.outputSha256 == page.cleanedSha256 &&
                    source.chapterId == task.chapterId && source.pageIndex == page.index && source.sourcePath == page.sourcePath &&
                    source.sourceSha256 == page.sourceSha256 && source.imageWidth == page.originalWidth && source.imageHeight == page.originalHeight &&
                    source.bounds == MemoryRegionBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
            val bubble = matching.singleOrNull() ?: return@mapIndexedNotNull null
            val correction = bubble.correction ?: return@mapIndexedNotNull null
            val edit = correction.edit
            if (edit == MemoryCorrectionEdit()) return@mapIndexedNotNull null
            val source = edit.correctedOcr ?: native.source
            val translated = edit.translated ?: native.translated
            val hindiDraft = if (edit.translated == null) native.savedHindiDraft else edit.hindiDraft
            if (!TranslationQualityPolicy.isUsable(source, translated, task.config.targetLanguage, hindiDraft)) return@mapIndexedNotNull null
            index to PersonalMangaLettering(native, native.copy(source = source, translated = translated, savedHindiDraft = hindiDraft), bubble.editRevision)
        }.toMap()
    }

    fun validateEdit(receipt: MemoryPublicationReceipt, edit: MemoryCorrectionEdit, savedHindiDraft: String?) {
        edit.validate()
        val translated = edit.translated ?: receipt.originalTranslation ?: error("This bubble has no saved translation.")
        require(TranslationQualityPolicy.isUsable(edit.correctedOcr ?: receipt.originalOcr, translated, receipt.targetLanguage,
            if (edit.translated == null) savedHindiDraft else edit.hindiDraft)) {
            "The correction does not match the selected target language. Check the translation and its Hindi draft."
        }
    }
}

internal fun parseMemoryChapterOrdinal(value: String): Int? {
    if (value.isBlank()) return null
    return value.toIntOrNull()?.takeIf { it in 0..1_000_000 }
        ?: throw IllegalArgumentException("Use a chapter order from 0 to 1000000, or leave it blank when unknown.")
}
