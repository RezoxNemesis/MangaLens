package com.mangalens.core.translation

import android.content.Context
import com.mangalens.orez.OrezLocalModelService
import com.mangalens.orez.OrezModelManager

class TranslationOrezRefiner(private val context: Context) {
    private val model = OrezLocalModelService(OrezModelManager(context))

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
