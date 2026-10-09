package com.mangalens.ui.video

import com.mangalens.download.ProviderCaptionFormat
import com.mangalens.download.ProviderCaptionDiscovery
import com.mangalens.download.ProviderCaptionInventory
import com.mangalens.download.ProviderCaptionKind
import com.mangalens.download.ProviderCaptionTrack
import com.mangalens.download.ProviderCaptionUrlPolicy
import com.mangalens.download.normalizeCaptionLanguage
import java.security.MessageDigest

internal class ProviderCaptionChangedException : java.io.IOException("Provider captions changed. Regenerate subtitles for the current source; saved cues have been kept.")

/** Complete provider document evidence. It proves caption text/times, never decoded PCM or ASR. */
data class ProviderCaptionReceipt(
    val taskId: String,
    val generation: String,
    val sourceFingerprint: String,
    val configFingerprint: String,
    val inventorySha256: String,
    val trackUrlSha256: String,
    val language: String,
    val kind: ProviderCaptionKind,
    val format: ProviderCaptionFormat,
    val payloadSha256: String,
    val cuesSha256: String,
    val cueCount: Int,
    val lastEndMs: Long
)

internal fun ProviderCaptionInventory.identityText(): String = listOf(sourcePageUrl, videoId.orEmpty(), originalLanguage.orEmpty(),
    selectedAudioLanguage.orEmpty(), expectedDurationMs?.toString().orEmpty(), tracks.joinToString("|") { track ->
        frame(listOf(track.url, track.language, track.kind.name, track.format.name, track.originalAutomatic.toString()))
    }).let(::frame)

internal fun ProviderCaptionInventory.fingerprint(): String = providerHash(identityText())
internal fun ProviderCaptionInventory.validate() {
    require(tracks.size in 1..128 && videoId?.length?.let { it !in 1..100 } != true &&
        expectedDurationMs?.let { it !in 1..21_600_000 } != true)
    require(listOfNotNull(originalLanguage, selectedAudioLanguage).all { normalizeCaptionLanguage(it) == it })
    require(tracks.distinct().size == tracks.size && tracks.all {
        normalizeCaptionLanguage(it.language) == it.language && ProviderCaptionUrlPolicy.accepts(sourcePageUrl, videoId, it.url, it.language)
    }) { "The captured provider caption inventory is invalid." }
}

internal fun providerReceipt(task: SubtitleGenerationTask, track: ProviderCaptionTrack, document: ProviderCaptionDocument): ProviderCaptionReceipt {
    val inventory = requireNotNull(task.source.source.providerCaptions)
    inventory.validate()
    require(track in inventory.tracks && document.cues.isNotEmpty())
    return ProviderCaptionReceipt(task.id, task.generation, task.source.fingerprint, task.config.fingerprint(), inventory.fingerprint(),
        providerHash(track.url), track.language, track.kind, track.format, document.payloadSha256, document.cuesSha256,
        document.cues.size, document.cues.maxOf { it.endMs })
}

internal fun ProviderCaptionReceipt.selectedTrack(inventory: ProviderCaptionInventory): ProviderCaptionTrack? = inventory.tracks.singleOrNull {
    providerHash(it.url) == trackUrlSha256 && it.language == language && it.kind == kind && it.format == format
}

internal fun ProviderCaptionReceipt.sameDocument(other: ProviderCaptionReceipt): Boolean =
    copy(generation = other.generation) == other

internal fun providerCaptionWindows(document: ProviderCaptionDocument, language: String): List<SubtitleWindow> = document.cues.chunked(64).mapIndexed { index, part ->
    val cues = part.map { SpeechCue(it.startMs, it.endMs, it.text) }
    SubtitleWindow(index, cues.minOf { it.startMs }, cues.maxOf { it.endMs }, "", sourceCues = cues,
        detectedLanguage = language.substringBefore('-'), providerCueSha256 = providerWindowHash(index, cues))
}

internal fun providerWindowHash(index: Int, cues: List<SpeechCue>): String = providerHash(frame(listOf(index.toString(),
    ProviderCaptionParser.cueHash(cues.map { ProviderCaptionCue(it.startMs, it.endMs, it.text) }))))

