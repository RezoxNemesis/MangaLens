package com.mangalens.core.translation

/** Hindi meaning/register is checked before romanization; final output has a separate script gate. */
internal object HinglishTranslationOutput {
    fun fromHindi(source: String, hindiDraft: String, style: TranslationStyleProfile? = null): String = fromHindiDraft(source, hindiDraft, style).text

    fun fromHindiDraft(source: String, hindiDraft: String, style: TranslationStyleProfile? = null): TranslationDraft {
        val hindi = TranslationQualityPolicy.hindiDraftForRomanOutput(source, hindiDraft, style)
        val proof = hindi.takeIf { it.any { char -> char in '\u0900'..'\u097f' && char.isLetter() } }
        return TranslationQualityPolicy.chooseDraft(source, TranslationDraft(HindiRomanization.render(hindi, source), proof), "", "hi-latn", style)
    }

    fun alreadyRoman(source: String): String = TranslationQualityPolicy.choose(source, source, "", "hi-latn")
}
