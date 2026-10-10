package com.mangalens.core.translation

import com.mangalens.orez.OrezModelPin
import com.mangalens.orez.OrezPinnedModelPolicy
import com.mangalens.orez.OrezGenerationCompletion
import com.mangalens.orez.OrezLocalizationProfile
import java.security.MessageDigest

data class TranslationRefinementRequest @JvmOverloads constructor(
    val enabled: Boolean,
    val style: TranslationStyleProfile = TranslationStyleProfile.NATURAL,
    val pinnedModel: OrezModelPin? = null,
    /** Null identifies historical work; it never selects the current template. */
    val inputProfileRevision: String? = null
)

enum class TranslationRefinementStatus { DISABLED, UNAVAILABLE, TIMED_OUT, GENERATED }

data class TranslationRefinementReceipt @JvmOverloads constructor(val model: OrezModelPin, val promptSha256: String,
    val outputSha256: String, val completion: OrezGenerationCompletion? = null)

data class TranslationRefinementResult(
    val text: String,
    val receipt: TranslationRefinementReceipt? = null,
    val status: TranslationRefinementStatus
)

/** Inference provenance only. A matching receipt never replaces independent translation-quality gates. */
object TranslationRefinementPolicy {
    const val INPUT_PROFILE_VERSION = OrezLocalizationProfile.REVISION
    fun matches(result: TranslationRefinementResult, source: String, translated: String, targetLanguage: String,
        request: TranslationRefinementRequest, chapterContext: String = "", glossary: Map<String, String> = emptyMap(), memoryPacketSha256: String? = null): Boolean {
        val receipt = result.receipt ?: return false
        val model = request.pinnedModel ?: return false
        if (!request.enabled || result.status != TranslationRefinementStatus.GENERATED ||
            receipt.model != model || !OrezPinnedModelPolicy.valid(model) || result.text.isBlank() || result.text.length > 6000 ||
            !validFields(source, translated, targetLanguage, request.style, chapterContext, glossary, memoryPacketSha256)) return false
        val completion = receipt.completion
        val prompt = if (completion == null) {
            // Only exact pre-profile historical receipts can omit native completion evidence.
            if (request.inputProfileRevision != null || memoryPacketSha256 != null) return false
            legacyPrompt(source, translated, targetLanguage, request.style, chapterContext, glossary)
        } else {
            // Historical typed v2 receipts stay readable, but a receipt cannot upgrade a captured job.
            val revision = request.inputProfileRevision ?: completion.profileRevision.takeIf {
                it == com.mangalens.orez.OrezLocalizationV2.REVISION
            } ?: return false
            if (!OrezLocalizationProfile.supported(revision)) return false
            val captured = promptForRevision(source, translated, targetLanguage, request.style, chapterContext, glossary,
                memoryPacketSha256, revision)
            if (!completion.completedLocalization(captured, revision)) return false
            captured
        }
        return prompt.length <= 12000 && receipt.promptSha256 == hash(prompt) && receipt.outputSha256 == hash(result.text)
    }

    internal fun generationReady(request: TranslationRefinementRequest): Boolean = request.enabled &&
        request.pinnedModel?.let(OrezPinnedModelPolicy::valid) == true && OrezLocalizationProfile.supported(request.inputProfileRevision)

    internal fun validCapturedInputs(source: String, translated: String, targetLanguage: String, request: TranslationRefinementRequest,
        chapterContext: String, glossary: Map<String, String>, memoryPacketSha256: String? = null): Boolean = generationReady(request) &&
        validFields(source, translated, targetLanguage, request.style, chapterContext, glossary, memoryPacketSha256) &&
        capturedPrompt(source, translated, targetLanguage, request, chapterContext, glossary, memoryPacketSha256).length <= 12000

    internal fun capturedPrompt(source: String, translated: String, targetLanguage: String, request: TranslationRefinementRequest,
        chapterContext: String, glossary: Map<String, String>, memoryPacketSha256: String? = null): String =
        promptForRevision(source, translated, targetLanguage, request.style, chapterContext, glossary, memoryPacketSha256,
            requireNotNull(request.inputProfileRevision) { "This older task has no captured localization input version; start a new request explicitly." })

    internal fun validInputs(source: String, translated: String, targetLanguage: String, style: TranslationStyleProfile,
        chapterContext: String, glossary: Map<String, String>, memoryPacketSha256: String? = null): Boolean =
        validFields(source, translated, targetLanguage, style, chapterContext, glossary, memoryPacketSha256) &&
        prompt(source, translated, targetLanguage, style, chapterContext, glossary, memoryPacketSha256).length <= 12000

    private fun validFields(source: String, translated: String, targetLanguage: String, style: TranslationStyleProfile,
        chapterContext: String, glossary: Map<String, String>, memoryPacketSha256: String?): Boolean =
        (memoryPacketSha256 == null || memoryPacketSha256.matches(Regex("[a-f0-9]{64}"))) && source.isNotBlank() && source.length <= 4000 &&
        translated.isNotBlank() && translated.length <= 4000 && targetLanguage.matches(Regex("[a-zA-Z0-9-]{2,48}")) &&
        style.id.length in 1..64 && style.name.length in 1..128 && style.instruction.isNotBlank() &&
        style.instruction.length <= TranslationStyleProfile.MAX_CUSTOM_INSTRUCTION_CHARS && chapterContext.length <= 4500 &&
        glossary.size <= 20 && glossary.all { (source, target) -> source.length in 1..256 && target.length in 1..256 }

    /** Current fixture producer; queued generation uses capturedPrompt instead. */
    internal fun prompt(source: String, translated: String, targetLanguage: String, style: TranslationStyleProfile,
        chapterContext: String, glossary: Map<String, String>, memoryPacketSha256: String? = null): String =
        promptForRevision(source, translated, targetLanguage, style, chapterContext, glossary, memoryPacketSha256, INPUT_PROFILE_VERSION)

    private fun promptForRevision(source: String, translated: String, targetLanguage: String, style: TranslationStyleProfile,
        chapterContext: String, glossary: Map<String, String>, memoryPacketSha256: String?, revision: String): String = when (revision) {
        com.mangalens.orez.OrezLocalizationV2.REVISION -> "LOCALIZATION PROFILE: ${com.mangalens.orez.OrezLocalizationV2.REVISION}\n" +
            (memoryPacketSha256?.let { "CAPTURED SERIES MEMORY SHA-256: $it\n" } ?: "") +
            legacyPrompt(source, translated, targetLanguage, style, chapterContext, glossary)
        else -> error("The captured localization input version is unsupported; start a new request explicitly.")
    }

    private fun legacyPrompt(source: String, translated: String, targetLanguage: String, style: TranslationStyleProfile,
        chapterContext: String, glossary: Map<String, String>): String = "STYLE PROFILE ID: ${style.id}\n" +
        buildTranslationRefinementPrompt(source, translated, targetLanguage, style, chapterContext, glossary)

    internal fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
