package com.mangalens.orez.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.mangalens.ui.video.SubtitlePipeline
import com.mangalens.ui.video.ProviderCaptionReceipt
import com.mangalens.ui.video.fingerprint
import com.mangalens.ui.video.selectedTrack
import com.mangalens.download.ProviderCaptionDiscovery

data class OrezSubtitleSnapshot(
    val sourceId: String, val descriptor: OrezMediaSelection, val sourceFingerprint: String,
    val speechModelSha256: String, val verifiable: Boolean = true,
    val captionInventorySha256: String? = descriptor.providerCaptions?.fingerprint()
)
data class OrezSubtitleDownload(val id: String, val sourceUrl: String, val destination: String, val bytes: Long, val title: String)
enum class OrezNativeSubtitleStatus { QUEUED, RUNNING, PAUSED, COMPLETED, PARTIAL, FAILED, CANCELLED }
data class OrezSubtitleExports(val srtSha256: String, val vttSha256: String, val srtBytes: Long, val vttBytes: Long)
data class OrezSubtitleReceipt(
    val taskId: String, val generation: String, val ownerRequestId: String?, val media: OrezSubtitleSnapshot,
    val options: OrezSubtitleOptions, val status: OrezNativeSubtitleStatus, val durationMs: Long, val processedMs: Long,
    val windowCount: Int, val cueCount: Int, val exports: OrezSubtitleExports? = null,
    val validationPending: Boolean = false, val error: String? = null,
    val configFingerprint: String = options.nativeConfig(media.speechModelSha256).fingerprint(),
    val audioComplete: Boolean = options.pipeline == SubtitlePipeline.WHISPER_ENGLISH,
    val sourceCueCount: Int = 0, val pendingTargetCues: Int = 0,
    val providerCaptionReceipt: ProviderCaptionReceipt? = null
)

/** Exact native source/config/owner boundary; navigation never produces these receipts. */
interface OrezSubtitleHost {
    suspend fun inspectSelection(selection: OrezMediaSelection, pinnedModelSha256: String? = null): OrezSubtitleSnapshot
    suspend fun inspectDownload(download: OrezSubtitleDownload, pinnedModelSha256: String? = null): OrezSubtitleSnapshot
    suspend fun start(media: OrezSubtitleSnapshot, options: OrezSubtitleOptions, requestId: String, allowReplacement: Boolean): OrezSubtitleReceipt
    suspend fun observe(taskId: String, requestId: String, media: OrezSubtitleSnapshot, options: OrezSubtitleOptions): OrezSubtitleReceipt?
    /** Existing hosts retain their public API; the native provider fences inspection before mutation. */
    suspend fun observe(taskId: String, requestId: String, media: OrezSubtitleSnapshot, options: OrezSubtitleOptions,
        expectedGeneration: String): OrezSubtitleReceipt? = observe(taskId, requestId, media, options)?.takeIf { it.generation == expectedGeneration }
    suspend fun findOwned(requestId: String): OrezSubtitleReceipt?
    /** Existing verified original speech may repair targets without adopting the currently installed speech model. */
    suspend fun revalidateOwned(receipt: OrezSubtitleReceipt): OrezSubtitleSnapshot? = null
    suspend fun pause(receipt: OrezSubtitleReceipt): OrezSubtitleReceipt?
    suspend fun resume(receipt: OrezSubtitleReceipt): OrezSubtitleReceipt?
    suspend fun cancel(receipt: OrezSubtitleReceipt): OrezSubtitleReceipt?
}

