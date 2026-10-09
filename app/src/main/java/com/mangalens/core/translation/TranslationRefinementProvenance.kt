package com.mangalens.core.translation

import com.mangalens.orez.OrezModelPin
import com.mangalens.orez.OrezPinnedModelPolicy
import java.security.MessageDigest

data class TranslationRefinementRequest(
    val enabled: Boolean,
    val style: TranslationStyleProfile = TranslationStyleProfile.NATURAL,
    val pinnedModel: OrezModelPin? = null
)

enum class TranslationRefinementStatus { DISABLED, UNAVAILABLE, TIMED_OUT, GENERATED }

data class TranslationRefinementReceipt(val model: OrezModelPin, val promptSha256: String, val outputSha256: String)

data class TranslationRefinementResult(
    val text: String,
    val receipt: TranslationRefinementReceipt? = null,
    val status: TranslationRefinementStatus
)

/** Inference provenance only. A matching receipt never replaces independent translation-quality gates. */
object TranslationRefinementPolicy {
    fun matches(result: TranslationRefinementResult, source: String, translated: String, targetLanguage: String,
        request: TranslationRefinementRequest, chapterContext: String = "", glossary: Map<String, String> = emptyMap()): Boolean {
        val receipt = result.receipt ?: return false
        val model = request.pinnedModel ?: return false
        if (!request.enabled || result.status != TranslationRefinementStatus.GENERATED ||
            receipt.model != model || !OrezPinnedModelPolicy.valid(model) ||
            result.text.isBlank() || result.text.length > 6000 ||
            !validInputs(source, translated, targetLanguage, request.style, chapterContext, glossary)) return false
        return receipt.promptSha256 == hash(prompt(source, translated, targetLanguage, request.style, chapterContext, glossary)) &&
            receipt.outputSha256 == hash(result.text)
    }

    internal fun validInputs(source: String, translated: String, targetLanguage: String, style: TranslationStyleProfile,
        chapterContext: String, glossary: Map<String, String>): Boolean = source.isNotBlank() && source.length <= 4000 &&
        translated.isNotBlank() && translated.length <= 4000 && targetLanguage.matches(Regex("[a-zA-Z0-9-]{2,48}")) &&
        style.id.length in 1..64 && style.name.length in 1..128 && style.instruction.isNotBlank() &&
        style.instruction.length <= TranslationStyleProfile.MAX_CUSTOM_INSTRUCTION_CHARS && chapterContext.length <= 4500 &&
        glossary.size <= 20 && glossary.all { (source, target) -> source.length in 1..256 && target.length in 1..256 } &&
        prompt(source, translated, targetLanguage, style, chapterContext, glossary).length <= 12000

    internal fun prompt(source: String, translated: String, targetLanguage: String, style: TranslationStyleProfile,
        chapterContext: String, glossary: Map<String, String>): String = "STYLE PROFILE ID: ${style.id}\n" +
        buildTranslationRefinementPrompt(source, translated, targetLanguage, style, chapterContext, glossary)

    internal fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
