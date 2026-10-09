package com.mangalens.orez.agent

import android.content.Context
import com.mangalens.download.DownloadDatabase
import com.mangalens.download.DownloadState
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.ui.video.SubtitleGenerationJobs
import com.mangalens.ui.video.SubtitleGenerationStore
import com.mangalens.ui.video.SubtitleGenerationTask
import com.mangalens.ui.video.SubtitleInputs
import com.mangalens.ui.video.hasSubtitleSourceProof
import com.mangalens.ui.video.SubtitlePipeline
import com.mangalens.ui.video.revalidateProviderCaptions
import com.mangalens.ui.video.hasVerifiedProviderCaptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Staged against the agreed native owner/refresh API; joint integration follows its freeze. */
class OrezNativeSubtitleHost(
    context: Context,
    private val native: SubtitleGenerationStore = SubtitleGenerationStore.shared(context),
    private val ownerPlan: (suspend (String) -> OrezTaskPlan?)? = null
) : OrezSubtitleHost {
    private val app = context.applicationContext
    private val exportsDirectory = File(app.filesDir, "subtitle_jobs")

    override suspend fun inspectSelection(selection: OrezMediaSelection, pinnedModelSha256: String?) =
        inspect(selection.sourceId, selection, pinnedModelSha256)

    override suspend fun inspectDownload(download: OrezSubtitleDownload, pinnedModelSha256: String?): OrezSubtitleSnapshot {
        val row = DownloadDatabase.get(app).downloads().get(download.id)
        require(row != null && row.state == DownloadState.COMPLETED && !row.isAdaptive && row.destination == download.destination &&
            row.bytesDownloaded == download.bytes && download.bytes > 0 && download.sourceUrl in setOfNotNull(row.sourceUrl, row.sourcePageUrl)) {
            "This plan's published native download is missing or has changed."
        }
        return inspect("download-${download.id}", OrezMediaSelection(download.destination, "orez-download:${download.id}", download.title.take(250)),
            pinnedModelSha256)
    }

    private suspend fun inspect(sourceId: String, descriptor: OrezMediaSelection, pin: String?): OrezSubtitleSnapshot = withContext(Dispatchers.IO) {
        val captured = descriptor.captured()
        val source = SubtitleInputs.capture(app, captured.nativeSubtitleSource())
        val captionCandidate = captured.hasProviderCaptionCandidate()
        require(hasSubtitleSourceProof(source) || captionCandidate) { "Select a saved file, stable playable source or verified provider caption selection." }
        val installed = SubtitleInputs.config(app, "auto").modelSha256
        val model = if (pin == "" && captionCandidate) "" else installed ?: "".also {
            require(captionCandidate) { "Install or import a multilingual Whisper model first." }
        }
        require(pin == null || pin == model) { "The installed speech model changed. Start a new subtitle request." }
        require(source.source == captured.nativeSubtitleSource()) { "The selected playable descriptor changed during inspection." }
        OrezSubtitleSnapshot(sourceId, captured, source.fingerprint, model)
    }

    override suspend fun start(media: OrezSubtitleSnapshot, options: OrezSubtitleOptions, requestId: String,
        allowReplacement: Boolean): OrezSubtitleReceipt = withContext(Dispatchers.IO) {
        val source = SubtitleInputs.capture(app, media.descriptor.nativeSubtitleSource())
        require((hasSubtitleSourceProof(source) || options.pipeline == SubtitlePipeline.SOURCE_TRANSLATION && media.descriptor.hasProviderCaptionCandidate()) &&
            source.fingerprint == media.sourceFingerprint) { "The selected media changed after inspection." }
        val savedSpeech = if (allowReplacement) null else native.states.value.firstOrNull { task ->
            task.ownerRequestId == requestId && task.source.source == source.source && task.source.fingerprint == media.sourceFingerprint &&
                task.config == options.nativeConfig(media.speechModelSha256)
        }?.let { candidate -> native.refresh(candidate.id, candidate.generation)?.let { current ->
            val owned = OrezSubtitleNativeEvidence.metadataReceipt(current, media.sourceId, media.descriptor)
            OrezSubtitleTools.verify(owned, media, options, requestId)
            if (current.providerCaptionReceipt != null) revalidateProviderCaptions(native, current)?.let { verified ->
                receipt(verified, media.sourceId, media.descriptor).also { OrezSubtitleTools.verify(it, media, options, requestId) }.media
            } else OrezSubtitleNativeEvidence.savedSpeechSnapshot(owned, current, source)
        } }
        require(savedSpeech != null || media.speechModelSha256.isEmpty() && options.pipeline == SubtitlePipeline.SOURCE_TRANSLATION && media.descriptor.hasProviderCaptionCandidate() ||
            SubtitleInputs.config(app, options.sourceLanguage).modelSha256 == media.speechModelSha256) {
            "The pinned speech model changed after inspection."
        }
        val task = SubtitleGenerationJobs.start(app, source, options.nativeConfig(media.speechModelSha256),
            ownerRequestId = requestId, allowOwnerReplacement = allowReplacement)
        return@withContext receipt(requireNotNull(native.refresh(task.id, task.generation)) {
            "The native subtitle generation changed before its receipt was captured."
        }, media.sourceId, media.descriptor).also {
            OrezSubtitleTools.verify(it, media, options, requestId)
        }
    }

    override suspend fun observe(taskId: String, requestId: String, media: OrezSubtitleSnapshot,
        options: OrezSubtitleOptions): OrezSubtitleReceipt? = observeScoped(taskId, requestId, media, options, null)

    override suspend fun observe(taskId: String, requestId: String, media: OrezSubtitleSnapshot,
        options: OrezSubtitleOptions, expectedGeneration: String): OrezSubtitleReceipt? =
        observeScoped(taskId, requestId, media, options, expectedGeneration)

    private suspend fun observeScoped(taskId: String, requestId: String, media: OrezSubtitleSnapshot,
        options: OrezSubtitleOptions, expectedGeneration: String?): OrezSubtitleReceipt? = withContext(Dispatchers.IO) {
        suspend fun verify(task: SubtitleGenerationTask) = OrezSubtitleTools.verify(
            OrezSubtitleNativeEvidence.metadataReceipt(task, media.sourceId, media.descriptor), media, options, requestId)
        val before = OrezOwnedSubtitleLookup.refresh(native, taskId, requestId, expectedGeneration, ::verify) ?: return@withContext null
        // Observation confirms fresh source proof without redispatching or adopting a newer same-owner generation.
        // The native confirmation retains any requirement to verify PCM through its actual worker.
        val confirmed = (if (before.providerCaptionReceipt != null) revalidateProviderCaptions(native, before)?.also { verify(it) }
        else if (before.source.source.providerCaptions != null && !hasSubtitleSourceProof(before.source)) before
        else {
            val fresh = SubtitleInputs.capture(app, media.descriptor.nativeSubtitleSource())
            OrezOwnedSubtitleLookup.confirmSource(native, before, fresh, ::verify)
        }) ?: return@withContext null
        receipt(confirmed, media.sourceId, media.descriptor)
    }

    override suspend fun findOwned(requestId: String): OrezSubtitleReceipt? {
        val candidate = native.states.value.firstOrNull { it.ownerRequestId == requestId } ?: return null
        val plan = (if (ownerPlan != null) ownerPlan.invoke(requestId) else run {
            val store = OrezTaskStore(OrezRoomDatabase.get(app).tasks())
            OrezDownloadTaskLink.candidates(requestId).firstNotNullOfOrNull { id -> store.load(id)?.takeIf {
                it.steps.any { step -> step.call.name == "generate_subtitles" && OrezDurablePlanRules.requestId(id, step.index) == requestId }
            } }
        }) ?: return null
        val step = plan.steps.firstOrNull { it.call.name == "generate_subtitles" && OrezDurablePlanRules.requestId(plan.id, it.index) == requestId } ?: return null
        val expected = OrezSubtitlePlanScope.expected(plan, step)
        val options = requireNotNull(plan.authorization?.subtitle)
        val task = OrezOwnedSubtitleLookup.refresh(native, candidate.id, requestId, candidate.generation) { captured ->
            OrezSubtitleTools.verify(OrezSubtitleNativeEvidence.metadataReceipt(captured, expected.sourceId, expected.descriptor), expected, options, requestId)
        } ?: return null
        return receipt(task, expected.sourceId, expected.descriptor)
    }

    override suspend fun revalidateOwned(receipt: OrezSubtitleReceipt): OrezSubtitleSnapshot? = withContext(Dispatchers.IO) {
        val before = native.get(receipt.taskId)?.takeIf { it.generation == receipt.generation } ?: return@withContext null
        if (before.providerCaptionReceipt != null) {
            val verified = revalidateProviderCaptions(native, before)?.takeIf(::hasVerifiedProviderCaptions) ?: return@withContext null
            return@withContext receipt(verified, receipt.media.sourceId, receipt.media.descriptor).also {
                OrezSubtitleTools.verify(it, receipt.media, receipt.options, requireNotNull(receipt.ownerRequestId))
            }.media
        }
        if (!before.audioComplete || before.pcmValidationRequired) return@withContext null
        val fresh = SubtitleInputs.capture(app, receipt.media.descriptor.nativeSubtitleSource())
        val current = native.refresh(receipt.taskId, receipt.generation) ?: return@withContext null
        OrezSubtitleNativeEvidence.savedSpeechSnapshot(receipt, current, fresh)
    }

    override suspend fun pause(receipt: OrezSubtitleReceipt) = control(receipt) {
        SubtitleGenerationJobs.pause(app, it.taskId, it.generation)
    }
    override suspend fun resume(receipt: OrezSubtitleReceipt) = control(receipt) {
        SubtitleGenerationJobs.resume(app, it.taskId, it.generation)
    }
    override suspend fun cancel(receipt: OrezSubtitleReceipt) = control(receipt) {
        SubtitleGenerationJobs.cancel(app, it.taskId, it.generation)
    }

    private suspend fun control(expected: OrezSubtitleReceipt, action: suspend (OrezSubtitleReceipt) -> SubtitleGenerationTask?): OrezSubtitleReceipt? {
        val task = native.get(expected.taskId)?.takeIf { it.generation == expected.generation } ?: return null
        OrezSubtitleTools.verify(receipt(task, expected.media.sourceId, expected.media.descriptor), expected.media, expected.options,
            requireNotNull(expected.ownerRequestId))
        return action(expected)?.let { receipt(it, expected.media.sourceId, expected.media.descriptor) }
    }

    internal fun receipt(task: SubtitleGenerationTask, sourceId: String, expectedDescriptor: OrezMediaSelection? = null): OrezSubtitleReceipt =
        OrezSubtitleNativeEvidence.receipt(task, sourceId, exportsDirectory, expectedDescriptor)
}
