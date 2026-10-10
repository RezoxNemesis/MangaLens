package com.mangalens.core.translation

import android.content.Context
import com.mangalens.core.compute.*
import com.mangalens.engine.*
import kotlinx.coroutines.*
import java.io.File

/** Explicit selected-bubble work. These alternatives never publish a native page or personal journal. */
internal class ReaderBubbleRegionActionService(private val context: Context) {
    suspend fun perform(inspection: ReaderBubbleInspection, action: ReaderBubbleRegionAction, sourceText: String,
        owner: NativeComputePrecondition): ReaderBubbleRegionResult = withContext(Dispatchers.IO + owner) {
        owner.validate(true)
        check(inspection.source != null) { "Retranslate this page to establish its original coordinates." }
        when (action) {
            ReaderBubbleRegionAction.RETRY_ORIGINAL_OCR -> retryOriginal(inspection, owner)
            ReaderBubbleRegionAction.REGENERATE_TRANSLATION -> regenerate(inspection, sourceText, owner)
        }
    }

    private suspend fun retryOriginal(inspection: ReaderBubbleInspection, owner: NativeComputePrecondition): ReaderBubbleRegionResult =
        ChapterTranslationComputeLane.withLock {
            val caller = currentCoroutineContext()
            val lease = NativeComputeAdmission.shared.acquire(NativeComputeAdmission.Priority.INTERACTIVE) { caller.isActive }
                ?: throw CancellationException("Selected-region owner closed.")
            try {
                owner.validate(lease.waited)
                ResourceGovernorRuntime.shared.requireNativeEntry(ResourceWorkKind.INTERACTIVE)
                val source = ReaderBubbleOriginalOcrSource(File(context.filesDir, "chapters"))
                val alternatives = source.withPixels(inspection, current = { owner.validate(true); true }) { pixels ->
                    val config = inspection.proof.task.config
                    val engine = AdvancedTranslationEngine(context)
                    val output = mutableListOf<ReaderBubbleRegionAlternative>()
                    val variants = listOf(OcrPixelVariantSpec("original", 1f),
                        OcrPixelVariantSpec("gray-contrast", 3f, grayscale = true, contrast = 1.6f))
                    for (variant in variants) {
                        owner.validate(false)
                        ResourceGovernorRuntime.shared.requireNativeEntry(ResourceWorkKind.INTERACTIVE)
                        val plan = planOriginalPixelVariant(pixels.bitmap.width, pixels.bitmap.height, variant) ?: continue
                        val rendered = renderContextualOcrRetry(pixels.bitmap, plan)
                        try {
                            val sets = engine.recognizeSavedRegionAlternatives(rendered,
                                AdvancedTranslationEngine.OcrOptions(config.ocrScript, config.highAccuracy))
                            for (regions in sets) {
                                val actual = regions.mapNotNull { region ->
                                    val cropBounds = plan.mapBounds(OcrBox(region.bounds.left, region.bounds.top,
                                        region.bounds.right, region.bounds.bottom)) ?: return@mapNotNull null
                                    val original = pixels.toOriginal(cropBounds) ?: return@mapNotNull null
                                    region to original
                                }
                                val reading = actual.joinToString("\n") { it.first.source.trim() }
                                if (reading.isBlank() || reading.length > 4096 || output.any { it.sourceText == reading }) continue
                                val boxes = actual.map { it.second }
                                val bounds = OcrBox(boxes.minOf { it.left }, boxes.minOf { it.top },
                                    boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
                                output += ReaderBubbleRegionAlternative("ocr-${variant.label}-${output.size}",
                                    ReaderBubbleAlternativeKind.ORIGINAL_OCR, reading, originalBounds = bounds,
                                    recognizerScript = actual.first().first.recognizerScript, pixelVariant = variant.label)
                            }
                        } finally { if (rendered !== pixels.bitmap) rendered.recycle() }
                    }
                    owner.validate(true)
                    output.take(8)
                } ?: error("The verified original crop is unavailable. Reopen or repair this page.")
                ReaderBubbleRegionResult(alternatives, if (alternatives.isEmpty())
                    "The original crop produced no OCR reading. Its saved text remains available." else
                    "Original-pixel OCR alternatives are unsaved. Review a reading before using it.")
            } finally { lease.close() }
        }

    private suspend fun regenerate(inspection: ReaderBubbleInspection, sourceText: String,
        owner: NativeComputePrecondition): ReaderBubbleRegionResult {
        require(sourceText == (inspection.view.personalOcr ?: inspection.view.originalOcr) && sourceText.isNotBlank() && sourceText.length <= 4096)
        val config = inspection.proof.task.config
        val style = config.style()
        val draftTarget = if (HindiRomanization.isTarget(config.targetLanguage) &&
            (config.localRefinement || style.id !in setOf("natural", "faithful"))) "hi" else config.targetLanguage
        val translator = TranslationService()
        val draft = try { translator.translateDraftRetainingNative(sourceText, draftTarget, sourceHint(sourceText)) }
            finally { translator.close() }
        owner.validate(true)
        val chosen = try { TranslationQualityPolicy.chooseDraft(sourceText, draft, "", draftTarget, style) }
            catch (_: TranslationQualityException) { null }
        fun rendered(value: TranslationDraft) = if (draftTarget != config.targetLanguage)
            HinglishTranslationOutput.fromHindiDraft(sourceText, value.text, style) else value
        val output = mutableListOf<ReaderBubbleRegionAlternative>()
        chosen?.let { output += ReaderBubbleRegionAlternative("on-device-draft", ReaderBubbleAlternativeKind.ON_DEVICE_DRAFT,
            sourceText, rendered(it)) }
        val request = config.refinementRequest
        var message = if (chosen != null) "On-device draft is unsaved. Review it before saving a personal correction."
            else "The on-device draft did not pass source, script or grammar checks. Saved text remains unchanged."
        if (config.localRefinement && request != null) {
            // A rejected draft remains untrusted input to the same one explicitly captured
            // localizer. It is never displayed or saved merely because the model saw it.
            val modelDraft = chosen?.text ?: draft.text
            val inputs = ReaderBubbleRegionRefinementInputs.prepare(inspection, sourceText, modelDraft, draftTarget, request)
            owner.validate(true)
            val refiner = TranslationOrezRefiner(context)
            val result = try { refiner.refineCaptured(sourceText, modelDraft, draftTarget, request,
                inputs.chapterContext, inputs.glossary) } finally { refiner.close() }
            owner.validate(true)
            val capturedCompletion = TranslationRefinementPolicy.matches(result, sourceText, modelDraft, draftTarget, request,
                    inputs.chapterContext, inputs.glossary)
            if (capturedCompletion &&
                TranslationQualityPolicy.isUsable(sourceText, result.text, draftTarget)) {
                val comparison = TranslationCandidateComparisonPolicy.assess(sourceText, chosen?.text.orEmpty(), result.text, draftTarget, inputs.glossary)
                val selected = try { TranslationQualityPolicy.chooseDraft(sourceText, chosen ?: draft, result.text, draftTarget, style, inputs.glossary) }
                    catch (_: TranslationQualityException) { null }
                if (selected != null && selected.text != chosen?.text) output += ReaderBubbleRegionAlternative(
                    "pinned-local-refinement", ReaderBubbleAlternativeKind.PINNED_LOCAL_REFINEMENT, sourceText, rendered(selected))
                message = if (comparison != TranslationCandidateComparison.COMPATIBLE)
                    "Pinned refinement did not retain the observed numeric or captured glossary constraints; saved text remains unchanged."
                    else if (output.size > 1) "Draft and pinned local refinement are separate unsaved alternatives."
                    else if (chosen == null && selected != null) "Pinned localization supplied an independently checked unsaved alternative. Review it against the original before saving."
                    else "Pinned localization supplied no distinct usable alternative; saved text remains unchanged."
            } else message = if (capturedCompletion)
                "The pinned candidate did not pass source, script or grammar checks; saved text remains unchanged."
                else "Pinned local refinement is unavailable for this captured request; saved text remains unchanged."
            if (inputs.targetProjectionOmitted) message += " Roman glossary wording has no saved Hindi intermediate and was omitted from Hindi refinement."
        }
        owner.validate(true)
        output.forEach { ReaderBubbleAlternativeInputPolicy.edit(it) }
        return ReaderBubbleRegionResult(output, message)
    }

    private fun sourceHint(text: String): String? = when {
        text.any { it in '\u3040'..'\u30ff' } -> "ja"
        text.any { it in '\uac00'..'\ud7af' } -> "ko"
        text.any { it in '\u0900'..'\u097f' } -> "hi"
        EnglishDialoguePolicy.shouldHintEnglish(text) -> "en"
        else -> null
    }
}

/** Verified current profile values fit the captured prompt. No whole-chapter packet identity is invented. */
internal object ReaderBubbleRegionRefinementInputs {
    fun prepare(inspection: ReaderBubbleInspection, source: String, draft: String, target: String,
        request: TranslationRefinementRequest): CapturedMemoryRefinementInputs {
        val omitted = !inspection.view.targetLanguage.equals(target, true)
        val glossary = linkedMapOf<String, String>()
        if (!omitted) for (term in inspection.profile?.glossary.orEmpty().filter {
                it.targetLanguage.equals(target, true) && (source.contains(it.source, true) || it.aliases.any { alias -> source.contains(alias, true) })
            }.take(16)) {
            val candidate = glossary + (term.source to term.preferred)
            if (TranslationRefinementPolicy.validCapturedInputs(source, draft, target, request, "", candidate, null))
                glossary[term.source] = term.preferred
        }
        val page = inspection.proof.page
        val indices = listOf(inspection.letteringIndex - 1, inspection.letteringIndex, inspection.letteringIndex + 1)
        var context = indices.mapNotNull { index -> page.lettering.getOrNull(index)?.let { letter ->
            if (omitted) "Saved original OCR: ${letter.source}" else "Saved original OCR: ${letter.source}\nSaved native translation: ${letter.translated}"
        } }.joinToString("\n").take(4500)
        while (context.isNotEmpty() && !TranslationRefinementPolicy.validCapturedInputs(source, draft, target, request, context, glossary, null))
            context = context.dropLast(minOf(128, context.length))
        return CapturedMemoryRefinementInputs(context, glossary.toMap(), null, omitted && inspection.profile?.glossary.orEmpty().isNotEmpty())
    }
}
