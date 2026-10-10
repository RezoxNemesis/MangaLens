package com.mangalens.orez.agent

import com.mangalens.ui.video.SubtitleFormats
import com.mangalens.ui.video.SubtitleGenerationStatus
import com.mangalens.ui.video.SubtitleGenerationTask
import com.mangalens.ui.video.hasSubtitleSourceProof
import com.mangalens.ui.video.hasVerifiedProviderCaptions
import com.mangalens.ui.video.SubtitlePipeline
import com.mangalens.ui.video.SubtitleSourceIdentity
import com.mangalens.ui.video.canTrustSubtitleSource
import kotlinx.coroutines.CancellationException
import java.io.File
import java.security.MessageDigest

/** The native journal is observed; exports are read only from its managed task/generation folder. */
internal object OrezSubtitleNativeEvidence {
    /** A typed repair hook must independently revalidate the exact owned original-speech checkpoint. */
    fun savedSpeechSnapshot(expected: OrezSubtitleReceipt, task: SubtitleGenerationTask, fresh: SubtitleSourceIdentity): OrezSubtitleSnapshot? {
        if (task.config.pipeline != SubtitlePipeline.SOURCE_TRANSLATION || !task.audioComplete || task.pcmValidationRequired ||
            task.id != expected.taskId || task.generation != expected.generation || task.ownerRequestId != expected.ownerRequestId ||
            task.windows.isEmpty() || task.processedMs !in 1..21_600_000 || task.processedMs + 1500 < task.durationMs ||
            task.source.source != fresh.source || !canTrustSubtitleSource(task.source, fresh) ||
            fresh.fingerprint != expected.media.sourceFingerprint || task.windows.any { window ->
                window.cues.isNotEmpty() || if (window.silent) window.sourceCues.isNotEmpty() else window.sourceCues.isEmpty()
            }) return null
        return try {
            val current = metadataReceipt(task, expected.media.sourceId, expected.media.descriptor)
            OrezSubtitleTools.verify(current, expected.media, expected.options, requireNotNull(expected.ownerRequestId))
            expected.media.descriptor.verifySubtitleTail(task.durationMs, task.processedMs)
            current.media
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    /** Stop provenance requires original native identity/configuration, never an export-completion claim. */
    fun metadataReceipt(task: SubtitleGenerationTask, sourceId: String, expectedDescriptor: OrezMediaSelection? = null): OrezSubtitleReceipt {
        val descriptor = if (expectedDescriptor != null) expectedDescriptor.captured().also { expected ->
            require(task.source.source == expected.nativeSubtitleSource() &&
                (!sourceId.startsWith("selected-") || sourceId == expected.sourceId)) { "The native audio descriptor does not match the captured selected source." }
        } else {
            require(!task.source.source.cacheKey.startsWith("orez-audio-pair:")) { "A native split audio receipt needs its captured video/audio selection." }
            task.source.source.let { OrezMediaSelection(it.uri, it.cacheKey, it.label, it.headers.toMap(),
                resolutionId = it.sourceResolutionId, providerCaptions = it.providerCaptions?.captureSnapshot(),
                expectedDurationUs = it.fragmentPlan?.durationUs, videoFragments = it.fragmentPlan?.captured()) }
        }
        val options = task.config.orezOptions().also(OrezSubtitleContract::validate)
        val candidate = task.config.pipeline == SubtitlePipeline.SOURCE_TRANSLATION && descriptor.hasProviderCaptionCandidate()
        val model = task.config.modelSha256 ?: "".also { require(candidate) { "The native subtitle request has no captured speech model or provider caption inventory." } }
        return OrezSubtitleReceipt(task.id, task.generation, task.ownerRequestId,
            OrezSubtitleSnapshot(sourceId, descriptor, task.source.fingerprint, model, hasSubtitleSourceProof(task.source) || candidate || descriptor.hasFragmentSourceCandidate()), options,
            OrezNativeSubtitleStatus.valueOf(task.status.name), task.durationMs, task.processedMs, task.windows.size, task.cues.size,
            validationPending = task.validationPending || task.pcmValidationRequired, error = task.error,
            configFingerprint = task.config.fingerprint(), audioComplete = task.audioComplete,
            sourceCueCount = task.sourceCueCount, pendingTargetCues = task.pendingTargetCues, providerCaptionReceipt = task.providerCaptionReceipt,
            fragmentContentSha256 = task.source.fragmentContentSha256, fragmentSize = task.source.fragmentSize)
    }

    fun receipt(task: SubtitleGenerationTask, sourceId: String, directory: File, expectedDescriptor: OrezMediaSelection? = null): OrezSubtitleReceipt {
        val metadata = metadataReceipt(task, sourceId, expectedDescriptor)
        val targetsComplete = task.config.pipeline == SubtitlePipeline.WHISPER_ENGLISH ||
            (if (task.providerCaptionReceipt != null) hasVerifiedProviderCaptions(task) else task.audioComplete) && task.windows.all { window ->
            window.cues.isEmpty() && if (window.silent) window.sourceCues.isEmpty() && window.translations.isEmpty()
            else window.sourceCues.isNotEmpty() && window.translations.map { it.sourceIndex }.sorted() == window.sourceCues.indices.toList()
        }
        val exports = if (task.status == SubtitleGenerationStatus.COMPLETED && !metadata.validationPending && metadata.media.verifiable && targetsComplete &&
            (task.providerCaptionReceipt != null || hasSubtitleSourceProof(task.source))) {
            val cues = task.cues
            fun validate(path: String?, savedHash: String?, extension: String, expected: String): Pair<String, Long> {
                val folder = File(directory.canonicalFile, task.id)
                require(folder.canonicalFile == folder && task.id.matches(Regex("[a-f0-9]{32}")) && task.generation.matches(Regex("[a-f0-9]{32}")))
                val expectedFile = File(folder, "${task.generation}.$extension")
                val file = File(requireNotNull(path)).canonicalFile
                require(file == expectedFile && file.isFile && file.length() in 1..8_000_000) { "The saved subtitle export is missing or outside its managed generation." }
                val bytes = file.readBytes()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                require(hash == savedHash && bytes.contentEquals(expected.toByteArray(Charsets.UTF_8))) { "The saved subtitle export does not match the verified speech cues." }
                return hash to bytes.size.toLong()
            }
            val srt = validate(task.srtPath, task.srtSha256, "srt", SubtitleFormats.srt(cues))
            val vtt = validate(task.vttPath, task.vttSha256, "vtt", SubtitleFormats.vtt(cues))
            OrezSubtitleExports(srt.first, vtt.first, srt.second, vtt.second)
        } else null
        return metadata.copy(exports = exports)
    }
}
