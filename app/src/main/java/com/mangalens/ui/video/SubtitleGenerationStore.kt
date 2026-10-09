package com.mangalens.ui.video

import android.content.Context
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
        val task = SubtitleGenerationTask(id, token(), source.copy(source = source.source.copy(headers = source.source.headers.toMap())), config,
            if (config.modelSha256 == null) SubtitleGenerationStatus.FAILED else SubtitleGenerationStatus.QUEUED,
            windows = if (!force && old != null) old.windows else emptyList(),
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
        validateWindow(window, task.config)
        require(window.index == task.windows.size && window.index < 4000) { "Speech windows must be checkpointed once in order." }
        require(window.startMs >= (task.windows.lastOrNull()?.startMs ?: 0) && durationMs in 0..MAX_DURATION)
        val result = task.copy(windows = task.windows + window, durationMs = durationMs,
            detectedLanguage = detectedLanguage ?: task.detectedLanguage, updatedAt = System.currentTimeMillis())
        require(result.windows.sumOf { it.cues.size } <= SubtitleFormats.MAX_CUES)
        save(result); return true
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
        it.copy(status = if (!invalidate && it.windows.any { window -> window.cues.isNotEmpty() }) SubtitleGenerationStatus.PARTIAL else SubtitleGenerationStatus.FAILED,
            windows = if (invalidate) emptyList() else it.windows,
            validationPending = if (invalidate) false else it.validationPending || requireValidation,
            pcmValidationRequired = !invalidate && (it.pcmValidationRequired || requireValidation), error = message.take(300),
            srtPath = null, srtSha256 = null, vttPath = null, vttSha256 = null, updatedAt = System.currentTimeMillis()).also(::save)
    }
    @Synchronized internal fun interrupted(id: String, generation: String) { active(id, generation)?.copy(status = SubtitleGenerationStatus.QUEUED)?.let(::save) }

    /** Both exports are durable before their checksums enter the atomic task manifest. */
    @Synchronized internal fun finish(id: String, generation: String): SubtitleGenerationTask? {
        val task = active(id, generation) ?: return null
        check(task.durationMs == 0L || task.processedMs + 1500 >= task.durationMs) { "Audio has not reached the end. Completed windows have been kept." }
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
        require(config.targetLanguage == "en" && config.style == "whisper-english") { "This generator produces English subtitles using multilingual Whisper." }
        require(config.sourceLanguage.matches(Regex("auto|[a-z]{2,3}")) && (config.modelSha256 == null || config.modelSha256.matches(HASH)))
        require(config.windowSeconds == 8 && config.overlapSeconds == 1 && config.threads in 1..4)
    }
    private fun validateOwner(owner: String?) {
        require(owner == null || owner.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"))) { "Subtitle request owner is invalid." }
    }
    private fun validateWindow(window: SubtitleWindow, config: SubtitleGenerationConfig) {
        require(window.index >= 0 && window.startMs >= 0 && window.endMs > window.startMs && window.endMs <= MAX_DURATION &&
            window.endMs - window.startMs <= config.windowSeconds * 1000L + 1 && window.pcmSha256.matches(HASH) && window.cues.size <= 64)
        require(!window.silent || window.cues.isEmpty())
        require(window.cues.all { it.startMs >= window.startMs && it.endMs > it.startMs && it.endMs <= window.endMs &&
            it.text.isNotBlank() && it.text.length <= 4000 })
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
    private fun encode(task: SubtitleGenerationTask): JSONObject = JSONObject().put("version", 1).put("id", task.id).put("generation", task.generation)
        .put("uri", task.source.source.uri).put("headers", JSONObject(task.source.source.headers)).put("cacheKey", task.source.source.cacheKey)
        .put("label", task.source.source.label).put("fingerprint", task.source.fingerprint).put("verifiable", task.source.verifiable)
        .put("strongEtag", task.source.strongEtag).put("networkSize", task.source.networkSize)
        .put("networkUrl", task.source.networkUrl)
        .put("config", JSONObject().put("sourceLanguage", task.config.sourceLanguage).put("targetLanguage", task.config.targetLanguage)
            .put("style", task.config.style).put("modelSha256", task.config.modelSha256).put("windowSeconds", task.config.windowSeconds)
            .put("overlapSeconds", task.config.overlapSeconds).put("threads", task.config.threads))
        .put("status", task.status.name).put("durationMs", task.durationMs).put("detectedLanguage", task.detectedLanguage)
        .put("srtFile", task.srtPath?.let { File(it).name }).put("srtSha256", task.srtSha256)
        .put("vttFile", task.vttPath?.let { File(it).name }).put("vttSha256", task.vttSha256).put("updatedAt", task.updatedAt).put("error", task.error)
        .put("pcmValidationRequired", task.pcmValidationRequired)
        .put("ownerRequestId", task.ownerRequestId)
        .put("windows", JSONArray().apply { task.windows.forEach { window -> put(JSONObject().put("index", window.index)
            .put("startMs", window.startMs).put("endMs", window.endMs).put("pcmSha256", window.pcmSha256).put("silent", window.silent)
            .put("cues", JSONArray().apply { window.cues.forEach { cue -> put(JSONObject().put("startMs", cue.startMs).put("endMs", cue.endMs).put("text", cue.text)) } })) } })
    private fun decode(json: JSONObject): SubtitleGenerationTask {
        require(json.getInt("version") == 1)
        val id = json.getString("id").also { require(it.matches(ID)) }; val generation = json.getString("generation").also { require(it.matches(ID)) }
        validateOwner(json.optional("ownerRequestId"))
        val headers = json.getJSONObject("headers"); require(headers.length() <= 32)
        val source = SubtitleSourceIdentity(SubtitleMediaSource(json.getString("uri"), headers.keys().asSequence().associateWith { headers.getString(it) },
            json.getString("cacheKey"), json.getString("label")), json.getString("fingerprint"), json.getBoolean("verifiable"),
            json.optional("strongEtag"), if (!json.has("networkSize") || json.isNull("networkSize")) null else json.getLong("networkSize"), json.optional("networkUrl"))
        validateSource(source)
        val c = json.getJSONObject("config")
        val config = SubtitleGenerationConfig(c.getString("sourceLanguage"), c.getString("targetLanguage"), c.getString("style"), c.optional("modelSha256"),
            c.getInt("windowSeconds"), c.getInt("overlapSeconds"), c.getInt("threads")); validateConfig(config)
        require(id == identity(source, config))
        val rows = json.getJSONArray("windows"); require(rows.length() <= 4000)
        val windows = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index); val cues = row.getJSONArray("cues"); require(cues.length() <= 64)
            SubtitleWindow(row.getInt("index"), row.getLong("startMs"), row.getLong("endMs"), row.getString("pcmSha256"),
                (0 until cues.length()).map { n -> val cue = cues.getJSONObject(n); SpeechCue(cue.getLong("startMs"), cue.getLong("endMs"), cue.getString("text")) },
                row.getBoolean("silent")).also { require(it.index == index); validateWindow(it, config) }
        }
        fun export(key: String): String? = json.optional(key)?.let { name -> require(File(name).name == name); managedExport(File(File(directory, id), name).path, id).path }
        return SubtitleGenerationTask(id, generation, source, config, SubtitleGenerationStatus.valueOf(json.getString("status")), windows,
            json.getLong("durationMs").also { require(it in 0..MAX_DURATION) }, json.optional("detectedLanguage"), export("srtFile"), json.optional("srtSha256"),
            export("vttFile"), json.optional("vttSha256"), json.getLong("updatedAt"), json.optional("error")?.take(300),
            pcmValidationRequired = json.optBoolean("pcmValidationRequired", false), ownerRequestId = json.optional("ownerRequestId"))
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
            digest(listOf(source.source.cacheKey, source.fingerprint, config.toString()).joinToString("|") { "${it.length}:$it" }).take(32)
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
