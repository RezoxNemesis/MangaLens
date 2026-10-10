package com.mangalens.core.translation

import android.content.Context
import com.mangalens.orez.OrezLocalModelService
import com.mangalens.orez.OrezModelManager

class TranslationOrezRefiner(private val context: Context) {
    private val model = OrezLocalModelService(OrezModelManager(context))

    suspend fun captureRequest(enabled: Boolean, style: TranslationStyleProfile = TranslationStyleProfile.NATURAL): TranslationRefinementRequest =
        TranslationRefinementRequest(enabled, style, if (enabled) model.captureModelPin(com.mangalens.orez.OrezModelTask.LOCALIZATION) else null,
            if (enabled) TranslationRefinementPolicy.INPUT_PROFILE_VERSION else null)

    /** Captured jobs never read ambient enable/style/model settings or choose a replacement model. */
    suspend fun refineCaptured(
        source: String,
        translated: String,
        targetLanguage: String,
        request: TranslationRefinementRequest,
        chapterContext: String = "",
        glossary: Map<String, String> = emptyMap(),
        memoryPacketSha256: String? = null
    ): TranslationRefinementResult {
        if (!request.enabled) return TranslationRefinementResult(translated, status = TranslationRefinementStatus.DISABLED)
        if (!TranslationRefinementPolicy.validCapturedInputs(source, translated, targetLanguage,
                request, chapterContext, glossary, memoryPacketSha256))
            return TranslationRefinementResult(translated, status = TranslationRefinementStatus.UNAVAILABLE)
        val capturedGlossary = glossary.toMap()
        val prompt = TranslationRefinementPolicy.capturedPrompt(source, translated, targetLanguage, request, chapterContext, capturedGlossary, memoryPacketSha256)
        val attempt = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
            model.localizeWithReceipt(prompt, requireNotNull(request.pinnedModel), requireNotNull(request.inputProfileRevision)) to Unit
        } ?: return TranslationRefinementResult(translated, status = TranslationRefinementStatus.TIMED_OUT)
        val answer = attempt.first ?: return TranslationRefinementResult(translated, status = TranslationRefinementStatus.UNAVAILABLE)
        if (answer.completion?.completedLocalization(prompt, requireNotNull(request.inputProfileRevision)) != true)
            return TranslationRefinementResult(translated, status = TranslationRefinementStatus.UNAVAILABLE)
        val text = answer.text.replace(Regex("\\s+"), " ").trim()
        val result = TranslationRefinementResult(text,
            TranslationRefinementReceipt(answer.model, TranslationRefinementPolicy.hash(prompt), TranslationRefinementPolicy.hash(text), answer.completion),
            TranslationRefinementStatus.GENERATED)
        return result.takeIf { TranslationRefinementPolicy.matches(it, source, translated, targetLanguage, request, chapterContext, capturedGlossary, memoryPacketSha256) }
            ?: TranslationRefinementResult(translated, status = TranslationRefinementStatus.UNAVAILABLE)
    }

    suspend fun refine(
        source: String,
        translated: String,
        targetLanguage: String,
        style: TranslationStyleProfile = TranslationStyleProfile.NATURAL,
        chapterContext: String = "",
        glossary: Map<String, String> = emptyMap(),
        enabledOverride: Boolean? = null
    ): String {
        if (source.isBlank() || translated.isBlank()) return translated
        // Generative editing can add latency across a whole chapter.
        // The fast on-device draft is the default; editing is an explicit quality option.
        val enabled = enabledOverride ?: context.getSharedPreferences("mangalens_ocr", Context.MODE_PRIVATE)
            .getBoolean("local_refinement", false)
        if (!enabled) return translated

        // Capture and generation both remain inside the original total deadline.
        return kotlinx.coroutines.withTimeoutOrNull(8_000L) {
            val request = captureRequest(true, style)
            refineCaptured(source, translated, targetLanguage, request, chapterContext, glossary).text
        } ?: translated
    }

    fun close() = model.close()
}
