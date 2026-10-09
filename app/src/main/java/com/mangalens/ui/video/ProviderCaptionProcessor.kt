package com.mangalens.ui.video

import com.mangalens.download.ProviderCaptionInventory
import com.mangalens.download.ProviderCaptionDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shared production worker stage; genuine provider captions precede speech decoding. */
internal class ProviderCaptionProcessor(
    private val store: SubtitleGenerationStore,
    private val captured: SubtitleGenerationTask,
    private val checkOwner: suspend () -> Unit,
    private val fetch: suspend (ProviderCaptionInventory, String, ProviderCaptionReceipt?) -> FetchedProviderCaptions =
        { inventory, language, saved -> ProviderCaptionFetcher().fetch(inventory, language, saved) },
    private val translate: suspend (SubtitleGenerationTask, SubtitleWindow, Int) -> SubtitleTranslatedCue,
    private val progress: suspend (SubtitleGenerationTask) -> Unit = {}
) {
    suspend fun process(): Boolean {
        val inventory = captured.source.source.providerCaptions ?: return false
        if (captured.config.pipeline != SubtitlePipeline.SOURCE_TRANSLATION ||
            captured.providerCaptionReceipt == null && captured.windows.isNotEmpty() ||
            captured.providerCaptionReceipt == null && ProviderCaptionDiscovery.select(inventory, captured.config.sourceLanguage) == null)
            return false
        checked()
        val fetched = try {
            fetch(inventory.captureSnapshot(), captured.config.sourceLanguage, captured.providerCaptionReceipt)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (paused: com.mangalens.core.compute.ResourcePausedException) { throw paused }
        catch (unavailable: Exception) {
            checked()
            if (captured.providerCaptionReceipt == null) return false
            store.providerVerificationFailed(captured.id, captured.generation)
            store.fail(captured.id, captured.generation, "Saved original provider captions are unavailable. Saved dialogue and translations have been kept.", requireValidation = true)
            throw unavailable
        }
        var task = checked()
        val receipt = providerReceipt(task, fetched.track, fetched.document)
        try {
            task = requireNotNull(store.checkpointProviderDocument(task.id, task.generation, receipt,
                providerCaptionWindows(fetched.document, fetched.track.language)))
        } catch (changed: ProviderCaptionChangedException) {
            checked()
            store.providerVerificationFailed(captured.id, captured.generation)
            store.fail(captured.id, captured.generation, changed.message ?: "Original provider captions changed.", requireValidation = true)
            throw changed
        }
        for (window in task.windows) {
            var failures = 0
            for (index in window.sourceCues.indices) {
                if (!com.mangalens.core.compute.ResourceGovernorRuntime.shared.awaitBoundary(com.mangalens.core.compute.ResourceWorkKind.BACKGROUND) {
                    store.current(captured.id, captured.generation)
                }) throw CancellationException("Provider caption generation was paused or replaced during resource wait.")
                val current = checked()
                val saved = current.windows[window.index]
                if (saved.translations.any { it.sourceIndex == index }) continue
                try {
                    val target = translate(current, saved, index)
                    checked()
                    if (!store.checkpointProviderTarget(current.id, current.generation, window.index, receipt, target))
                        throw CancellationException("Provider caption generation was paused or replaced.")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (paused: com.mangalens.core.compute.ResourcePausedException) { throw paused }
                catch (blocked: SubtitleOwnedWorkBlocked) { throw blocked }
                catch (failure: Exception) {
                    checked()
                    store.noteTargetFailure(current.id, current.generation, failure.message ?: "A provider caption could not be translated.")
                    if (++failures >= 3) {
                        store.fail(current.id, current.generation, failure.message ?: "Provider caption translation failed.")
                        return true
                    }
                }
            }
            progress(checked())
        }
        checked()
        store.finish(captured.id, captured.generation)
        return true
    }

    private suspend fun checked(): SubtitleGenerationTask {
        currentCoroutineContext().ensureActive()
        checkOwner()
        return store.get(captured.id)?.takeIf {
            sameSubtitleGeneration(captured, it) && store.current(it.id, it.generation)
        } ?: throw CancellationException("Provider caption task was paused, cancelled or replaced.")
    }
}

/** Reopening/apply/export re-fetches the exact document; media-byte proof cannot stand in for it. */
internal suspend fun revalidateProviderCaptions(store: SubtitleGenerationStore, captured: SubtitleGenerationTask,
    fetch: suspend (ProviderCaptionInventory, String, ProviderCaptionReceipt?) -> FetchedProviderCaptions =
        { inventory, language, saved -> ProviderCaptionFetcher().fetch(inventory, language, saved) }): SubtitleGenerationTask? {
    val saved = captured.providerCaptionReceipt ?: return null
    val inventory = captured.source.source.providerCaptions ?: return null
    try {
        val fetched = fetch(inventory.captureSnapshot(), captured.config.sourceLanguage, saved)
        currentCoroutineContext().ensureActive()
        val current = store.get(captured.id)?.takeIf { sameSubtitlePlaybackReceipt(it, captured) &&
            it.status != SubtitleGenerationStatus.CANCELLED } ?: return null
        return store.confirmProviderDocument(current.id, current.generation, providerReceipt(current, fetched.track, fetched.document),
            providerCaptionWindows(fetched.document, fetched.track.language))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        store.get(captured.id)?.takeIf { sameSubtitlePlaybackReceipt(it, captured) }?.let {
            store.providerVerificationFailed(it.id, it.generation)
        }
        return null
    }
}
