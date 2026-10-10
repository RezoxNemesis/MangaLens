package com.mangalens.ui.video

import java.io.File
import com.mangalens.core.translation.TranslationStyleProfile
import com.mangalens.download.ProviderCaptionInventory

data class SpeechCue(val startMs: Long, val endMs: Long, val text: String)

data class SubtitleMediaSource(
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
    val cacheKey: String = uri,
    val label: String = "Video",
    val providerCaptions: ProviderCaptionInventory? = null,
    val sourceResolutionId: String? = null,
    val captionDocumentOnly: Boolean = false,
    val fragmentPlan: com.mangalens.download.OriginalFragmentPlan? = null
)

enum class SubtitleOutputMode { TRANSLATED, DUAL }
enum class SubtitlePipeline { WHISPER_ENGLISH, SOURCE_TRANSLATION }
data class SubtitleRefinementPin(val modelId: String, val sha256: String, val bytes: Long)
data class SubtitleSavedRefinement(val model: SubtitleRefinementPin, val promptSha256: String, val outputSha256: String,
    val completion: SubtitleRefinementCompletionEvidence? = null)
data class SubtitleTargetOptions(
    val targetLanguage: String = "en",
    val outputMode: SubtitleOutputMode = SubtitleOutputMode.TRANSLATED,
    val style: String = "natural",
    val customStyle: String = "",
    val localRefinement: Boolean = false,
    val refinementPin: SubtitleRefinementPin? = null,
    val capturedStyle: TranslationStyleProfile? = null,
    val sceneContext: String = "",
    val refinementInputProfileRevision: String? = null
) {
    /** Called only for a new generation action; journal decode/resume uses capture() without upgrading. */
    fun captureForNewRequest(): SubtitleTargetOptions = capture().let { captured ->
        if (captured.localRefinement && captured.refinementInputProfileRevision == null)
            captured.copy(refinementInputProfileRevision = com.mangalens.core.translation.TranslationRefinementPolicy.INPUT_PROFILE_VERSION)
        else captured
    }

    fun capture(): SubtitleTargetOptions = copy(targetLanguage = targetLanguage.trim().lowercase(java.util.Locale.ROOT),
        style = style.trim().lowercase(java.util.Locale.ROOT), customStyle = customStyle.trim(), sceneContext = sceneContext.trim(),
        capturedStyle = capturedStyle ?: if (style.trim().equals("custom", true)) TranslationStyleProfile.custom(customStyle)
            else TranslationStyleProfile.fromId(style))
}

