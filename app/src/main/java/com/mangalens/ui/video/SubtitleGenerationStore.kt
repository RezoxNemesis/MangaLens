package com.mangalens.ui.video

import android.content.Context
import com.mangalens.core.translation.TranslationStyleProfile
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal interface SubtitleJournalIo {
    fun read(file: File): ByteArray
    fun write(file: File, bytes: ByteArray)
}
private object AndroidSubtitleJournalIo : SubtitleJournalIo {
    override fun read(file: File): ByteArray = AtomicFile(file).openRead().use {
        require(it.channel.size() <= SubtitleGenerationStore.MAX_BYTES)
        it.readBytes()
    }
    override fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }
}

class SubtitleGenerationStore internal constructor(private val directory: File,
    private val io: SubtitleJournalIo = AndroidSubtitleJournalIo) {
    private val tasks = LinkedHashMap<String, SubtitleGenerationTask>()
    private val mutable = MutableStateFlow<List<SubtitleGenerationTask>>(emptyList())
    val states: StateFlow<List<SubtitleGenerationTask>> = mutable
    init {
        check(directory.isDirectory || directory.mkdirs())
        journalFiles().sortedByDescending { it.lastModified() }.take(32).forEach { file ->
            runCatching {
                val bytes = io.read(file); require(bytes.size <= MAX_BYTES)
                val task = decode(JSONObject(String(bytes, Charsets.UTF_8)))
                require(file.name == task.id + ".json")
                val checked = validateExports(task)
                checked.copy(status = if (checked.status == SubtitleGenerationStatus.RUNNING) SubtitleGenerationStatus.QUEUED else checked.status,
                    validationPending = true)
            }.getOrNull()?.let { tasks[it.id] = it }
        }
        publish()
    }
    @Synchronized fun get(id: String): SubtitleGenerationTask? = tasks[id]
    @Synchronized fun latest(cacheKey: String): SubtitleGenerationTask? = tasks.values.filter { it.source.source.cacheKey == cacheKey }.maxByOrNull { it.updatedAt }
    @Synchronized internal fun find(source: SubtitleSourceIdentity, config: SubtitleGenerationConfig): SubtitleGenerationTask? =
        tasks[identity(source, config.normalized())]?.let(::validateExports)?.let { checked ->
            // A previous decode verified that request, not a later reopen of a URL
            // without a stable validator. Keep its windows but require fresh PCM checks.
            if (!canTrustSubtitleSource(checked.source, source) && checked.windows.isNotEmpty())
                checked.copy(validationPending = true, pcmValidationRequired = true) else checked
        }?.also { checked -> if (checked != tasks[checked.id]) save(checked) }
    /** A generation precondition is checked before any verification or journal mutation. */
    @Synchronized fun refresh(id: String, generation: String? = null): SubtitleGenerationTask? = tasks[id]?.takeIf {
        generation == null || it.generation == generation
    }?.let(::validateExports)?.also { if (it != tasks[id]) save(it) }
    @Synchronized fun exportVerified(id: String, generation: String): SubtitleGenerationTask? = refresh(id, generation)?.takeIf {
        it.status == SubtitleGenerationStatus.COMPLETED && !it.validationPending && !it.pcmValidationRequired &&
            hasSubtitleSourceProof(it.source) && it.srtPath != null && it.vttPath != null
    }
    fun start(source: SubtitleSourceIdentity, config: SubtitleGenerationConfig, force: Boolean = false,
        ownerRequestId: String? = null, allowOwnerReplacement: Boolean = true): SubtitleGenerationTask =
        startResult(source, config, force, ownerRequestId, allowOwnerReplacement).task

    @Synchronized internal fun startResult(requestedSource: SubtitleSourceIdentity, requestedConfig: SubtitleGenerationConfig,
        force: Boolean = false, ownerRequestId: String? = null, allowOwnerReplacement: Boolean = true): SubtitleStartResult {
        val source = requestedSource.copy(source = requestedSource.source.captureSnapshot())
        val config = requestedConfig.normalized()
        validateSource(source); validateConfig(config)
        validateOwner(ownerRequestId)
        val id = identity(source, config)
        val existing = tasks[id]
        check(allowOwnerReplacement || existing == null || existing.ownerRequestId == ownerRequestId) {
            "The subtitle request was replaced by another owner. The newer request has been kept."
        }
        // Ownership is checked before export inspection can publish a repair state.
        val old = existing?.let(::validateExports)
        val trusted = old?.let { canTrustSubtitleSource(it.source, source) } == true
        val ownedReplay = ownerRequestId != null && old != null && old.ownerRequestId == ownerRequestId && old.source == source && old.config == config
        if (old != null && old.ownerRequestId == ownerRequestId && (ownedReplay || !force && old.source == source &&
            (trusted || old.status != SubtitleGenerationStatus.COMPLETED))) {
            val verified = old.copy(validationPending = old.pcmValidationRequired || !trusted && old.windows.isNotEmpty(),
                pcmValidationRequired = old.pcmValidationRequired || !trusted && old.windows.isNotEmpty())
            if (verified != tasks[id]) save(verified)
            return SubtitleStartResult(verified, null)
        }
        require(id in tasks || tasks.size < 32 && journalFiles().size < 32) { "Subtitle history is full. Existing subtitles have been kept." }
        // Reuse only real source-language ASR from the same proof-bound source/model/decode options.
        // English-translated legacy windows are deliberately ineligible.
        val donor = if (force || old != null || config.pipeline != SubtitlePipeline.SOURCE_TRANSLATION) null else tasks.values
            .filter { candidate -> candidate.config.pipeline == SubtitlePipeline.SOURCE_TRANSLATION && candidate.audioComplete &&
                !candidate.pcmValidationRequired && candidate.source == source && canTrustSubtitleSource(candidate.source, source) &&
                candidate.config.sourceLanguage == config.sourceLanguage && candidate.config.modelSha256 == config.modelSha256 &&
                candidate.config.windowSeconds == config.windowSeconds && candidate.config.overlapSeconds == config.overlapSeconds &&
                candidate.config.threads == config.threads }.maxByOrNull { it.updatedAt }
        val retainedWindows = if (force) emptyList() else old?.windows ?: donor?.windows?.map { it.copy(translations = emptyList()) }.orEmpty()
        val task = SubtitleGenerationTask(id, token(), source.copy(source = source.source.copy(headers = source.source.headers.toMap())), config,
            if (config.modelSha256 == null) SubtitleGenerationStatus.FAILED else SubtitleGenerationStatus.QUEUED,
            windows = retainedWindows,
            durationMs = if (force) 0 else old?.durationMs ?: donor?.durationMs ?: 0,
            detectedLanguage = if (force) null else old?.detectedLanguage ?: donor?.detectedLanguage,
            audioComplete = !force && (old?.audioComplete == true || donor?.audioComplete == true),
            validationPending = !force && old?.windows?.isNotEmpty() == true && (!trusted || old.pcmValidationRequired),
            pcmValidationRequired = !force && old?.windows?.isNotEmpty() == true && (!trusted || old.pcmValidationRequired),
            error = if (config.modelSha256 == null) "Install or import a multilingual Whisper model first." else null, ownerRequestId = ownerRequestId)
        save(task); cleanGeneratedExports(task.id); return SubtitleStartResult(task, existing)
    }
    @Synchronized internal fun running(id: String, generation: String): SubtitleGenerationTask? = active(id, generation)
        ?.copy(status = SubtitleGenerationStatus.RUNNING, error = null, updatedAt = System.currentTimeMillis())?.also(::save)
    @Synchronized internal fun checkpoint(id: String, generation: String, window: SubtitleWindow, durationMs: Long,
        detectedLanguage: String? = null): Boolean {
        val task = active(id, generation) ?: return false
        val savedWindow = window.copy(cues = window.cues.toList(), sourceCues = window.sourceCues.toList(), translations = window.translations.toList())
        validateWindow(savedWindow, task.config, task.copy(windows = task.windows + savedWindow))
        require(window.index == task.windows.size && window.index < 4000) { "Speech windows must be checkpointed once in order." }
        require(window.startMs >= (task.windows.lastOrNull()?.startMs ?: 0) && durationMs in 0..MAX_DURATION)
        val result = task.copy(windows = task.windows + savedWindow, durationMs = durationMs,
            detectedLanguage = detectedLanguage ?: task.detectedLanguage, updatedAt = System.currentTimeMillis())
        require(result.windows.sumOf { if (task.config.pipeline == SubtitlePipeline.WHISPER_ENGLISH) it.cues.size else it.sourceCues.size } <= SubtitleFormats.MAX_CUES)
        save(result); return true
    }
    @Synchronized internal fun checkpointTarget(id: String, generation: String, windowIndex: Int, pcmSha256: String,
        target: SubtitleTranslatedCue): Boolean {
        val task = active(id, generation) ?: return false
        require(task.config.pipeline == SubtitlePipeline.SOURCE_TRANSLATION)
        val window = task.windows.getOrNull(windowIndex) ?: error("Original speech has not been checkpointed.")
        require(window.pcmSha256 == pcmSha256) { "Source audio changed before target checkpoint." }
        require(acceptsSubtitleTarget(task, window, target)) { "Subtitle translation failed language, quality or refinement proof checks." }
        window.translations.firstOrNull { it.sourceIndex == target.sourceIndex }?.let { existing ->
            require(existing == target) { "A verified target cue cannot be silently replaced." }
            return true
        }
        val replacement = window.copy(translations = (window.translations + target).sortedBy { it.sourceIndex })
        val windows = task.windows.toMutableList().also { it[windowIndex] = replacement }
        save(task.copy(windows = windows.toList(), updatedAt = System.currentTimeMillis())); return true
    }
    @Synchronized internal fun completeAudio(id: String, generation: String, decodedWindows: Int): SubtitleGenerationTask? = active(id, generation)?.let { task ->
        require(decodedWindows == task.windows.size && task.windows.isNotEmpty()) { "Not all source windows reached their checkpoint." }
        check(task.durationMs == 0L || task.processedMs + 1500 >= task.durationMs) { "Audio has not reached the end." }
        task.copy(audioComplete = true, updatedAt = System.currentTimeMillis()).also(::save)
    }
    @Synchronized internal fun pause(id: String, generation: String): SubtitleGenerationTask? = active(id, generation)
        ?.copy(status = SubtitleGenerationStatus.PAUSED, updatedAt = System.currentTimeMillis())?.also(::save)
    @Synchronized internal fun resume(id: String, generation: String): SubtitleGenerationTask? = tasks[id]?.takeIf {
        it.generation == generation && it.status in setOf(SubtitleGenerationStatus.PAUSED, SubtitleGenerationStatus.PARTIAL, SubtitleGenerationStatus.FAILED)
    }?.copy(generation = token(), status = SubtitleGenerationStatus.QUEUED, error = null, updatedAt = System.currentTimeMillis())?.also {
        save(it); cleanGeneratedExports(it.id)
    }
    @Synchronized internal fun cancel(id: String, generation: String): SubtitleGenerationTask? = tasks[id]?.takeIf {
        it.generation == generation && it.status != SubtitleGenerationStatus.CANCELLED
    }?.copy(status = SubtitleGenerationStatus.CANCELLED, error = null, updatedAt = System.currentTimeMillis())?.also(::save)
    @Synchronized internal fun current(id: String, generation: String): Boolean = active(id, generation) != null
    @Synchronized internal fun commandSnapshot(captured: SubtitleGenerationTask): SubtitleGenerationTask =
        tasks[captured.id]?.takeIf { it.generation == captured.generation } ?: captured
    @Synchronized internal fun confirmValidated(id: String, generation: String, pcmVerified: Boolean = false): SubtitleGenerationTask? =
        tasks[id]?.takeIf { it.generation == generation }?.let { task ->
            if (task.pcmValidationRequired && !pcmVerified) task
            else task.copy(validationPending = false, pcmValidationRequired = false).also(::save)
        }
    @Synchronized internal fun fail(id: String, generation: String, message: String, invalidate: Boolean = false,
        requireValidation: Boolean = false): SubtitleGenerationTask? = active(id, generation)?.let {
        it.copy(status = if (!invalidate && it.windows.any { window -> window.cues.isNotEmpty() || window.sourceCues.isNotEmpty() }) SubtitleGenerationStatus.PARTIAL else SubtitleGenerationStatus.FAILED,
            windows = if (invalidate) emptyList() else it.windows, audioComplete = !invalidate && it.audioComplete,
            validationPending = if (invalidate) false else it.validationPending || requireValidation,
            pcmValidationRequired = !invalidate && (it.pcmValidationRequired || requireValidation), error = message.take(300),
            srtPath = null, srtSha256 = null, vttPath = null, vttSha256 = null, updatedAt = System.currentTimeMillis()).also(::save)
    }
    @Synchronized internal fun noteTargetFailure(id: String, generation: String, message: String) {
        active(id, generation)?.copy(error = message.take(300), updatedAt = System.currentTimeMillis())?.let(::save)
    }
    @Synchronized internal fun interrupted(id: String, generation: String) { active(id, generation)?.copy(status = SubtitleGenerationStatus.QUEUED)?.let(::save) }

    /** Both exports are durable before their checksums enter the atomic task manifest. */
    @Synchronized internal fun finish(id: String, generation: String): SubtitleGenerationTask? {
        val task = active(id, generation) ?: return null
        check(task.durationMs == 0L || task.processedMs + 1500 >= task.durationMs) { "Audio has not reached the end. Completed windows have been kept." }
        if (task.config.pipeline == SubtitlePipeline.SOURCE_TRANSLATION &&
            (!task.audioComplete || task.windows.any { !it.targetComplete(task.config) }))
            return fail(id, generation, task.error ?: if (!task.audioComplete) "Original speech has not reached the end. Saved speech and translations have been kept."
                else "${task.pendingTargetCues} speech cues still need successful translation. Saved speech and translations have been kept.")
        val verified = task.copy(validationPending = false, pcmValidationRequired = false)
        val cues = verified.cues
        if (cues.isEmpty()) return fail(id, generation, "No clear speech was recognised. Check the audio track or use a larger multilingual model.")
        val folder = File(directory, id)
        require(folder.canonicalFile == File(directory.canonicalFile, id)); check(folder.isDirectory || folder.mkdirs())
        val srt = File(folder, "$generation.srt"); val vtt = File(folder, "$generation.vtt")
        io.write(srt, SubtitleFormats.srt(cues).toByteArray(Charsets.UTF_8))
        io.write(vtt, SubtitleFormats.vtt(cues).toByteArray(Charsets.UTF_8))
        return verified.copy(status = SubtitleGenerationStatus.COMPLETED, srtPath = srt.absolutePath, srtSha256 = fileHash(srt),
            vttPath = vtt.absolutePath, vttSha256 = fileHash(vtt), error = null, updatedAt = System.currentTimeMillis()).also(::save)
    }
    private fun cleanGeneratedExports(id: String) {
        val folder = File(directory, id)
        if (!folder.isDirectory || folder.canonicalFile != File(directory.canonicalFile, id)) return
        val retained = tasks.values.flatMap { listOfNotNull(it.srtPath, it.vttPath) }.toSet()
        val task = tasks[id]
        folder.listFiles().orEmpty().forEach { file ->
            val name = file.name.removeSuffix(".new").removeSuffix(".bak")
            if (!name.matches(Regex("[a-f0-9]{32}\\.(srt|vtt)"))) return@forEach
            if (File(folder, name).absolutePath in retained) return@forEach
            if (name.substringBefore('.') == task?.generation && task.status in setOf(SubtitleGenerationStatus.QUEUED, SubtitleGenerationStatus.RUNNING)) return@forEach
            // Unlink only our generated filename; do not follow a symlink or recurse.
            file.delete()
        }
    }

    private fun active(id: String, generation: String) = tasks[id]?.takeIf { it.generation == generation &&
        it.status in setOf(SubtitleGenerationStatus.QUEUED, SubtitleGenerationStatus.RUNNING) }
    private fun validateExports(task: SubtitleGenerationTask): SubtitleGenerationTask {
        if (task.srtPath == null && task.vttPath == null && task.status != SubtitleGenerationStatus.COMPLETED) return task
        val valid = runCatching {
            require(task.config.pipeline == SubtitlePipeline.WHISPER_ENGLISH || task.audioComplete && task.windows.all { it.targetComplete(task.config) })
            val cues = task.copy(validationPending = false, pcmValidationRequired = false).cues
            listOf(Triple(task.srtPath, task.srtSha256, "srt"), Triple(task.vttPath, task.vttSha256, "vtt")).all { (path, hash, extension) ->
                val file = managedExport(path ?: error("Missing subtitle export"), task.id)
                require(file.name == "${task.generation}.$extension" && file.isFile && file.length() in 1..MAX_BYTES)
                val bytes = file.readBytes(); require(bytes.size <= MAX_BYTES)
                val expected = if (extension == "srt") SubtitleFormats.srt(cues) else SubtitleFormats.vtt(cues)
                hash == digest(bytes) && bytes.contentEquals(expected.toByteArray(Charsets.UTF_8))
            }
        }.getOrDefault(false)
        return if (valid) task else task.copy(status = SubtitleGenerationStatus.PARTIAL, srtPath = null, srtSha256 = null,
            vttPath = null, vttSha256 = null, error = "Saved subtitle export is missing or corrupt. Resume to rebuild it.")
    }
    private fun managedExport(path: String, id: String): File = File(path).canonicalFile.also {
        require(it.parentFile == File(directory.canonicalFile, id) && it.name.matches(Regex("[a-f0-9]{32}\\.(srt|vtt)")))
    }
    private fun validateSource(source: SubtitleSourceIdentity) {
        require(source.fingerprint.matches(HASH) && source.source.uri.length in 1..16000 && source.source.cacheKey.length in 1..16000)
        require(source.source.label.length <= 250 && source.source.headers.size <= 32 && source.source.headers.all {
            it.key.length in 1..128 && it.value.length <= 8192 && it.key.none(Char::isISOControl) && it.value.none { char -> char == '\n' || char == '\r' }
        })
        require(source.strongEtag == null || isStrongSubtitleEtag(source.strongEtag))
        require(source.networkSize == null || source.networkSize > 0)
        require((source.strongEtag == null) == (source.networkSize == null))
        require(source.networkUrl == null || source.networkUrl.length in 1..16000 &&
            source.networkUrl.substringBefore(':').lowercase(java.util.Locale.ROOT) in setOf("http", "https"))
    }
    private fun validateConfig(config: SubtitleGenerationConfig) {
        if (config.pipeline == SubtitlePipeline.WHISPER_ENGLISH) {
            require(config.targetLanguage == "en" && config.style == "whisper-english" && config.outputMode == SubtitleOutputMode.TRANSLATED &&
                config.customStyle.isEmpty() && !config.localRefinement && config.translationPolicy == "whisper-english-v1" &&
                config.capturedStyle == null && config.refinementPin == null) { "Legacy Whisper jobs produce translated English only." }
        } else {
            require(config.targetLanguage in setOf("en", "hi", "hi-latn") && config.translationPolicy == "mlkit-dialogue-v1") { "Unsupported subtitle target or provider policy." }
            require(config.style in setOf("natural", "faithful", "casual", "formal", "webtoon", "custom")) { "Unsupported subtitle style." }
            require(config.customStyle.length <= TranslationStyleProfile.MAX_CUSTOM_INSTRUCTION_CHARS)
            val captured = requireNotNull(config.capturedStyle) { "Capture the subtitle style before scheduling." }
            require(captured.id == config.style && captured.name.length in 1..100 && captured.instruction.length in 1..1200)
            require(config.style != "custom" || config.customStyle.isNotBlank() && captured.instruction == config.customStyle)
            require(config.style == "custom" || config.customStyle.isEmpty())
            config.refinementPin?.let { require(it.modelId.length in 1..100 && it.sha256.matches(HASH) && it.bytes in 1_000_000..4_000_000_000L) }
            require(config.localRefinement || config.refinementPin == null) { "A disabled refiner cannot own a model pin." }
        }
        require(config.sourceLanguage.matches(Regex("auto|[a-z]{2,3}")) && (config.modelSha256 == null || config.modelSha256.matches(HASH)))
        require(config.windowSeconds == 8 && config.overlapSeconds == 1 && config.threads in 1..4)
    }
    private fun validateOwner(owner: String?) {
        require(owner == null || owner.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"))) { "Subtitle request owner is invalid." }
    }
    private fun validateWindow(window: SubtitleWindow, config: SubtitleGenerationConfig, task: SubtitleGenerationTask? = null) {
        require(window.index >= 0 && window.startMs >= 0 && window.endMs > window.startMs && window.endMs <= MAX_DURATION &&
            window.endMs - window.startMs <= config.windowSeconds * 1000L + 1 && window.pcmSha256.matches(HASH) &&
            window.cues.size <= 64 && window.sourceCues.size <= 64 && window.translations.size <= 64)
        fun valid(cues: List<SpeechCue>) = cues.all { it.startMs >= window.startMs && it.endMs > it.startMs && it.endMs <= window.endMs &&
            it.text.isNotBlank() && it.text.length <= 4000 }
        require(valid(window.cues) && valid(window.sourceCues))
        if (config.pipeline == SubtitlePipeline.WHISPER_ENGLISH) {
            require(window.sourceCues.isEmpty() && window.translations.isEmpty() && window.detectedLanguage == null)
            require(!window.silent || window.cues.isEmpty())
        } else {
            require(window.cues.isEmpty()) { "English output cannot masquerade as original-language speech." }
            require(window.detectedLanguage == null || window.detectedLanguage.matches(Regex("[a-z]{2,3}")))
            require(if (window.silent) window.sourceCues.isEmpty() && window.translations.isEmpty() else window.sourceCues.isNotEmpty())
            require(window.translations.map { it.sourceIndex }.distinct().size == window.translations.size)
            require(window.translations.all { target -> task != null && acceptsSubtitleTarget(task, window, target) })
        }
    }
    private fun save(task: SubtitleGenerationTask) {
        val bytes = encode(task).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Subtitle journal limit reached. Previous windows have been kept." }
        require(journalFiles().filter { it.name != task.id + ".json" }.sumOf { it.length() } + bytes.size <= 64_000_000) { "Subtitle history storage is full." }
        io.write(File(directory, task.id + ".json"), bytes)
        tasks[task.id] = task; publish()
    }
    private fun journalFiles() = directory.listFiles().orEmpty().mapNotNull {
        val name = it.name.removeSuffix(".bak")
        if (name.matches(Regex("[a-f0-9]{32}\\.json"))) File(directory, name) else null
    }.distinctBy { it.name }
    private fun encode(task: SubtitleGenerationTask): JSONObject = JSONObject().put("version", 2).put("id", task.id).put("generation", task.generation)
        .put("uri", task.source.source.uri).put("headers", JSONObject(task.source.source.headers)).put("cacheKey", task.source.source.cacheKey)
        .put("label", task.source.source.label).put("fingerprint", task.source.fingerprint).put("verifiable", task.source.verifiable)
        .put("strongEtag", task.source.strongEtag).put("networkSize", task.source.networkSize)
        .put("networkUrl", task.source.networkUrl)
        .put("config", JSONObject().put("sourceLanguage", task.config.sourceLanguage).put("targetLanguage", task.config.targetLanguage)
            .put("style", task.config.style).put("modelSha256", task.config.modelSha256).put("windowSeconds", task.config.windowSeconds)
            .put("overlapSeconds", task.config.overlapSeconds).put("threads", task.config.threads)
            .put("outputMode", task.config.outputMode.name).put("pipeline", task.config.pipeline.name)
            .put("customStyle", task.config.customStyle).put("localRefinement", task.config.localRefinement)
            .put("translationPolicy", task.config.translationPolicy).put("capturedStyle", task.config.capturedStyle?.let { style ->
                JSONObject().put("id", style.id).put("name", style.name).put("instruction", style.instruction)
                    .put("preserveHonorifics", style.preserveHonorifics).put("preserveNames", style.preserveNames).put("naturalDialogue", style.naturalDialogue)
            }).put("refinementPin", task.config.refinementPin?.let { pin ->
                JSONObject().put("modelId", pin.modelId).put("sha256", pin.sha256).put("bytes", pin.bytes)
            }))
        .put("status", task.status.name).put("durationMs", task.durationMs).put("detectedLanguage", task.detectedLanguage)
        .put("srtFile", task.srtPath?.let { File(it).name }).put("srtSha256", task.srtSha256)
        .put("vttFile", task.vttPath?.let { File(it).name }).put("vttSha256", task.vttSha256).put("updatedAt", task.updatedAt).put("error", task.error)
        .put("pcmValidationRequired", task.pcmValidationRequired)
        .put("ownerRequestId", task.ownerRequestId).put("audioComplete", task.audioComplete)
        .put("windows", JSONArray().apply { task.windows.forEach { window -> put(JSONObject().put("index", window.index)
            .put("startMs", window.startMs).put("endMs", window.endMs).put("pcmSha256", window.pcmSha256).put("silent", window.silent)
            .put("cues", encodeCues(window.cues)).put("sourceCues", encodeCues(window.sourceCues)).put("detectedLanguage", window.detectedLanguage)
            .put("translations", JSONArray().apply { window.translations.forEach { target -> put(JSONObject().put("sourceIndex", target.sourceIndex)
                .put("text", target.text).put("hindiDraft", target.hindiDraft).put("refinementDraft", target.refinementDraft)
                .put("refinementCandidate", target.refinementCandidate).put("refinementHindiDraft", target.refinementHindiDraft)
                .put("refinement", target.refinement?.let { evidence -> JSONObject().put("modelId", evidence.model.modelId)
                    .put("sha256", evidence.model.sha256).put("bytes", evidence.model.bytes).put("promptSha256", evidence.promptSha256)
                    .put("outputSha256", evidence.outputSha256) })) } })) } })
    private fun encodeCues(cues: List<SpeechCue>): JSONArray = JSONArray().apply { cues.forEach { cue ->
        put(JSONObject().put("startMs", cue.startMs).put("endMs", cue.endMs).put("text", cue.text)) } }
    private fun decodeCues(cues: JSONArray): List<SpeechCue> {
        require(cues.length() <= 64)
        return (0 until cues.length()).map { n -> val cue = cues.getJSONObject(n)
            SpeechCue(cue.getLong("startMs"), cue.getLong("endMs"), cue.getString("text")) }
    }
    private fun decode(json: JSONObject): SubtitleGenerationTask {
        require(json.getInt("version") in 1..2)
        val id = json.getString("id").also { require(it.matches(ID)) }; val generation = json.getString("generation").also { require(it.matches(ID)) }
        validateOwner(json.optional("ownerRequestId"))
        val headers = json.getJSONObject("headers"); require(headers.length() <= 32)
        val source = SubtitleSourceIdentity(SubtitleMediaSource(json.getString("uri"), headers.keys().asSequence().associateWith { headers.getString(it) },
            json.getString("cacheKey"), json.getString("label")), json.getString("fingerprint"), json.getBoolean("verifiable"),
            json.optional("strongEtag"), if (!json.has("networkSize") || json.isNull("networkSize")) null else json.getLong("networkSize"), json.optional("networkUrl"))
        validateSource(source)
        val c = json.getJSONObject("config")
        val config = SubtitleGenerationConfig(c.getString("sourceLanguage"), c.getString("targetLanguage"), c.getString("style"), c.optional("modelSha256"),
            c.getInt("windowSeconds"), c.getInt("overlapSeconds"), c.getInt("threads"),
            outputMode = SubtitleOutputMode.valueOf(c.optString("outputMode", "TRANSLATED")),
            pipeline = SubtitlePipeline.valueOf(c.optString("pipeline", "WHISPER_ENGLISH")),
            customStyle = c.optString("customStyle", ""), localRefinement = c.optBoolean("localRefinement", false),
            translationPolicy = c.optString("translationPolicy", "whisper-english-v1"),
            capturedStyle = c.optJSONObject("capturedStyle")?.let { style -> TranslationStyleProfile(style.getString("id"), style.getString("name"),
                style.getString("instruction"), style.getBoolean("preserveHonorifics"), style.getBoolean("preserveNames"), style.getBoolean("naturalDialogue")) },
            refinementPin = c.optJSONObject("refinementPin")?.let { pin -> SubtitleRefinementPin(pin.getString("modelId"), pin.getString("sha256"), pin.getLong("bytes")) }); validateConfig(config)
        require(id == identity(source, config))
        val rows = json.getJSONArray("windows"); require(rows.length() <= 4000)
        val windows = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index); val targets = row.optJSONArray("translations") ?: JSONArray(); require(targets.length() <= 64)
            SubtitleWindow(row.getInt("index"), row.getLong("startMs"), row.getLong("endMs"), row.getString("pcmSha256"),
                decodeCues(row.getJSONArray("cues")), row.getBoolean("silent"), decodeCues(row.optJSONArray("sourceCues") ?: JSONArray()),
                row.optional("detectedLanguage"), (0 until targets.length()).map { n -> val target = targets.getJSONObject(n)
                    SubtitleTranslatedCue(target.getInt("sourceIndex"), target.getString("text"), target.optional("hindiDraft"),
                        target.optJSONObject("refinement")?.let { evidence -> SubtitleSavedRefinement(SubtitleRefinementPin(evidence.getString("modelId"),
                            evidence.getString("sha256"), evidence.getLong("bytes")), evidence.getString("promptSha256"), evidence.getString("outputSha256")) },
                        target.optional("refinementDraft"), target.optional("refinementCandidate"), target.optional("refinementHindiDraft"))
                }).also { require(it.index == index) }
        }
        val evidenceTask = SubtitleGenerationTask(id, generation, source, config, SubtitleGenerationStatus.PAUSED, windows)
        windows.forEach { validateWindow(it, config, evidenceTask) }
        require(windows.sumOf { if (config.pipeline == SubtitlePipeline.WHISPER_ENGLISH) it.cues.size else it.sourceCues.size } <= SubtitleFormats.MAX_CUES)
        fun export(key: String): String? = json.optional(key)?.let { name -> require(File(name).name == name); managedExport(File(File(directory, id), name).path, id).path }
        return SubtitleGenerationTask(id, generation, source, config, SubtitleGenerationStatus.valueOf(json.getString("status")), windows,
            json.getLong("durationMs").also { require(it in 0..MAX_DURATION) }, json.optional("detectedLanguage"), export("srtFile"), json.optional("srtSha256"),
            export("vttFile"), json.optional("vttSha256"), json.getLong("updatedAt"), json.optional("error")?.take(300),
            pcmValidationRequired = json.optBoolean("pcmValidationRequired", false), ownerRequestId = json.optional("ownerRequestId"),
            audioComplete = json.optBoolean("audioComplete", false))
    }
    private fun JSONObject.optional(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
    private fun publish() { mutable.value = tasks.values.toList() }
    companion object {
        internal const val MAX_BYTES = 8_000_000L
        internal const val MAX_DURATION = 6L * 60 * 60 * 1000
        private val ID = Regex("[a-f0-9]{32}")
        private val HASH = Regex("[a-f0-9]{64}")
        @Volatile private var instance: SubtitleGenerationStore? = null
        fun shared(context: Context): SubtitleGenerationStore = instance ?: synchronized(this) {
            instance ?: SubtitleGenerationStore(File(context.applicationContext.filesDir, "subtitle_jobs")).also { instance = it }
        }
        private fun identity(source: SubtitleSourceIdentity, config: SubtitleGenerationConfig) =
            digest(listOf(source.source.cacheKey, source.fingerprint, config.identityText()).joinToString("|") { "${it.length}:$it" }).take(32)
        internal fun fileHash(file: File): String = file.inputStream().use { stream ->
            val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var total = 0L
            while (true) { val n = stream.read(buffer); if (n < 0) break; total += n; require(total <= 4L * 1024 * 1024 * 1024); hash.update(buffer, 0, n) }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        private fun token() = UUID.randomUUID().toString().replace("-", "")
        internal fun digest(value: String) = digest(value.toByteArray())
        internal fun digest(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    }
}
