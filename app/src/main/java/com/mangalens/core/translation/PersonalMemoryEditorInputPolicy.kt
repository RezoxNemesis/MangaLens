package com.mangalens.core.translation

import com.mangalens.core.translation.memory.MemoryCorrectionEdit
import com.mangalens.core.translation.memory.MemoryPublicationReceipt

internal data class PersonalMemoryEditorValues(val ocr: String, val translated: String, val hindiDraft: String)

/** Form values preserve actual native/edit evidence; the existing save policy validates every pair. */
internal object PersonalMemoryEditorInputPolicy {
    fun initial(receipt: MemoryPublicationReceipt, savedHindiDraft: String?, edit: MemoryCorrectionEdit?): PersonalMemoryEditorValues =
        PersonalMemoryEditorValues(
            edit?.correctedOcr ?: receipt.originalOcr,
            edit?.translated ?: receipt.originalTranslation.orEmpty(),
            if (!HindiRomanization.isTarget(receipt.targetLanguage)) ""
            else if (edit?.translated != null) edit.hindiDraft.orEmpty()
            else savedHindiDraft.orEmpty()
        )

    fun changed(initial: PersonalMemoryEditorValues, current: PersonalMemoryEditorValues, targetLanguage: String): Boolean =
        current.ocr != initial.ocr || current.translated != initial.translated ||
            HindiRomanization.isTarget(targetLanguage) && current.hindiDraft != initial.hindiDraft

    fun edit(receipt: MemoryPublicationReceipt, savedHindiDraft: String?, current: PersonalMemoryEditorValues): MemoryCorrectionEdit {
        val romanHindi = HindiRomanization.isTarget(receipt.targetLanguage)
        val explicitTranslation = current.translated != receipt.originalTranslation ||
            romanHindi && current.hindiDraft != savedHindiDraft.orEmpty()
        return MemoryCorrectionEdit(
            correctedOcr = current.ocr.takeUnless { it == receipt.originalOcr },
            translated = current.translated.takeIf { explicitTranslation },
            hindiDraft = current.hindiDraft.takeIf { romanHindi && explicitTranslation && it.isNotBlank() }
        )
    }
}
