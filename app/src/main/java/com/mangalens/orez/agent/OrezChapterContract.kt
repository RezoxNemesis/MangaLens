package com.mangalens.orez.agent

import com.mangalens.core.translation.ChapterTranslationConfig
import com.mangalens.core.translation.TranslationRefinementPolicy
import com.mangalens.core.translation.TranslationRefinementRequestCodec

internal fun OrezTranslationOptions.nativeChapterConfig() = ChapterTranslationConfig(targetLanguage, styleId, customStyle, ocrScript,
    highAccuracy, preserveStyle, localRefinement, refinementRequest).normalized()

internal fun ChapterTranslationConfig.orezChapterOptions() = OrezTranslationOptions(targetLanguage, styleId, customStyle, ocrScript,
    highAccuracy, preserveStyle, localRefinement, refinementRequest)

internal fun OrezTranslationOptions.refinementRequestFingerprint(): String? =
    refinementRequest?.let { TranslationRefinementPolicy.hash(TranslationRefinementRequestCodec.identity(it)) }

internal fun OrezTranslationOptions.requireCapturedChapterRefinement() {
    nativeChapterConfig()
    if (localRefinement) {
        val request = requireNotNull(refinementRequest) {
            "This older queued request has no captured refinement model. Start a new translation explicitly."
        }
        require(request.enabled && request.pinnedModel != null) {
            "No verified refinement model was captured. Install a verified pack, then start a new translation explicitly."
        }
    }
}
