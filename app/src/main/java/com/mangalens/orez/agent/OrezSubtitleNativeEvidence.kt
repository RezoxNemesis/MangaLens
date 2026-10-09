package com.mangalens.orez.agent

import com.mangalens.ui.video.SubtitleFormats
import com.mangalens.ui.video.SubtitleGenerationStatus
import com.mangalens.ui.video.SubtitleGenerationTask
import com.mangalens.ui.video.hasSubtitleSourceProof
import java.io.File
import java.security.MessageDigest

/** The native journal is observed; exports are read only from its managed task/generation folder. */
internal object OrezSubtitleNativeEvidence {
    /** Stop provenance requires original native identity/configuration, never an export-completion claim. */
    fun metadataReceipt(task: SubtitleGenerationTask, sourceId: String): OrezSubtitleReceipt {
        val descriptor = task.source.source.let { OrezMediaSelection(it.uri, it.cacheKey, it.label, it.headers.toMap()) }
        val options = task.config.let { OrezSubtitleOptions(it.sourceLanguage, it.targetLanguage, it.style, it.windowSeconds, it.overlapSeconds, it.threads) }
        val model = requireNotNull(task.config.modelSha256) { "The native subtitle request has no captured speech model." }
        return OrezSubtitleReceipt(task.id, task.generation, task.ownerRequestId,
            OrezSubtitleSnapshot(sourceId, descriptor, task.source.fingerprint, model, hasSubtitleSourceProof(task.source)), options,
            OrezNativeSubtitleStatus.valueOf(task.status.name), task.durationMs, task.processedMs, task.windows.size, task.cues.size,
            validationPending = task.validationPending || task.pcmValidationRequired, error = task.error)
    }

    fun receipt(task: SubtitleGenerationTask, sourceId: String, directory: File): OrezSubtitleReceipt {
        val metadata = metadataReceipt(task, sourceId)
        val exports = if (task.status == SubtitleGenerationStatus.COMPLETED && !metadata.validationPending && metadata.media.verifiable) {
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
