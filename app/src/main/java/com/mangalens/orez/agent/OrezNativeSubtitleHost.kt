package com.mangalens.orez.agent

import android.content.Context
import com.mangalens.download.DownloadDatabase
import com.mangalens.download.DownloadState
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.ui.video.SubtitleGenerationConfig
import com.mangalens.ui.video.SubtitleGenerationJobs
import com.mangalens.ui.video.SubtitleGenerationStore
import com.mangalens.ui.video.SubtitleGenerationTask
import com.mangalens.ui.video.SubtitleInputs
import com.mangalens.ui.video.SubtitleMediaSource
import com.mangalens.ui.video.hasSubtitleSourceProof
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
        val source = SubtitleInputs.capture(app, captured.nativeSource())
        require(hasSubtitleSourceProof(source)) { "Select a saved file or a playable source with a stable version before generating subtitles." }
        val model = requireNotNull(SubtitleInputs.config(app, "auto").modelSha256) { "Install or import a multilingual Whisper model first." }
        require(pin == null || pin == model) { "The installed speech model changed. Start a new subtitle request." }
        require(source.source == captured.nativeSource()) { "The selected playable descriptor changed during inspection." }
        OrezSubtitleSnapshot(sourceId, captured, source.fingerprint, model)
    }

    override suspend fun start(media: OrezSubtitleSnapshot, options: OrezSubtitleOptions, requestId: String,
        allowReplacement: Boolean): OrezSubtitleReceipt = withContext(Dispatchers.IO) {
        val source = SubtitleInputs.capture(app, media.descriptor.nativeSource())
        require(hasSubtitleSourceProof(source) && source.fingerprint == media.sourceFingerprint) { "The selected media changed after inspection." }
        val installed = SubtitleInputs.config(app, options.sourceLanguage)
        require(installed.modelSha256 == media.speechModelSha256) { "The pinned speech model changed after inspection." }
        val task = SubtitleGenerationJobs.start(app, source, options.nativeConfig(media.speechModelSha256),
            ownerRequestId = requestId, allowOwnerReplacement = allowReplacement)
        return@withContext receipt(requireNotNull(native.refresh(task.id, task.generation)) {
            "The native subtitle generation changed before its receipt was captured."
        }, media.sourceId).also {
            OrezSubtitleTools.verify(it, media, options, requestId)
        }
    }

    override suspend fun observe(taskId: String, requestId: String, media: OrezSubtitleSnapshot,
        options: OrezSubtitleOptions): OrezSubtitleReceipt? {
        val before = native.refresh(taskId) ?: return null
        OrezSubtitleTools.verify(receipt(before, media.sourceId), media, options, requestId)
        // Revalidation clears a restored source mask only through the actual native
        // idempotent start boundary. It preserves terminal and paused generations.
        return start(media, options, requestId, allowReplacement = false).also {
            require(it.taskId == taskId) { "The native subtitle identity changed during receipt revalidation." }
        }
    }

    override suspend fun findOwned(requestId: String): OrezSubtitleReceipt? {
        val candidate = native.states.value.firstOrNull { it.ownerRequestId == requestId } ?: return null
        val task = native.refresh(candidate.id)?.takeIf { it.ownerRequestId == requestId } ?: return null
        val plan = (if (ownerPlan != null) ownerPlan.invoke(requestId) else run {
            val store = OrezTaskStore(OrezRoomDatabase.get(app).tasks())
            OrezDownloadTaskLink.candidates(requestId).firstNotNullOfOrNull { id -> store.load(id)?.takeIf {
                it.steps.any { step -> step.call.name == "generate_subtitles" && OrezDurablePlanRules.requestId(id, step.index) == requestId }
            } }
        }) ?: return null
        val step = plan.steps.firstOrNull { it.call.name == "generate_subtitles" && OrezDurablePlanRules.requestId(plan.id, it.index) == requestId } ?: return null
        val expected = OrezSubtitlePlanScope.expected(plan, step)
        return receipt(task, expected.sourceId)
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
        OrezSubtitleTools.verify(receipt(task, expected.media.sourceId), expected.media, expected.options,
            requireNotNull(expected.ownerRequestId))
        return action(expected)?.let { receipt(it, expected.media.sourceId) }
    }

    internal fun receipt(task: SubtitleGenerationTask, sourceId: String): OrezSubtitleReceipt =
        OrezSubtitleNativeEvidence.receipt(task, sourceId, exportsDirectory)

    private fun OrezMediaSelection.nativeSource() = SubtitleMediaSource(uri, headers.toMap(), cacheKey, label)
    private fun OrezSubtitleOptions.nativeConfig(model: String) = SubtitleGenerationConfig(sourceLanguage, targetLanguage, style,
        model, windowSeconds, overlapSeconds, threads)
}
