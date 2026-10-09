package com.mangalens.ui.video

import android.content.Context
import com.mangalens.core.translation.EnglishDialoguePolicy
import com.mangalens.core.translation.TranslationDraft
import com.mangalens.core.translation.TranslationMemoryCodec
import com.mangalens.core.translation.TranslationOrezRefiner
import com.mangalens.core.translation.TranslationRefinementPolicy
import com.mangalens.core.translation.TranslationRefinementStatus
import com.mangalens.core.translation.TranslationService
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.orez.OrezTranslationEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Source-bound memory and on-device drafts; audio never enters this text-only provider. */
internal class SubtitleCueTranslator(private val context: Context) : AutoCloseable {
    private val serviceDelegate = lazy { TranslationService() }
    private val service by serviceDelegate
    private val refinerDelegate = lazy { TranslationOrezRefiner(context) }
    private val refiner by refinerDelegate
    private val lifetime = SubtitleProviderLifetime {
        if (serviceDelegate.isInitialized()) runCatching { service.close() }
        if (refinerDelegate.isInitialized()) runCatching { refiner.close() }
    }

    suspend fun translate(task: SubtitleGenerationTask, window: SubtitleWindow, sourceIndex: Int): SubtitleTranslatedCue {
        val config = task.config
        require(config.pipeline == SubtitlePipeline.SOURCE_TRANSLATION)
        require(config.localRefinement || config.style in setOf("natural", "faithful")) {
            "The chosen subtitle style requires a verified local refinement model. Original speech has been kept."
        }
        if (config.localRefinement) check(config.refinementPin != null) {
            "No verified refinement model was captured. Original speech has been kept; start a new request after installing a model."
        }
        val source = window.sourceCues[sourceIndex].text
        val contextText = task.translationContext(window.index, sourceIndex)
        val scope = "subtitle:" + task.source.fingerprint + ":" + config.fingerprint() + ":" +
            SubtitleGenerationStore.digest(window.detectedLanguage.orEmpty() + "|" + contextText)
        val style = requireNotNull(config.capturedStyle)
        var draft = if (config.localRefinement) null else recall(source, config.targetLanguage, style.memoryKey, scope)
        if (draft == null) draft = try {
            withTimeout(90_000) { lifetime.run { service.translateDraft(source, config.targetLanguage, sourceHint(source, window, config)) } }
        } catch (timeout: TimeoutCancellationException) {
            error("The on-device translation model exceeded its 90-second budget. Original speech and earlier translations have been kept.")
        }
        currentCoroutineContext().ensureActive()
        val readyDraft = requireNotNull(draft)
        val target = if (!config.localRefinement) {
            val accepted = config.selectTargetDraft(source, readyDraft, "")
            SubtitleTranslatedCue(sourceIndex, accepted.text, accepted.hindiDraft)
        } else {
            val request = config.refinementRequest()
            val refined = lifetime.run { refiner.refineCaptured(source, readyDraft.text, config.targetLanguage, request, chapterContext = contextText) }
            check(refined.status == TranslationRefinementStatus.GENERATED &&
                TranslationRefinementPolicy.matches(refined, source, readyDraft.text, config.targetLanguage, request, contextText)) {
                "The captured local refinement model could not complete a verified result. Original speech has been kept."
            }
            val accepted = config.selectTargetDraft(source, readyDraft, refined.text)
            val receipt = requireNotNull(refined.receipt)
            SubtitleTranslatedCue(sourceIndex, accepted.text, accepted.hindiDraft,
                SubtitleSavedRefinement(SubtitleRefinementPin(receipt.model.modelId, receipt.model.sha256, receipt.model.bytes),
                    receipt.promptSha256, receipt.outputSha256), readyDraft.text, refined.text, readyDraft.hindiDraft)
        }
        check(acceptsSubtitleTarget(task, window, target)) {
            "The target cue failed independent language, quality or selected-style checks. Original speech has been kept."
        }
        currentCoroutineContext().ensureActive()
        if (!config.localRefinement) remember(source, TranslationDraft(target.text, target.hindiDraft), config.targetLanguage, style.memoryKey, scope)
        return target
    }

    private fun sourceHint(source: String, window: SubtitleWindow, config: SubtitleGenerationConfig): String? = when {
        source.any { it in '\u0900'..'\u097f' && it.isLetter() } -> "hi"
        window.detectedLanguage == "en" -> "en".takeIf { EnglishDialoguePolicy.shouldHintEnglish(source) }
        config.sourceLanguage != "auto" -> config.sourceLanguage
        else -> window.detectedLanguage
    }
    private suspend fun recall(source: String, target: String, style: String, scope: String): TranslationDraft? = try {
        OrezRoomDatabase.get(context).datasets().exactTranslationScoped(source.trim(), target, style, scope)
            ?.let { TranslationMemoryCodec.decode(source, it, target) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    private suspend fun remember(source: String, draft: TranslationDraft, target: String, style: String, scope: String) {
        try {
            val key = SubtitleGenerationStore.digest(listOf(source.trim(), target, style, scope).joinToString("|") { "${it.length}:$it" })
            OrezRoomDatabase.get(context).datasets().upsertTranslation(OrezTranslationEntity(key, source.trim(),
                TranslationMemoryCodec.encode(source, draft, target), target, style = style, scope = scope))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Optional memory never discards a durable accepted cue. */ }
    }
    override fun close() = lifetime.close()
}
