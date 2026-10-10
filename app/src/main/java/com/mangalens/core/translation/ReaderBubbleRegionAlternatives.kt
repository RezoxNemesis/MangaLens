package com.mangalens.core.translation

import com.mangalens.core.compute.NativeComputePrecondition
import com.mangalens.engine.OcrBox

internal enum class ReaderBubbleRegionAction { RETRY_ORIGINAL_OCR, REGENERATE_TRANSLATION }
internal enum class ReaderBubbleAlternativeKind { ORIGINAL_OCR, ON_DEVICE_DRAFT, PINNED_LOCAL_REFINEMENT }
internal data class ReaderBubbleRegionAlternative(val id: String, val kind: ReaderBubbleAlternativeKind,
    val sourceText: String, val translated: TranslationDraft? = null, val originalBounds: OcrBox? = null,
    val recognizerScript: String? = null, val pixelVariant: String? = null)
internal data class ReaderBubbleRegionResult(val alternatives: List<ReaderBubbleRegionAlternative>, val message: String? = null)
internal typealias SavedBubbleRegionActions = suspend (ReaderBubbleInspection, ReaderBubbleRegionAction, String, NativeComputePrecondition) -> ReaderBubbleRegionResult

/** These values are unsaved candidates. The original native receipt still owns the editor. */
internal object ReaderBubbleAlternativeInputPolicy {
    fun edit(alternative: ReaderBubbleRegionAlternative): com.mangalens.core.translation.memory.MemoryCorrectionEdit {
        require(alternative.sourceText.isNotBlank() && alternative.sourceText.length <= 4096)
        alternative.translated?.let {
            require(it.text.isNotBlank() && it.text.length <= 4096)
            require(it.hindiDraft == null || it.hindiDraft.length <= 4096)
        }
        return com.mangalens.core.translation.memory.MemoryCorrectionEdit(correctedOcr = alternative.sourceText,
            translated = alternative.translated?.text, hindiDraft = alternative.translated?.hindiDraft)
    }
}

/** Form presets never mutate the actual saved correction or history. */
internal object ReaderBubbleAlternativeFormPolicy {
    fun preset(actual: PersonalMemoryEditorValues, edit: com.mangalens.core.translation.memory.MemoryCorrectionEdit): PersonalMemoryEditorValues =
        PersonalMemoryEditorValues(edit.correctedOcr ?: actual.ocr, edit.translated ?: actual.translated,
            if (edit.translated != null) edit.hindiDraft.orEmpty() else actual.hindiDraft)
}
