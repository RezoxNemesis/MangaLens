package com.mangalens.core.translation

/** Hindi meaning/register is checked before romanization; final output has a separate script gate. */
internal object HinglishTranslationOutput {
    fun fromHindi(source: String, hindiDraft: String): String = fromHindiDraft(source, hindiDraft).text

    fun fromHindiDraft(source: String, hindiDraft: String): TranslationDraft {
        val hindi = TranslationQualityPolicy.hindiDraftForRomanOutput(source, hindiDraft)
        val proof = hindi.takeIf { it.any { char -> char in '\u0900'..'\u097f' && char.isLetter() } }
        return TranslationQualityPolicy.chooseDraft(source, TranslationDraft(HindiRomanization.render(hindi, source), proof), "", "hi-latn")
    }

    fun alreadyRoman(source: String): String = TranslationQualityPolicy.choose(source, source, "", "hi-latn")
}