data class SubtitleGenerationConfig(
    val sourceLanguage: String = "auto",
    val targetLanguage: String = "en",
    val style: String = "whisper-english",
    val modelSha256: String? = null,
    val windowSeconds: Int = 8,
    val overlapSeconds: Int = 1,
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
    val outputMode: SubtitleOutputMode = SubtitleOutputMode.TRANSLATED,
    val pipeline: SubtitlePipeline = SubtitlePipeline.WHISPER_ENGLISH,
    val customStyle: String = "",
    val localRefinement: Boolean = false,
    val translationPolicy: String = "whisper-english-v1",
    val capturedStyle: TranslationStyleProfile? = null,
    val refinementPin: SubtitleRefinementPin? = null,
    val sceneContext: String = "",
    val refinementInputProfileRevision: String? = null
) {
    /** Legacy spelling is part of the durable v1 identity; never use data-class toString here. */
    internal fun identityText(): String {
        val legacy = if (pipeline == SubtitlePipeline.WHISPER_ENGLISH)
        "SubtitleGenerationConfig(sourceLanguage=$sourceLanguage, targetLanguage=$targetLanguage, style=$style, modelSha256=$modelSha256, windowSeconds=$windowSeconds, overlapSeconds=$overlapSeconds, threads=$threads)"
    else listOf("source-translation-v1", sourceLanguage, targetLanguage, style, modelSha256.orEmpty(),
        windowSeconds.toString(), overlapSeconds.toString(), threads.toString(), outputMode.name, pipeline.name,
        customStyle, localRefinement.toString(), translationPolicy, capturedStyle?.id.orEmpty(), capturedStyle?.name.orEmpty(),
        capturedStyle?.instruction.orEmpty(), capturedStyle?.preserveHonorifics.toString(), capturedStyle?.preserveNames.toString(),
        capturedStyle?.naturalDialogue.toString(), refinementPin?.modelId.orEmpty(), refinementPin?.sha256.orEmpty(),
        refinementPin?.bytes.toString()).joinToString("|") { "${it.length}:$it" }
        val scene = if (sceneContext.isEmpty()) legacy else legacy + "|scene-context-v1|${sceneContext.length}:$sceneContext"
        return refinementInputProfileRevision?.let { scene + "|localization-input-profile-v1|${it.length}:$it" } ?: scene
    }
    fun fingerprint(): String = java.security.MessageDigest.getInstance("SHA-256").digest(identityText().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun withTarget(options: SubtitleTargetOptions): SubtitleGenerationConfig = options.capture().let { captured -> copy(
        targetLanguage = captured.targetLanguage, style = captured.style, outputMode = captured.outputMode,
        pipeline = SubtitlePipeline.SOURCE_TRANSLATION, customStyle = captured.customStyle,
        localRefinement = captured.localRefinement, translationPolicy = "mlkit-dialogue-v1",
        capturedStyle = captured.capturedStyle, refinementPin = captured.refinementPin, sceneContext = captured.sceneContext,
        refinementInputProfileRevision = captured.refinementInputProfileRevision)
    }
}

data class SubtitleSourceIdentity(val source: SubtitleMediaSource, val fingerprint: String, val verifiable: Boolean = true,
    val strongEtag: String? = null, val networkSize: Long? = null, val networkUrl: String? = null,
    val fragmentContentSha256: String? = null, val fragmentSize: Long? = null)
enum class SubtitleGenerationStatus { QUEUED, RUNNING, PAUSED, COMPLETED, PARTIAL, FAILED, CANCELLED }
data class SubtitleTranslatedCue(val sourceIndex: Int, val text: String, val hindiDraft: String? = null,
    val refinement: SubtitleSavedRefinement? = null, val refinementDraft: String? = null, val refinementCandidate: String? = null, val refinementHindiDraft: String? = null)
data class SubtitleWindow(val index: Int, val startMs: Long, val endMs: Long, val pcmSha256: String,
    val cues: List<SpeechCue> = emptyList(), val silent: Boolean = false,
    val sourceCues: List<SpeechCue> = emptyList(), val detectedLanguage: String? = null,
    val translations: List<SubtitleTranslatedCue> = emptyList(),
    val providerCueSha256: String? = null) {
    internal fun targetComplete(config: SubtitleGenerationConfig): Boolean = config.pipeline == SubtitlePipeline.WHISPER_ENGLISH ||
        silent || sourceCues.isNotEmpty() && translations.size == sourceCues.size
    internal fun rendered(config: SubtitleGenerationConfig): List<SpeechCue> =
        if (config.pipeline == SubtitlePipeline.WHISPER_ENGLISH) cues else translations.sortedBy { it.sourceIndex }.map { target ->
            val original = sourceCues[target.sourceIndex]
            original.copy(text = if (config.outputMode == SubtitleOutputMode.DUAL && original.text.trim() != target.text.trim())
                original.text.trim() + "\n" + target.text.trim() else target.text.trim())
        }
}
data class SubtitleGenerationTask(
    val id: String, val generation: String, val source: SubtitleSourceIdentity, val config: SubtitleGenerationConfig,
    val status: SubtitleGenerationStatus, val windows: List<SubtitleWindow> = emptyList(),
    val durationMs: Long = 0, val detectedLanguage: String? = null, val srtPath: String? = null,
    val srtSha256: String? = null, val vttPath: String? = null, val vttSha256: String? = null,
    val updatedAt: Long = System.currentTimeMillis(), val error: String? = null, val validationPending: Boolean = false,
    val pcmValidationRequired: Boolean = false, val ownerRequestId: String? = null, val audioComplete: Boolean = false,
    val providerCaptionReceipt: ProviderCaptionReceipt? = null
) {
    private val aligned: List<SubtitleAlignedTrack.Pair> by lazy {
        if (validationPending || pcmValidationRequired || config.pipeline != SubtitlePipeline.SOURCE_TRANSLATION) emptyList()
        else if (providerCaptionReceipt != null) providerCaptionPairs(windows) else SubtitleAlignedTrack.pairs(windows)
    }
    val cues: List<SpeechCue> by lazy {
        if (validationPending || pcmValidationRequired) emptyList()
        else if (config.pipeline == SubtitlePipeline.SOURCE_TRANSLATION) SubtitleAlignedTrack.render(aligned, config.outputMode)
        else {
            val all = ArrayList<SpeechCue>()
            windows.forEach { window ->
                val retained = minOf(all.size, 10)
                val tail = SpeechWindowPolicy.append(all.takeLast(retained), window.rendered(config))
                repeat(retained) { all.removeAt(all.lastIndex) }
                all.addAll(tail)
                require(all.size <= SubtitleFormats.MAX_CUES) { "Subtitle cue limit reached." }
            }
            all.toList()
        }
    }
    val sourceCues: List<SpeechCue> by lazy {
        aligned.map { it.original }
    }
    val translatedCueCount: Int get() = windows.sumOf { it.translations.size }
    val sourceCueCount: Int get() = windows.sumOf { it.sourceCues.size }
    val pendingTargetCues: Int get() = if (config.pipeline == SubtitlePipeline.WHISPER_ENGLISH) 0 else sourceCueCount - translatedCueCount
    val processedMs: Long get() = if (providerCaptionReceipt != null) windows.maxOfOrNull { it.endMs } ?: 0 else windows.lastOrNull()?.endMs ?: 0
}

internal data class SubtitleStartResult(val task: SubtitleGenerationTask, val replaced: SubtitleGenerationTask?)
internal class SubtitleOwnedWorkBlocked(val retry: Boolean) : Exception()
internal fun sameSubtitleGeneration(expected: SubtitleGenerationTask, actual: SubtitleGenerationTask?): Boolean =
    actual != null && expected.id == actual.id && expected.generation == actual.generation &&
        expected.ownerRequestId == actual.ownerRequestId && expected.source == actual.source && expected.config == actual.config
internal suspend fun enforceSubtitleOwnerGate(store: SubtitleGenerationStore, captured: SubtitleGenerationTask,
    allow: suspend (SubtitleGenerationTask) -> Boolean) {
    val task = store.get(captured.id)?.takeIf { sameSubtitleGeneration(captured, it) }
        ?: throw SubtitleOwnedWorkBlocked(retry = false)
    val allowed = if (task.ownerRequestId == null) true else try { allow(task) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    // The control lookup can suspend. It grants no right over a replacement,
    // including a resumed generation with the same owner and configuration.
    val current = store.current(task.id, task.generation) && sameSubtitleGeneration(task, store.get(task.id))
    if (!allowed || !current) throw SubtitleOwnedWorkBlocked(retry = current)
}
internal fun SubtitleGenerationConfig.normalized(): SubtitleGenerationConfig = copy(
    sourceLanguage = sourceLanguage.trim().lowercase(java.util.Locale.ROOT),
    targetLanguage = targetLanguage.trim().lowercase(java.util.Locale.ROOT),
    style = style.trim().lowercase(java.util.Locale.ROOT), customStyle = customStyle.trim(), sceneContext = sceneContext.trim())

data class FullSubtitleState(
    val running: Boolean = false, val progress: Float = 0f, val stage: String = "Ready",
    val cues: List<SpeechCue> = emptyList(), val srt: String = "", val outputFile: File? = null,
    val cached: Boolean = false, val error: String? = null, val taskId: String? = null,
    val generation: String? = null, val status: SubtitleGenerationStatus? = null,
    val completedWindows: Int = 0, val vtt: String = "", val vttFile: File? = null,
    val sourceCacheKey: String? = null, val targetLanguage: String = "en",
    val outputMode: SubtitleOutputMode = SubtitleOutputMode.TRANSLATED, val sourceCues: List<SpeechCue> = emptyList(),
    val pendingTargetCues: Int = 0, val pipeline: SubtitlePipeline = SubtitlePipeline.WHISPER_ENGLISH
)

internal object SubtitleFormats {
    const val MAX_CUES = 30_000
    fun normalizeGenerated(cues: List<SpeechCue>): List<SpeechCue> {
        require(cues.size <= MAX_CUES) { "Subtitle cue limit reached." }
        val all = ArrayList<SpeechCue>()
        // The live append policy deliberately retains only its recent tail. Apply it
        // to a small working tail so a full generated track keeps its first cues.
        cues.chunked(64).forEach { incoming ->
            val retained = minOf(all.size, 10)
            val tail = SpeechWindowPolicy.append(all.takeLast(retained), incoming)
            repeat(retained) { all.removeAt(all.lastIndex) }
            all.addAll(tail)
        }
        return all.toList()
    }
    fun append(existing: List<SpeechCue>, incoming: List<SpeechCue>): List<SpeechCue> {
        val retainedTail = minOf(existing.size, 10)
        val merged = existing.dropLast(retainedTail) + SpeechWindowPolicy.append(existing.takeLast(retainedTail), incoming)
        require(merged.size <= MAX_CUES) { "Subtitle cue limit reached. Completed windows have been kept." }
        return merged
    }
    fun srt(cues: List<SpeechCue>): String = cues.mapIndexed { index, cue ->
        "${index + 1}\n${timestamp(cue.startMs, ',')} --> ${timestamp(cue.endMs, ',')}\n${cue.text.trim()}\n"
    }.joinToString("\n")
    fun vtt(cues: List<SpeechCue>): String = "WEBVTT\n\n" + cues.joinToString("\n") { cue ->
        "${timestamp(cue.startMs, '.')} --> ${timestamp(cue.endMs, '.')}\n${cue.text.trim()}\n"
    }
    private fun timestamp(ms: Long, separator: Char): String {
        val t = ms.coerceAtLeast(0)
        return "%02d:%02d:%02d%c%03d".format(java.util.Locale.ROOT, t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, separator, t % 1000)
    }
}

internal fun SubtitleMediaSource.captureSnapshot(): SubtitleMediaSource = copy(headers = headers.toMap(), providerCaptions = providerCaptions?.captureSnapshot(), fragmentPlan = fragmentPlan?.captured())

internal class SubtitleGenerationRequest(val token: Long, source: SubtitleMediaSource, val sourceLanguage: String, targetOptions: SubtitleTargetOptions? = null) {
    val source = source.captureSnapshot()
    val targetOptions = targetOptions?.capture()
    @Volatile var control: String? = null
    @Volatile var receipt: SubtitleGenerationTask? = null
}

internal class SubtitlePublicationGate {
    private var epoch = 0L
    @Synchronized fun advance(): Long = ++epoch
    @Synchronized fun current(): Long = epoch
    @Synchronized fun publish(token: Long, action: () -> Unit): Boolean {
        if (token != epoch) return false
        action()
        return true
    }
    @Synchronized fun <T> capture(action: (Long) -> T): T = action(epoch)
}

internal suspend fun runSubtitleControl(action: suspend () -> Unit, accepted: () -> Boolean, onFailure: (String) -> Unit) {
    try { action() }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (failure: Exception) {
        if (accepted()) onFailure((failure.message ?: "Unable to control subtitle generation. Try again.").take(300))
    }
}

internal enum class SubtitleSourceCheck { MATCH, CHANGED, UNVERIFIED }
internal fun hasSubtitleSourceProof(identity: SubtitleSourceIdentity): Boolean = identity.verifiable &&
    (if (identity.source.fragmentPlan != null) identity.source.fragmentPlan.sourceUrl == identity.source.uri &&
        identity.source.sourceResolutionId?.matches(Regex("[a-f0-9]{32}")) == true && identity.fragmentContentSha256?.matches(Regex("[a-f0-9]{64}")) == true &&
        identity.fragmentSize?.let { it in 1..4L * 1024 * 1024 * 1024 } == true
    else identity.source.uri.substringBefore(':').lowercase(java.util.Locale.ROOT) !in setOf("http", "https") ||
        identity.strongEtag?.let(::isStrongSubtitleEtag) == true && identity.networkSize?.let { it > 0 } == true && identity.networkUrl != null)
internal fun canTrustSubtitleSource(expected: SubtitleSourceIdentity, actual: SubtitleSourceIdentity): Boolean =
    hasSubtitleSourceProof(expected) && hasSubtitleSourceProof(actual) && expected.fingerprint == actual.fingerprint &&
        expected.strongEtag == actual.strongEtag && expected.networkSize == actual.networkSize && expected.networkUrl == actual.networkUrl &&
        expected.fragmentContentSha256 == actual.fragmentContentSha256 && expected.fragmentSize == actual.fragmentSize
internal fun assessSubtitleSource(expected: SubtitleSourceIdentity, actual: SubtitleSourceIdentity): SubtitleSourceCheck =
    when {
        expected.source.fragmentPlan != null && hasSubtitleSourceProof(expected) && hasSubtitleSourceProof(actual) &&
            (expected.fragmentContentSha256 != actual.fragmentContentSha256 || expected.fragmentSize != actual.fragmentSize) -> SubtitleSourceCheck.CHANGED
        expected.fingerprint == actual.fingerprint -> SubtitleSourceCheck.MATCH
        !hasSubtitleSourceProof(expected) || !hasSubtitleSourceProof(actual) -> SubtitleSourceCheck.UNVERIFIED
        else -> SubtitleSourceCheck.CHANGED
    }

internal data class SubtitleExportReceipt(val taskId: String, val generation: String, val sourceCacheKey: String,
    val format: String, val text: String) {
    suspend fun write(open: suspend () -> java.io.OutputStream?) {
        open()?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("Cannot write subtitle file")
    }
    companion object {
        fun capture(state: FullSubtitleState, source: SubtitleMediaSource, format: String): SubtitleExportReceipt? {
            val text = if (format == "vtt") state.vtt else state.srt
            val targetIncomplete = state.pipeline == SubtitlePipeline.SOURCE_TRANSLATION &&
                (state.status != SubtitleGenerationStatus.COMPLETED || state.pendingTargetCues != 0)
            return if (targetIncomplete || text.isBlank() || state.cues.isEmpty() || state.sourceCacheKey != source.cacheKey) null
            else SubtitleExportReceipt(state.taskId ?: return null, state.generation ?: return null, source.cacheKey, format, text)
        }
    }
}

internal class SubtitleAttachmentReceipt {
    private var source: SubtitleMediaSource? = null
    private var generation: String? = null
    fun bind(next: SubtitleMediaSource) {
        val captured = next.captureSnapshot()
        if (source != captured) generation = null
        source = captured
    }
    fun shouldAttach(nextGeneration: String) = generation != nextGeneration
    fun attached(nextGeneration: String) { generation = nextGeneration }
}