class OrezSubtitleTools(
    private val host: OrezSubtitleHost,
    private val authorization: OrezTaskAuthorization,
    private val downloaded: suspend (String) -> OrezSubtitleDownload? = { null },
    private val stopOwned: (suspend (OrezSubtitleReceipt) -> OrezSubtitleReceipt?)? = null,
    private val mayStopOwned: (suspend () -> Boolean)? = null,
    private val isExecuting: suspend () -> Boolean = { true }
) : OrezDurableTools {
    override suspend fun execute(step: OrezPlanStep, requestId: String): OrezToolResult {
        try {
            require(authorization.origin == OrezTrustOrigin.USER && authorization.explicitUserRequest) { "Explicit captured user source scope is missing." }
            val options = requireNotNull(authorization.subtitle) { "Speech options were not captured." }
            OrezSubtitleContract.validate(options)
            val media = inspect(step, requestId)
            require(media.verifiable && media.sourceFingerprint.matches(HASH) && (media.speechModelSha256.matches(HASH) || options.pipeline == SubtitlePipeline.SOURCE_TRANSLATION &&
                media.speechModelSha256.isEmpty() && media.descriptor.hasProviderCaptionCandidate())) {
                "This playable source or installed speech model cannot be verified. Select a saved file or stable playable source and install a Whisper model."
            }
            if (step.call.name in setOf("inspect_selected_media", "inspect_downloaded_media")) return OrezToolResult.Completed(media.outputs(requestId))
            require(step.call.name == "generate_subtitles") { "Unsupported native subtitle tool." }
            require(step.call.arguments["sourceFingerprint"] == media.sourceFingerprint && step.call.arguments["speechModelSha256"] == media.speechModelSha256 &&
                step.call.arguments["captionInventorySha256"] == media.captionInventorySha256) {
                "Media or the pinned speech model changed after inspection. Start a new subtitle request."
            }
            require(step.call.arguments["targetLanguage"] == options.targetLanguage) { "Subtitle target exceeds the captured request." }
            if (!isExecuting()) return OrezToolResult.Pending("Task paused before native subtitle dispatch.", needsResume = true)
            val previous = step.outputs["subtitleTaskId"]
            val receipt = if (previous != null) {
                require(step.outputs["requestId"] == requestId) { "Saved subtitle receipt belongs to another request." }
                requireNotNull(host.observe(previous, requestId, media, options, requireNotNull(step.outputs["generation"]))) {
                    "Native subtitle receipt is missing or its generation was replaced. Start a new request."
                }.also {
                    require(it.generation == step.outputs["generation"]) { "Native subtitle generation was replaced. Start a new request." }
                }
            } else host.start(media, options, requestId, allowReplacement = step.status == OrezStepStatus.PENDING)
            verify(receipt, media, options, requestId)
            if (!isExecuting()) {
                val stopped = if (canStop()) stop(receipt) ?: receipt else receipt
                return OrezToolResult.Pending("Task paused during native subtitle dispatch.", true, stopped.outputs(requestId))
            }
            return when (receipt.status) {
                OrezNativeSubtitleStatus.COMPLETED -> {
                    verifyCompleted(receipt)
                    OrezToolResult.Completed(receipt.outputs(requestId) + ("destination" to "subtitle-track:${receipt.taskId}"))
                }
                OrezNativeSubtitleStatus.PAUSED -> OrezToolResult.Pending("Subtitle generation is paused. Resume to retain completed speech windows.", true, receipt.outputs(requestId))
                OrezNativeSubtitleStatus.CANCELLED -> OrezToolResult.Cancelled("The owned native subtitle generation was cancelled.")
                OrezNativeSubtitleStatus.PARTIAL, OrezNativeSubtitleStatus.FAILED -> OrezToolResult.Failed(receipt.error ?: "Subtitle generation stopped before the full track was verified. Resume to retry unfinished audio.")
                else -> OrezToolResult.Pending("Subtitle generation is continuing in its native worker.", outputs = receipt.outputs(requestId))
            }
        } catch (cancelled: CancellationException) {
            // OS interruption preserves the native job. A delayed old epoch may stop
            // only if the shared control fence still authorizes its exact owned receipt.
            withContext(NonCancellable) {
                try {
                    if (step.call.name == "generate_subtitles" && canStop()) host.findOwned(requestId)?.let { receipt ->
                        val descriptor = capturedDescriptor(requireNotNull(step.call.arguments["sourceId"]))
                        val expected = OrezSubtitleSnapshot(step.call.arguments.getValue("sourceId"), descriptor,
                            step.call.arguments.getValue("sourceFingerprint"), step.call.arguments.getValue("speechModelSha256"))
                        verify(receipt, expected, requireNotNull(authorization.subtitle), requestId)
                        stop(receipt)
                    }
                } catch (_: Exception) { /* The persisted pending control is reconciled headlessly. */ }
            }
            throw cancelled
        } catch (failure: Exception) {
            return OrezToolResult.Failed(failure.message ?: "Native subtitle workflow failed.")
        }
    }

    private suspend fun capturedDescriptor(sourceId: String): OrezMediaSelection {
        authorization.selectedMedia?.takeIf { it.sourceId == sourceId }?.let { return it }
        val id = sourceId.takeIf { it.startsWith("download-") }?.removePrefix("download-")
        val download = requireNotNull(id?.let { downloaded(it) }) { "Captured native download source is missing." }
        require(download.id == id && download.sourceUrl in authorization.urls && download.bytes > 0) { "Downloaded media exceeds captured scope." }
        return download.descriptor()
    }

    private suspend fun inspect(step: OrezPlanStep, requestId: String): OrezSubtitleSnapshot {
        val pin = if (step.call.name == "generate_subtitles") step.call.arguments["speechModelSha256"] else null
        if (step.call.name == "generate_subtitles" && authorization.subtitle?.pipeline == SubtitlePipeline.SOURCE_TRANSLATION &&
            (step.outputs["audioComplete"] == "true" || step.outputs["providerPayloadSha256"]?.matches(HASH) == true) && step.outputs["subtitleTaskId"] != null) {
            val expected = OrezSubtitleSnapshot(step.call.arguments.getValue("sourceId"), capturedDescriptor(step.call.arguments.getValue("sourceId")),
                step.call.arguments.getValue("sourceFingerprint"), step.call.arguments.getValue("speechModelSha256"))
            val owned = requireNotNull(host.findOwned(requestId)) { "The owned saved original speech is missing." }
            verify(owned, expected, requireNotNull(authorization.subtitle), requestId)
            require(owned.taskId == step.outputs["subtitleTaskId"] && owned.generation == step.outputs["generation"]) { "Saved original speech belongs to a replaced generation." }
            host.revalidateOwned(owned)?.let { verified ->
                require(verified == expected) { "The saved original speech source changed during revalidation." }
                return verified
            }
        }
        val selected = authorization.selectedMedia
        val id = step.call.arguments["sourceId"]
        if (step.call.name == "inspect_selected_media" || id == selected?.sourceId && selected != null) {
            require(selected != null && id == selected.sourceId) { "Media exceeds the captured explicit selection." }
            return host.inspectSelection(selected, pin).also {
                require(it.sourceId == selected.sourceId && it.descriptor == selected) { "Native inspection returned another selected source." }
            }
        }
        val downloadId = if (step.call.name == "inspect_downloaded_media") step.call.arguments["downloadId"]
            else id?.takeIf { it.startsWith("download-") }?.removePrefix("download-")
        val download = requireNotNull(downloadId?.let { downloaded(it) }) { "This plan's typed published download is missing." }
        require(download.id == downloadId && download.sourceUrl in authorization.urls && download.bytes > 0 && download.destination.isNotBlank()) {
            "Downloaded media exceeds this plan's captured native transfer scope."
        }
        return host.inspectDownload(download, pin).also {
            require(it.sourceId == "download-$downloadId" && it.descriptor == download.descriptor()) { "Native inspection returned another downloaded source." }
        }
    }

    private suspend fun canStop() = mayStopOwned?.invoke() ?: !isExecuting()
    private suspend fun stop(receipt: OrezSubtitleReceipt) = if (stopOwned == null) host.pause(receipt) else stopOwned.invoke(receipt)

    companion object {
        private val HASH = Regex("[a-f0-9]{64}")
        fun forPlan(store: OrezTaskStore, plan: OrezTaskPlan, host: OrezSubtitleHost) = OrezSubtitleTools(host,
            requireNotNull(plan.authorization), downloaded = { id -> store.load(plan.id)?.let { OrezSubtitlePlanScope.download(it, id) } },
            stopOwned = { OrezTaskControlFence.stopIfRequested(store, plan.id, it, host) },
            mayStopOwned = { store.load(plan.id)?.let { it.status == OrezTaskStatus.CANCELLED || it.pausedByUser && !it.resuming } == true },
            isExecuting = { store.isExecuting(plan.id, plan.executionEpoch) })
        fun OrezSubtitleDownload.descriptor() = OrezMediaSelection(destination, "orez-download:$id", title.take(250))
        fun verify(receipt: OrezSubtitleReceipt, media: OrezSubtitleSnapshot, options: OrezSubtitleOptions, requestId: String) {
            OrezSubtitleContract.validate(options)
            OrezSubtitleContract.validate(receipt.options)
            require(receipt.ownerRequestId == requestId && receipt.taskId.matches(Regex("[a-f0-9]{32}")) &&
                receipt.generation.matches(Regex("[a-f0-9]{32}"))) { "Native subtitle identity/owner does not match this request." }
            require(receipt.media == media && receipt.media.verifiable && receipt.options == options &&
                receipt.configFingerprint == options.nativeConfig(media.speechModelSha256).fingerprint()) { "Native subtitles belong to another source/model/configuration." }
        }
        fun verifyCompleted(receipt: OrezSubtitleReceipt) {
            OrezSubtitleContract.validate(receipt.options)
            val exports = requireNotNull(receipt.exports) { "Saved SRT and VTT export evidence is missing." }
            require(receipt.status == OrezNativeSubtitleStatus.COMPLETED && !receipt.validationPending && receipt.media.verifiable &&
                receipt.durationMs in 0..21_600_000 && receipt.processedMs in 1..21_600_000 && (receipt.providerCaptionReceipt != null || receipt.processedMs + 1500 >= receipt.durationMs) &&
                receipt.windowCount in 1..4000 && receipt.cueCount in 1..30_000 && exports.srtSha256.matches(HASH) && exports.vttSha256.matches(HASH) &&
                exports.srtBytes in 1..20_000_000 && exports.vttBytes in 1..20_000_000) { "Native subtitle track or saved SRT/VTT is incomplete or unverified." }
            if (receipt.providerCaptionReceipt != null) verifyProviderCompleted(receipt)
            else {
                require(receipt.options.pipeline == SubtitlePipeline.WHISPER_ENGLISH || receipt.audioComplete &&
                    receipt.sourceCueCount in 1..30_000 && receipt.pendingTargetCues == 0) { "Original speech or requested target cues are incomplete." }
                require(receipt.media.speechModelSha256.matches(HASH)) { "The captured speech model is missing." }
                receipt.media.descriptor.verifySubtitleTail(receipt.durationMs, receipt.processedMs)
            }
        }
        private fun verifyProviderCompleted(receipt: OrezSubtitleReceipt) {
            val proof = requireNotNull(receipt.providerCaptionReceipt)
            val inventory = requireNotNull(receipt.media.descriptor.providerCaptions)
            require(receipt.media.descriptor.hasProviderCaptionCandidate() && receipt.options.pipeline == SubtitlePipeline.SOURCE_TRANSLATION &&
                !receipt.audioComplete && proof.taskId == receipt.taskId && proof.generation == receipt.generation &&
                proof.sourceFingerprint == receipt.media.sourceFingerprint && proof.configFingerprint == receipt.configFingerprint &&
                proof.inventorySha256 == receipt.media.captionInventorySha256 && proof.inventorySha256 == inventory.fingerprint() &&
                listOf(proof.sourceFingerprint, proof.configFingerprint, proof.inventorySha256, proof.trackUrlSha256,
                    proof.payloadSha256, proof.cuesSha256).all { it.matches(HASH) } &&
                proof.selectedTrack(inventory) in ProviderCaptionDiscovery.candidates(inventory, receipt.options.sourceLanguage) &&
                proof.cueCount == receipt.sourceCueCount && receipt.pendingTargetCues == 0 && proof.cueCount in 1..30_000 &&
                proof.lastEndMs == receipt.processedMs && receipt.durationMs == (inventory.expectedDurationMs ?: proof.lastEndMs) &&
                (inventory.expectedDurationMs == null || proof.lastEndMs <= inventory.expectedDurationMs + 1500)) {
                "The exact provider document or requested target cues are incomplete."
            }
        }
        fun OrezSubtitleSnapshot.outputs(requestId: String) = mapOf("requestId" to requestId, "sourceId" to sourceId,
            "sourceFingerprint" to sourceFingerprint, "speechModelSha256" to speechModelSha256, "title" to descriptor.label.take(250)) +
            (captionInventorySha256?.let { mapOf("captionInventorySha256" to it) } ?: emptyMap())
        fun OrezSubtitleReceipt.outputs(requestId: String) = media.outputs(requestId) + mapOf(
            "ownerRequestId" to ownerRequestId.orEmpty(), "subtitleTaskId" to taskId, "generation" to generation,
            "targetLanguage" to options.targetLanguage, "sourceLanguage" to options.sourceLanguage, "status" to status.name,
            "durationMs" to durationMs.toString(), "processedMs" to processedMs.toString(), "windowCount" to windowCount.toString(), "cueCount" to cueCount.toString(),
            "srtSha256" to exports?.srtSha256.orEmpty(), "vttSha256" to exports?.vttSha256.orEmpty(),
            "srtBytes" to (exports?.srtBytes ?: 0).toString(), "vttBytes" to (exports?.vttBytes ?: 0).toString(),
            "configFingerprint" to configFingerprint, "pipeline" to options.pipeline.name, "outputMode" to options.outputMode.name,
            "translationPolicy" to options.translationPolicy, "audioComplete" to audioComplete.toString(),
            "sourceCueCount" to sourceCueCount.toString(), "pendingTargetCues" to pendingTargetCues.toString()) +
            (providerCaptionReceipt?.let { mapOf("providerTrackSha256" to it.trackUrlSha256, "providerPayloadSha256" to it.payloadSha256,
                "providerCuesSha256" to it.cuesSha256, "providerLanguage" to it.language,
                "providerKind" to it.kind.name, "providerFormat" to it.format.name) } ?: emptyMap())
    }
}
