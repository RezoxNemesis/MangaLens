package com.mangalens.ui.video

import com.mangalens.core.translation.TranslationDraft
import com.mangalens.core.translation.TranslationQualityPolicy
import com.mangalens.core.translation.TranslationRefinementPolicy
import com.mangalens.core.translation.TranslationRefinementReceipt
import com.mangalens.core.translation.TranslationRefinementRequest
import com.mangalens.core.translation.TranslationRefinementResult
import com.mangalens.core.translation.TranslationRefinementStatus
import com.mangalens.core.translation.TranslationStyleProfile
import com.mangalens.orez.OrezModelPin

internal fun SubtitleGenerationConfig.refinementRequest(): TranslationRefinementRequest = TranslationRefinementRequest(
    localRefinement, requireNotNull(capturedStyle), refinementPin?.let { OrezModelPin(it.modelId, it.sha256, it.bytes) })

/** Candidate selection uses the same immutable style during generation and journal verification. */
internal fun SubtitleGenerationConfig.selectTargetDraft(source: String, draft: TranslationDraft, refined: String): TranslationDraft =
    TranslationQualityPolicy.chooseDraft(source, draft, refined, targetLanguage, requireNotNull(capturedStyle))

/** Only earlier original speech is context; a retry never changes a cue's prompt with new target text. */
internal fun SubtitleGenerationTask.translationContext(windowIndex: Int, sourceIndex: Int): String {
    val earlier = windows.take(windowIndex).takeLast(3).flatMap { it.sourceCues }.takeLast(12) +
        windows[windowIndex].sourceCues.take(sourceIndex)
    val previous = earlier.joinToString(" ") { it.text }
    return if (config.sceneContext.isEmpty()) previous.takeLast(1200)
        else config.sceneContext + previous.takeLast((1200 - config.sceneContext.length).coerceAtLeast(0)).let { if (it.isEmpty()) "" else "\n" + it }.take((1200 - config.sceneContext.length).coerceAtLeast(0))
}

internal fun acceptsSubtitleTarget(task: SubtitleGenerationTask, window: SubtitleWindow, target: SubtitleTranslatedCue): Boolean {
    if (target.sourceIndex !in window.sourceCues.indices || target.text.length !in 1..4000 ||
        target.hindiDraft?.length?.let { it !in 1..4000 } == true) return false
    val source = window.sourceCues[target.sourceIndex].text
    if (!TranslationQualityPolicy.isUsable(source, target.text, task.config.targetLanguage, target.hindiDraft)) return false
    if (!task.config.localRefinement) return task.config.style in setOf("natural", "faithful") &&
        task.config.capturedStyle == TranslationStyleProfile.fromId(task.config.style) && target.refinement == null &&
        target.refinementDraft == null && target.refinementCandidate == null && target.refinementHindiDraft == null
    val receipt = target.refinement ?: return false
    val draft = target.refinementDraft?.takeIf { it.length in 1..4000 } ?: return false
    val candidate = target.refinementCandidate?.takeIf { it.length in 1..4000 } ?: return false
    if (target.refinementHindiDraft?.length?.let { it !in 1..4000 } == true) return false
    val result = TranslationRefinementResult(candidate, TranslationRefinementReceipt(
        OrezModelPin(receipt.model.modelId, receipt.model.sha256, receipt.model.bytes), receipt.promptSha256, receipt.outputSha256),
        TranslationRefinementStatus.GENERATED)
    if (!TranslationRefinementPolicy.matches(result, source, draft, task.config.targetLanguage, task.config.refinementRequest(),
            task.translationContext(window.index, target.sourceIndex))) return false
    return runCatching {
        val selected = task.config.selectTargetDraft(source, TranslationDraft(draft, target.refinementHindiDraft), candidate)
        // The model receipt proves the candidate, never an unrelated fallback draft.
        val candidateProof = target.refinementHindiDraft?.takeIf { candidate == draft }
        val checkedCandidate = task.config.selectTargetDraft(source, TranslationDraft(candidate, candidateProof), "")
        selected.text == target.text && checkedCandidate.text == target.text && selected.hindiDraft == target.hindiDraft
    }.getOrDefault(false)
}
