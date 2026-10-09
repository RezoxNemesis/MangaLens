package com.mangalens.core.translation

import android.content.Context
import com.mangalens.orez.OrezLocalModelService
import com.mangalens.orez.OrezModelManager

class TranslationOrezRefiner(private val context: Context) {
    private val model = OrezLocalModelService(OrezModelManager(context))

    suspend fun captureRequest(enabled: Boolean, style: TranslationStyleProfile = TranslationStyleProfile.NATURAL): TranslationRefinementRequest =
        TranslationRefinementRequest(enabled, style, if (enabled) model.captureModelPin() else null)

    /** Captured jobs never read ambient enable/style/model settings or choose a replacement model. */
    suspend fun refineCaptured(
        source: String,
        translated: String,
        targetLanguage: String,
        request: TranslationRefinementRequest,
        chapterContext: String = "",
        glossary: Map<String, String> = emptyMap()
    ): TranslationRefinementResult {
        if (!request.enabled) return TranslationRefinementResult(translated, status = TranslationRefinementStatus.DISABLED)
        if (request.pinnedModel == null || !TranslationRefinementPolicy.validInputs(source, translated, targetLanguage,
                request.style, chapterContext, glossary))
            return TranslationRefinementResult(translated, status = TranslationRefinementStatus.UNAVAILABLE)
        val capturedGlossary = glossary.toMap()
        val prompt = TranslationRefinementPolicy.prompt(source, translated, targetLanguage, request.style, chapterContext, capturedGlossary)
        val attempt = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
            model.answerWithReceipt(prompt, emptyList(), pinnedModel = request.pinnedModel) to Unit
        } ?: return TranslationRefinementResult(translated, status = TranslationRefinementStatus.TIMED_OUT)
        val answer = attempt.first ?: return TranslationRefinementResult(translated, status = TranslationRefinementStatus.UNAVAILABLE)
        val text = answer.text.replace(Regex("\\s+"), " ").trim()
        val result = TranslationRefinementResult(text,
            TranslationRefinementReceipt(answer.model, TranslationRefinementPolicy.hash(prompt), TranslationRefinementPolicy.hash(text)),
            TranslationRefinementStatus.GENERATED)
        return result.takeIf { TranslationRefinementPolicy.matches(it, source, translated, targetLanguage, request, chapterContext, capturedGlossary) }
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

        val prompt = buildTranslationRefinementPrompt(
            source = source,
            translated = translated,
            targetLanguage = targetLanguage,
            style = style,
            chapterContext = chapterContext,
            glossary = glossary
        )

        return kotlinx.coroutines.withTimeoutOrNull(8_000L) { model.answer(prompt, emptyList()) }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: translated
    }

    fun close() = model.close()
}