internal fun providerCaptionPairs(windows: List<SubtitleWindow>): List<SubtitleAlignedTrack.Pair> = windows.flatMap { window ->
    val translated = window.translations.associateBy { it.sourceIndex }
    window.sourceCues.mapIndexed { index, original -> SubtitleAlignedTrack.Pair(original, translated[index]?.text) }
}

internal fun validProviderCaptionTask(task: SubtitleGenerationTask, allowUnvalidatedGeneration: Boolean = false): Boolean = runCatching {
    val receipt = task.providerCaptionReceipt ?: return false
    val inventory = task.source.source.providerCaptions ?: return false
    inventory.validate()
    val hash = Regex("[a-f0-9]{64}")
    require(task.config.pipeline == SubtitlePipeline.SOURCE_TRANSLATION && !task.audioComplete &&
        task.source.source.sourceResolutionId?.matches(Regex("[a-f0-9]{32}")) == true &&
        receipt.taskId == task.id && receipt.sourceFingerprint == task.source.fingerprint &&
        receipt.configFingerprint == task.config.fingerprint() && receipt.inventorySha256 == inventory.fingerprint())
    require(receipt.generation == task.generation || allowUnvalidatedGeneration && task.validationPending && receipt.generation.matches(Regex("[a-f0-9]{32}")))
    require(listOf(receipt.sourceFingerprint, receipt.configFingerprint, receipt.inventorySha256, receipt.trackUrlSha256,
        receipt.payloadSha256, receipt.cuesSha256).all { it.matches(hash) })
    val track = requireNotNull(receipt.selectedTrack(inventory))
    require(track in ProviderCaptionDiscovery.candidates(inventory, task.config.sourceLanguage))
    require(task.windows.size in 1..4000 && receipt.cueCount in 1..30_000 && receipt.lastEndMs in 1..21_600_000)
    val all = ArrayList<ProviderCaptionCue>()
    task.windows.forEachIndexed { index, window ->
        require(window.index == index && window.pcmSha256.isEmpty() && window.cues.isEmpty() && !window.silent &&
            window.sourceCues.size in 1..64 && window.translations.size <= window.sourceCues.size &&
            window.detectedLanguage == receipt.language.substringBefore('-') &&
            window.providerCueSha256 == providerWindowHash(index, window.sourceCues))
        require(window.startMs == window.sourceCues.minOf { it.startMs } && window.endMs == window.sourceCues.maxOf { it.endMs })
        window.sourceCues.forEach { cue ->
            require(cue.startMs >= 0 && cue.endMs > cue.startMs && cue.endMs <= 21_600_000 && cue.text.length in 1..4000 && cue.text.isNotBlank())
            require(all.lastOrNull()?.startMs?.let { cue.startMs >= it } != false)
            all += ProviderCaptionCue(cue.startMs, cue.endMs, cue.text)
        }
        require(window.translations.map { it.sourceIndex }.distinct().size == window.translations.size &&
            window.translations.all { acceptsSubtitleTarget(task, window, it) })
    }
    require(all.size == receipt.cueCount && all.maxOf { it.endMs } == receipt.lastEndMs &&
        ProviderCaptionParser.cueHash(all) == receipt.cuesSha256 &&
        task.durationMs == (inventory.expectedDurationMs ?: receipt.lastEndMs) &&
        (inventory.expectedDurationMs == null || receipt.lastEndMs <= inventory.expectedDurationMs + 1500))
    true
}.getOrDefault(false)

fun hasVerifiedProviderCaptions(task: SubtitleGenerationTask): Boolean = !task.validationPending && !task.pcmValidationRequired && validProviderCaptionTask(task)
internal fun hasSubtitlePlaybackProof(task: SubtitleGenerationTask): Boolean =
    if (task.providerCaptionReceipt != null) hasVerifiedProviderCaptions(task) else hasSubtitleSourceProof(task.source)

private fun frame(values: List<String>): String = values.joinToString("|") { "${it.length}:$it" }
internal fun providerHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
