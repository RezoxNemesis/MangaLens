package com.mangalens.core.translation

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import com.mangalens.core.reader.SavedChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID

/** All inputs that can change a translation are captured before WorkManager dispatch. */
data class ChapterTranslationConfig(
    val targetLanguage: String,
    val styleId: String = "natural",
    val customStyle: String = "",
    val ocrScript: String = "AUTO",
    val highAccuracy: Boolean = true,
    val preserveStyle: Boolean = true,
    val localRefinement: Boolean = false,
    val refinementRequest: TranslationRefinementRequest? = null
) {
    fun style(): TranslationStyleProfile = refinementRequest?.style ?: if (styleId == "custom") TranslationStyleProfile.custom(customStyle)
        else TranslationStyleProfile.fromId(styleId)

    internal fun normalized(): ChapterTranslationConfig {
        val language = targetLanguage.trim().lowercase(Locale.ROOT)
        require(language.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})?"))) { "Choose a supported target language." }
        val script = ocrScript.trim().uppercase(Locale.ROOT)
        require(script in setOf("AUTO", "LATIN", "DEVANAGARI", "CHINESE", "JAPANESE", "KOREAN")) { "Unsupported OCR script." }
        require(customStyle.length <= TranslationStyleProfile.MAX_CUSTOM_INSTRUCTION_CHARS) { "Custom translation style is too long." }
        val style = styleId.trim().lowercase(Locale.ROOT)
        refinementRequest?.let {
            TranslationRefinementRequestCodec.validate(it)
            require(it.enabled == localRefinement && it.style.id == if (style == "custom") "custom" else TranslationStyleProfile.fromId(style).id) {
                "Captured refinement settings differ from the requested style."
            }
            require(style != "custom" || it.style.instruction == customStyle.trim()) { "Captured custom style differs from the request." }
        }
        return copy(targetLanguage = language, styleId = if (style == "custom") style else TranslationStyleProfile.fromId(style).id,
            customStyle = if (style == "custom") customStyle.trim() else "", ocrScript = script)
    }
}

enum class ChapterTranslationStatus { QUEUED, RUNNING, PAUSED, COMPLETED, PARTIAL, FAILED, CANCELLED }
enum class ChapterTranslationPageStatus { PENDING, RUNNING, COMPLETED, PARTIAL, FAILED, NO_TEXT, PROMO }

/** Page-space text, geometry and optional Hindi evidence. A journal never contains a Bitmap or text layer. */
data class SavedMangaLettering(
    val source: String,
    val translated: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val family: String,
    val face: Int,
    val color: Int,
    val size: Float,
    val alignment: String,
    val sourceLeft: Int,
    val sourceTop: Int,
    val sourceRight: Int,
    val sourceBottom: Int,
    val savedHindiDraft: String? = null,
    val originalSourceBounds: SavedOriginalSourceBounds? = null
)

data class ChapterTranslationPage(
    val index: Int,
    val sourcePath: String?,
    val sourceSha256: String?,
    val status: ChapterTranslationPageStatus = ChapterTranslationPageStatus.PENDING,
    val cleanedPath: String? = null,
    val cleanedSha256: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val lettering: List<SavedMangaLettering> = emptyList(),
    val rejectedRegions: Int = 0,
    val error: String? = null,
    val originalWidth: Int? = null,
    val originalHeight: Int? = null
) {
    val isProcessed: Boolean get() = status !in setOf(ChapterTranslationPageStatus.PENDING, ChapterTranslationPageStatus.RUNNING)
    val isComplete: Boolean get() = status in setOf(ChapterTranslationPageStatus.COMPLETED, ChapterTranslationPageStatus.NO_TEXT, ChapterTranslationPageStatus.PROMO)
}

data class ChapterTranslationTask(
    val id: String,
    val generation: String,
    val chapterId: String,
    val title: String,
    val config: ChapterTranslationConfig,
    val pages: List<ChapterTranslationPage>,
    val status: ChapterTranslationStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val error: String? = null,
    val validationPending: Boolean = false,
    val requestedPages: List<Int>? = null,
    val ownerRequestId: String? = null
) {
    val totalPages: Int get() = pages.size
    val processedPages: Int get() = pages.count { it.isProcessed }
    val completedPages: Int get() = pages.count { it.isComplete }
    val hasTranslations: Boolean get() = pages.any { it.lettering.isNotEmpty() && it.cleanedPath != null }
}

internal data class ChapterTranslationRemoval(
    val chapterId: String,
    val token: String,
    val tasks: List<ChapterTranslationTask>
)

internal data class NativeMemoryFileStamp(val path: String, val key: Any?, val size: Long, val modified: java.nio.file.attribute.FileTime)
internal data class NativeMemoryPageProof(val task: ChapterTranslationTask, val page: ChapterTranslationPage,
    val source: NativeMemoryFileStamp, val output: NativeMemoryFileStamp)

/** The injected IO boundary lets fault tests interrupt a commit without mocking the journal state. */
internal interface ChapterJournalIo {
    fun read(file: File): ByteArray
    fun write(file: File, bytes: ByteArray)
    fun delete(file: File) {
        for (candidate in listOf(file, File(file.path + ".bak"), File(file.path + ".new"))) {
            check(!candidate.exists() || candidate.delete()) { "Unable to remove translation journal." }
        }
    }
}

private class AtomicChapterJournalIo : ChapterJournalIo {
    override fun read(file: File): ByteArray = AtomicFile(file).openRead().use { input ->
        require(input.channel.size() <= ChapterTranslationStore.MAX_JOURNAL_BYTES) { "Translation journal is too large." }
        input.readBytes()
    }

    override fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }

    override fun delete(file: File) {
        AtomicFile(file).delete()
        // API 28 AtomicFile deletes the base and backup, but leaves newer .new sidecars.
        super<ChapterJournalIo>.delete(file)
        check(listOf(file, File(file.path + ".bak"), File(file.path + ".new")).none { it.exists() }) {
            "Unable to remove translation journal."
        }
    }
}

/**
 * One atomic task manifest checkpoints each completed page. Images stay in private managed files.
 * Every mutation checks a generation; an interrupted/old worker cannot resurrect cancelled work.
 * Use the suspend jobs facade for commands and [refresh] before restoring results to the reader.
 */
class ChapterTranslationStore internal constructor(
    private val directory: File,
    private val sourceDirectory: File,
    private val journalIo: ChapterJournalIo = AtomicChapterJournalIo(),
    private val originalDimensions: (File) -> Pair<Int, Int>? = { file ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L)
            bounds.outWidth to bounds.outHeight else null
    },
    private val surfaceReadable: (File) -> Boolean = { file ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= MAX_SURFACE_PIXELS
    }
) {
    private val tasks = LinkedHashMap<String, ChapterTranslationTask>()
    private val awaitingValidation = HashSet<String>()
    private class StartGuard(var removed: Boolean = false)
    private val startsByChapter = HashMap<String, MutableSet<StartGuard>>()
    private val chapterRemovals = HashMap<String, ChapterTranslationRemoval>()
    private val _states = MutableStateFlow<List<ChapterTranslationTask>>(emptyList())
    val states: StateFlow<List<ChapterTranslationTask>> = _states.asStateFlow()

    init {
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create private translation storage." }
        check(sourceDirectory.isDirectory || sourceDirectory.mkdirs()) { "Unable to find private chapter storage." }
        var remaining = MAX_TOTAL_JOURNAL_BYTES
        journalFiles().sortedByDescending { it.lastModified() }.take(MAX_TASKS).forEach { file ->
            val loaded = runCatching {
                val bytes = journalIo.read(file)
                require(bytes.size <= MAX_JOURNAL_BYTES && bytes.size <= remaining)
                val task = decode(JSONObject(String(bytes, Charsets.UTF_8)))
                require(task.id + ".json" == file.name)
                remaining -= bytes.size
                task.copy(status = if (task.status == ChapterTranslationStatus.RUNNING) ChapterTranslationStatus.QUEUED else task.status,
                    pages = task.pages.map(::interruptedPage))
            }.getOrNull()
            if (loaded != null) { tasks[loaded.id] = loaded; awaitingValidation += loaded.id }
        }
        publish()
    }

    @Synchronized fun get(taskId: String): ChapterTranslationTask? = tasks[taskId]?.let(::visible)

    /** Return the receipt of the command that was dispatched, even if native awaits overlap a replacement. */
    @Synchronized internal fun commandSnapshot(captured: ChapterTranslationTask): ChapterTranslationTask =
        tasks[captured.id]?.takeIf { it.generation == captured.generation && it.ownerRequestId == captured.ownerRequestId }
            ?.let(::visible) ?: captured

    @Synchronized private fun rawGet(taskId: String): ChapterTranslationTask? = tasks[taskId]

    /** IO-only: check actual bytes and surface before the short Reader/task publication guard. */
    internal fun prepareMemoryPublication(receipt: ReaderTranslationReceipt, pageIndex: Int): NativeMemoryPageProof? {
        val task = synchronized(this) {
            tasks[receipt.taskId]?.takeIf { memoryTaskMatches(receipt, it) }
        } ?: return null
        val page = task.pages.singleOrNull { it.index == pageIndex && it.status in setOf(
            ChapterTranslationPageStatus.COMPLETED, ChapterTranslationPageStatus.PARTIAL) } ?: return null
        if (page.lettering.isEmpty() || page.originalWidth == null || page.originalHeight == null) return null
        val source = page.sourcePath?.let { runCatching { requireManagedSource(it) }.getOrNull() } ?: return null
        val output = page.cleanedPath?.let { runCatching { requireManagedOutput(it, task.id) }.getOrNull() } ?: return null
        return runCatching {
            val sourceBefore = memoryStamp(source); val outputBefore = memoryStamp(output)
            require(originalDimensions(source) == (page.originalWidth to page.originalHeight))
            require(validatedPage(page, task.config) == page)
            require(sourceBefore == memoryStamp(source) && outputBefore == memoryStamp(output))
            NativeMemoryPageProof(task, page, sourceBefore, outputBefore)
        }.getOrNull()
    }

    /** The caller holds Reader authority; this monitor protects task removal/replacement through rename. */
    @Synchronized internal fun commitMemoryPublication(receipt: ReaderTranslationReceipt, proof: NativeMemoryPageProof, commit: () -> Unit) {
        val current = tasks[proof.task.id]
        check(current != null && memoryTaskMatches(receipt, current) &&
            current.pages.singleOrNull { it.index == proof.page.index } == proof.page &&
            memoryStamp(File(proof.source.path)) == proof.source && memoryStamp(File(proof.output.path)) == proof.output) {
            "The saved source or translation changed. Reopen its correction editor."
        }
        commit()
    }

    private fun memoryTaskMatches(receipt: ReaderTranslationReceipt, task: ChapterTranslationTask): Boolean =
        task.id !in awaitingValidation && task.chapterId !in chapterRemovals &&
        task.status in setOf(ChapterTranslationStatus.QUEUED, ChapterTranslationStatus.RUNNING,
            ChapterTranslationStatus.COMPLETED, ChapterTranslationStatus.PARTIAL) &&
        ReaderTranslationPresentation.matchesTask(receipt, task) &&
        receipt.sources == task.pages.map { ReaderTranslationSource(it.index, it.sourcePath, it.sourceSha256) }

    private fun memoryStamp(file: File): NativeMemoryFileStamp {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        check(attributes.isRegularFile)
        return NativeMemoryFileStamp(file.canonicalPath, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime())
    }

    @Synchronized fun latest(chapterId: String, targetLanguage: String? = null): ChapterTranslationTask? =
        tasks.values.filter { it.chapterId == chapterId && (targetLanguage == null || it.config.targetLanguage == targetLanguage) }
            .maxByOrNull { it.updatedAt }?.let(::visible)

    /** Re-hash bytes, including same-length replacements, and drop missing/corrupt cleaned surfaces. */
    suspend fun refresh(taskId: String, generation: String? = null): ChapterTranslationTask? = withContext(Dispatchers.IO) { validate(taskId, generation) }

    internal fun start(chapter: SavedChapter, configuration: ChapterTranslationConfig,
        requestedPages: List<Int>? = null, ownerRequestId: String? = null,
        allowOwnerReplacement: Boolean = true, forceReprocess: Boolean = false): ChapterTranslationTask {
        require(chapter.id.matches(ID)) { "This chapter has no stable library identity." }
        val guard = synchronized(this) {
            if (chapter.id in chapterRemovals) throw CancellationException("This chapter is being removed.")
            StartGuard().also { startsByChapter.getOrPut(chapter.id) { HashSet() }.add(it) }
        }
        try { return startChecked(chapter, configuration, guard, requestedPages, ownerRequestId, allowOwnerReplacement, forceReprocess) }
        finally { synchronized(this) {
            startsByChapter[chapter.id]?.let { starts ->
                starts.remove(guard)
                if (starts.isEmpty()) startsByChapter.remove(chapter.id)
            }
        } }
    }

    private fun startChecked(chapter: SavedChapter, configuration: ChapterTranslationConfig, guard: StartGuard,
        requestedPages: List<Int>?, ownerRequestId: String?, allowOwnerReplacement: Boolean,
        forceReprocess: Boolean): ChapterTranslationTask {
        require(chapter.id.matches(ID)) { "This chapter has no stable library identity." }
        require(chapter.pages.isNotEmpty() && chapter.pages.size <= MAX_PAGES) { "Chapter must contain 1–$MAX_PAGES pages." }
        require(chapter.pages.map { it.index }.distinct().size == chapter.pages.size && chapter.pages.all { it.index >= 0 }) { "Chapter page indexes must be unique." }
        val scope = normalizedScope(requestedPages, chapter.pages.map { it.index })
        validateOwner(ownerRequestId)
        val config = configuration.normalized()
        val id = digest(chapter.id + "|" + configIdentity(config)).take(32)
        val previous = rawGet(id)
        val pages = chapter.pages.map { page ->
            val source = page.localPath?.let { path ->
                requireManagedSource(path)
            }
            val hash = source?.takeIf(::validSource)?.let(::sha256)
            val old = previous?.pages?.firstOrNull { it.index == page.index }
            if (hash != null && old?.sourcePath == source.absolutePath && old.sourceSha256 == hash) {
                validatedPage(old, config)
            } else ChapterTranslationPage(page.index, source?.absolutePath, hash,
                status = if (hash == null) ChapterTranslationPageStatus.FAILED else ChapterTranslationPageStatus.PENDING,
                error = if (hash == null) "Page ${page.index} is not available in private chapter storage. Retry its source." else null)
        }
        val now = System.currentTimeMillis()
        val task = ChapterTranslationTask(id, token(), chapter.id, chapter.title.take(MAX_TITLE_CHARS), config, pages,
            ChapterTranslationStatus.QUEUED, previous?.createdAt ?: now, now,
            requestedPages = scope, ownerRequestId = ownerRequestId)
        return synchronized(this) {
            if (guard.removed || chapter.id in chapterRemovals) throw CancellationException("Chapter was removed before translation could start.")
            if (id !in tasks) require(tasks.size < MAX_TASKS && journalFiles().size < MAX_TASKS) { "Translation history is full. Existing results have been kept." }
            val concurrent = tasks[id]
            // Replay authority is checked at the mutation boundary, after disk checks;
            // an earlier host lookup cannot reserve a slot against a newer reader job.
            check(allowOwnerReplacement || concurrent == null || concurrent.ownerRequestId == ownerRequestId) {
                "Translation request was replaced by another owner."
            }
            // Source/surface checks deliberately run outside the control lock. Retain pages
            // the current worker committed while those checks were running, rather than
            // replacing that newly completed evidence with our earlier RUNNING snapshot.
            val verifiedPages = task.pages.map { planned ->
                val latest = concurrent?.pages?.firstOrNull { it.index == planned.index }
                val earlier = previous?.pages?.firstOrNull { it.index == planned.index }
                if (latest != null && latest != earlier && planned.sourceSha256 != null &&
                    latest.sourcePath == planned.sourcePath && latest.sourceSha256 == planned.sourceSha256) {
                    validatedPage(latest, config)
                } else planned
            }
            // A stable owned request closes the process-death gap between the native
            // journal commit and the caller's receipt. It never restarts a cancelled,
            // paused or failed generation, and ownership alone is insufficient.
            if (!forceReprocess && ownerRequestId != null && concurrent != null && concurrent.ownerRequestId == ownerRequestId &&
                concurrent.config == config && concurrent.requestedPages == scope && sameSources(concurrent.pages, pages)) {
                val replay = withValidatedPages(concurrent, verifiedPages)
                if (replay != concurrent) saveVerified(replay)
                else { awaitingValidation.remove(id); publish() }
                return@synchronized replay
            }
            val merged = task.copy(pages = verifiedPages.map { page ->
                if (forceReprocess && page.sourceSha256 != null && (scope == null || page.index in scope)) {
                    // Explicit retranslation fences the old worker and starts fresh for
                    // this scope. Original files and other completed pages remain intact.
                    ChapterTranslationPage(page.index, page.sourcePath, page.sourceSha256)
                } else interruptedPage(page)
            })
            saveVerified(merged)
            merged
        }
    }

    @Synchronized internal fun markRunning(taskId: String, generation: String): ChapterTranslationTask? {
        val task = current(taskId, generation) ?: return null
        return task.copy(status = ChapterTranslationStatus.RUNNING, updatedAt = System.currentTimeMillis(), error = null).also(::save)
    }

    @Synchronized internal fun beginPage(taskId: String, generation: String, index: Int): ChapterTranslationPage? {
        val task = current(taskId, generation) ?: return null
        if (task.requestedPages != null && index !in task.requestedPages) return null
        val old = task.pages.firstOrNull { it.index == index } ?: return null
        if (old.isComplete) return null
        val page = old.copy(status = ChapterTranslationPageStatus.RUNNING, error = null)
        save(task.copy(pages = task.pages.map { if (it.index == index) page else it }, updatedAt = System.currentTimeMillis()))
        return page
    }

    /** The PNG is promoted first; only a verified file and unchanged source can enter the journal. */
    @Synchronized internal fun commitPage(taskId: String, generation: String, page: ChapterTranslationPage): Boolean {
        val task = current(taskId, generation) ?: return false
        if (task.requestedPages != null && page.index !in task.requestedPages) return false
        val old = task.pages.firstOrNull { it.index == page.index } ?: return false
        require(page.status != ChapterTranslationPageStatus.RUNNING && page.status != ChapterTranslationPageStatus.PENDING)
        if (page.sourcePath != old.sourcePath || page.sourceSha256 != old.sourceSha256) return false
        validateMetadata(page, task.config)
        val source = old.sourcePath?.let(::requireManagedSource)
        if (old.sourceSha256 != null && (source == null || !validSource(source) || sha256(source) != old.sourceSha256)) {
            val hash = source?.takeIf(::validSource)?.let(::sha256)
            val invalid = ChapterTranslationPage(old.index, old.sourcePath, hash,
                status = if (hash == null) ChapterTranslationPageStatus.FAILED else ChapterTranslationPageStatus.PENDING,
                error = "Source page changed during translation. Resume to translate the current page.")
            save(task.copy(pages = task.pages.map { if (it.index == old.index) invalid else it }, updatedAt = System.currentTimeMillis()))
            return false
        }
        if (page.lettering.isNotEmpty()) {
            val output = requireManagedOutput(page.cleanedPath ?: error("Missing cleaned page surface."), taskId)
            require(output.isFile && output.length() in 1..MAX_SURFACE_BYTES && surfaceReadable(output)) { "Cleaned page is missing or unreadable." }
            require(page.cleanedSha256 != null && sha256(output) == page.cleanedSha256) { "Cleaned page checksum changed." }
        } else require(page.cleanedPath == null && page.cleanedSha256 == null) { "An empty page must not retain an erased surface." }
        save(task.copy(pages = task.pages.map { if (it.index == page.index) page else it }, updatedAt = System.currentTimeMillis()))
        if (old.cleanedPath != null && old.cleanedPath != page.cleanedPath) discardUnreferenced(old.cleanedPath)
        return true
    }

    @Synchronized internal fun finish(taskId: String, generation: String): ChapterTranslationTask? {
        val task = current(taskId, generation) ?: return null
        val incomplete = task.pages.any { !it.isComplete }
        val status = when {
            !task.hasTranslations -> ChapterTranslationStatus.FAILED
            incomplete -> ChapterTranslationStatus.PARTIAL
            else -> ChapterTranslationStatus.COMPLETED
        }
        val failures = task.pages.filter { it.status in setOf(ChapterTranslationPageStatus.FAILED, ChapterTranslationPageStatus.PARTIAL, ChapterTranslationPageStatus.PENDING) }
        val message = when {
            !task.hasTranslations -> failures.firstOrNull()?.error ?: "No readable story text was translated. Try a clearer source or another OCR script."
            incomplete -> "${failures.size} of ${task.totalPages} pages need another attempt. Successful translations are available."
            else -> null
        }
        return task.copy(status = status, error = message?.take(MAX_ERROR_CHARS), updatedAt = System.currentTimeMillis()).also(::save)
    }

    @Synchronized internal fun pause(taskId: String, generation: String): ChapterTranslationTask? {
        val task = current(taskId, generation) ?: return null
        return task.copy(status = ChapterTranslationStatus.PAUSED, pages = task.pages.map(::interruptedPage),
            error = null, updatedAt = System.currentTimeMillis()).also(::save)
    }

    internal fun resume(taskId: String, generation: String): ChapterTranslationTask? {
        val task = rawGet(taskId)?.takeIf { it.generation == generation && it.status in setOf(
            ChapterTranslationStatus.PAUSED, ChapterTranslationStatus.PARTIAL, ChapterTranslationStatus.FAILED) } ?: return null
        val verified = validatedTask(task)
        return synchronized(this) {
            if (tasks[taskId] != task || task.chapterId in chapterRemovals) return@synchronized null
            verified.copy(generation = token(), status = ChapterTranslationStatus.QUEUED,
                pages = verified.pages.map(::interruptedPage), error = null, updatedAt = System.currentTimeMillis()).also(::saveVerified)
        }
    }

    @Synchronized internal fun cancel(taskId: String, generation: String): ChapterTranslationTask? {
        val task = tasks[taskId]?.takeIf { it.generation == generation && it.status != ChapterTranslationStatus.CANCELLED } ?: return null
        return task.copy(status = ChapterTranslationStatus.CANCELLED, pages = task.pages.map(::interruptedPage),
            error = null, updatedAt = System.currentTimeMillis()).also(::save)
    }

    @Synchronized internal fun fail(taskId: String, generation: String, message: String): ChapterTranslationTask? {
        val task = current(taskId, generation) ?: return null
        val verifiedComplete = task.hasTranslations && task.pages.all { it.isComplete }
        return task.copy(status = when {
            verifiedComplete -> ChapterTranslationStatus.COMPLETED
            task.hasTranslations -> ChapterTranslationStatus.PARTIAL
            else -> ChapterTranslationStatus.FAILED
        }, pages = task.pages.map(::interruptedPage), error = if (verifiedComplete) null else message.take(MAX_ERROR_CHARS),
            updatedAt = System.currentTimeMillis()).also(::save)
    }

    /** OS stops leave replay eligible; explicit pause/cancel/replacement have already fenced this. */
    @Synchronized internal fun interrupted(taskId: String, generation: String) {
        val task = current(taskId, generation) ?: return
        save(task.copy(status = ChapterTranslationStatus.QUEUED, pages = task.pages.map(::interruptedPage), updatedAt = System.currentTimeMillis()))
    }

    /** Resource waits retain ownership and generation, unlike an explicit user pause/resume. */
    @Synchronized internal fun deferForResources(taskId: String, generation: String, reason: String): Boolean {
        val task = current(taskId, generation) ?: return false
        save(task.copy(status = ChapterTranslationStatus.QUEUED, pages = task.pages.map(::interruptedPage),
            error = reason.take(MAX_ERROR_CHARS), updatedAt = System.currentTimeMillis()))
        return true
    }

    @Synchronized internal fun isCurrent(taskId: String, generation: String): Boolean = current(taskId, generation) != null

    /** Fence first; the facade stops these exact native jobs before completing deletion. */
    @Synchronized internal fun beginChapterRemoval(chapterId: String): ChapterTranslationRemoval {
        require(chapterId.matches(ID))
        val removal = chapterRemovals.getOrPut(chapterId) {
            ChapterTranslationRemoval(chapterId, token(), tasks.values.filter { it.chapterId == chapterId }.toList())
        }
        startsByChapter[chapterId]?.forEach { it.removed = true }
        // Even if a write fails, the in-memory chapter fence keeps native callbacks out;
        // the library remains intact and an explicit delete retry finishes the same removal.
        tasks.values.filter { it.chapterId == chapterId }.toList().forEach { task ->
            if (task.status != ChapterTranslationStatus.CANCELLED) save(task.copy(status = ChapterTranslationStatus.CANCELLED,
                pages = task.pages.map(::interruptedPage), error = null, updatedAt = System.currentTimeMillis()))
        }
        return removal
    }

    @Synchronized internal fun finishChapterRemoval(removal: ChapterTranslationRemoval) {
        if (chapterRemovals[removal.chapterId]?.token != removal.token) return
        removal.tasks.forEach { task ->
            val current = tasks[task.id]
            check(current == null || current.chapterId == removal.chapterId && current.generation == task.generation &&
                current.status == ChapterTranslationStatus.CANCELLED) { "Chapter translation changed during deletion." }
            journalIo.delete(File(directory, task.id + ".json"))
            deleteManagedSurfaces(task.id)
            tasks.remove(task.id)
            awaitingValidation.remove(task.id)
            publish()
        }
        chapterRemovals.remove(removal.chapterId)
    }

    private fun deleteManagedSurfaces(taskId: String) {
        val folder = File(directory, taskId)
        if (!folder.exists()) return
        require(folder.canonicalFile == File(directory.canonicalFile, taskId)) { "Generated surface folder escaped private task storage." }
        folder.listFiles().orEmpty().forEach { file ->
            // File.delete unlinks a symlink itself; never recursively follow it into a
            // shared source directory or another chapter's generated files.
            if (file.name.removeSuffix(".part").matches(OUTPUT_NAME)) {
                check(!file.exists() || file.delete()) { "Unable to remove a generated translation surface." }
            }
        }
        if (folder.listFiles()?.isEmpty() == true) check(folder.delete()) { "Unable to remove the empty translation folder." }
    }

    @Synchronized internal fun createOutputFile(taskId: String, generation: String, pageIndex: Int): File {
        require(taskId.matches(ID) && generation.matches(ID) && pageIndex >= 0)
        val task = current(taskId, generation) ?: throw CancellationException("Translation generation was removed or replaced.")
        require(task.pages.any { it.index == pageIndex } && (task.requestedPages == null || pageIndex in task.requestedPages)) {
            "Page is outside the captured translation scope."
        }
        val folder = File(directory, taskId)
        require(folder.canonicalFile.parentFile == directory.canonicalFile)
        check(folder.isDirectory || folder.mkdirs()) { "Unable to create cleaned page storage." }
        return File(folder, "${generation}_${pageIndex}_${token()}.png")
    }

    /** Only generated surfaces are removable; original chapter pages are outside this tree. */
    @Synchronized internal fun discardUnreferenced(path: String) {
        if (tasks.values.any { task -> task.pages.any { it.cleanedPath == path } }) return
        runCatching { requireManagedOutput(path).takeIf { it.name.matches(OUTPUT_NAME) }?.delete() }
    }

    /** A grace period and active-generation fence keep cleanup away from live encode/commit work. */
    @Synchronized internal fun cleanupOrphans(now: Long = System.currentTimeMillis()) {
        val retained = tasks.values.flatMap { it.pages }.mapNotNull { it.cleanedPath }.toSet()
        val cutoff = now - 24L * 60L * 60L * 1000L
        directory.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(ID) }.forEach { folder ->
            if (folder.canonicalFile.parentFile != directory.canonicalFile) return@forEach
            val current = tasks[folder.name]
            if (current == null && (File(directory, folder.name + ".json").exists() ||
                    File(directory, folder.name + ".json.bak").exists())) return@forEach
            folder.listFiles().orEmpty().forEach files@ { file ->
                val generatedName = file.name.removeSuffix(".part")
                if (!generatedName.matches(OUTPUT_NAME) || file.lastModified() > cutoff) return@files
                if (file.canonicalFile.parentFile != folder.canonicalFile || file.absolutePath in retained) return@files
                if (current?.generation == generatedName.substringBefore('_') && current.status in setOf(
                        ChapterTranslationStatus.QUEUED, ChapterTranslationStatus.RUNNING)) return@files
                file.delete()
            }
        }
    }

    internal fun sourceFile(page: ChapterTranslationPage): File? = page.sourcePath?.let(::requireManagedSource)

    private fun validate(taskId: String, generation: String? = null): ChapterTranslationTask? {
        val task = rawGet(taskId)?.takeIf { generation == null || it.generation == generation } ?: return null
        val verified = validatedTask(task)
        return synchronized(this) {
            // Hashing a large chapter never holds the control lock or overwrites newer checkpoints.
            if (tasks[taskId] != task) return@synchronized tasks[taskId]?.takeIf { generation == null || it.generation == generation }?.let(::visible)
            if (verified != task) saveVerified(verified)
            else { awaitingValidation.remove(taskId); publish() }
            verified
        }
    }

    private fun validatedTask(task: ChapterTranslationTask): ChapterTranslationTask {
        val pages = task.pages.map { validatedPage(it, task.config) }
        return withValidatedPages(task, pages)
    }

    private fun withValidatedPages(task: ChapterTranslationTask, pages: List<ChapterTranslationPage>): ChapterTranslationTask {
        if (pages == task.pages) return task
        val status = if (task.status in setOf(ChapterTranslationStatus.QUEUED, ChapterTranslationStatus.RUNNING,
                ChapterTranslationStatus.PAUSED, ChapterTranslationStatus.CANCELLED)) task.status
            else if (pages.any { it.lettering.isNotEmpty() }) ChapterTranslationStatus.PARTIAL else ChapterTranslationStatus.FAILED
        return task.copy(pages = pages, status = status, updatedAt = System.currentTimeMillis(),
            error = "Some saved translation pages changed or are unavailable. Resume to rebuild affected pages.")
    }

    private fun sameSources(existing: List<ChapterTranslationPage>, checked: List<ChapterTranslationPage>): Boolean =
        existing.size == checked.size && existing.zip(checked).all { (old, fresh) ->
            old.index == fresh.index && fresh.sourceSha256 != null && old.sourcePath == fresh.sourcePath &&
                old.sourceSha256 == fresh.sourceSha256
        }

    private fun normalizedScope(requested: List<Int>?, pageIndices: List<Int>): List<Int>? {
        if (requested == null) return null
        require(requested.isNotEmpty() && requested.size <= MAX_PAGES && requested.distinct().size == requested.size &&
            requested.all { it in pageIndices }) { "Requested translation pages must be unique existing chapter pages." }
        return requested.sorted()
    }

    private fun validateOwner(owner: String?) {
        require(owner == null || owner.isNotBlank() && owner.length <= MAX_OWNER_CHARS && owner.none { it.isISOControl() }) {
            "Invalid translation request identity."
        }
    }

    private fun validatedPage(page: ChapterTranslationPage, config: ChapterTranslationConfig): ChapterTranslationPage {
        val source = page.sourcePath?.let { runCatching { requireManagedSource(it) }.getOrNull() }
        val hash = source?.takeIf(::validSource)?.let(::sha256)
        if (hash == null || hash != page.sourceSha256) return ChapterTranslationPage(page.index, source?.absolutePath, hash,
            status = if (hash == null) ChapterTranslationPageStatus.FAILED else ChapterTranslationPageStatus.PENDING,
            error = if (hash == null) "Saved source page is missing. Retry its source." else "Source page changed. Resume to rebuild its translation.")
        if (page.lettering.isEmpty() && page.cleanedPath == null) return page
        val output = page.cleanedPath?.let { runCatching { requireManagedOutput(it) }.getOrNull() }
        val usable = runCatching {
            validateMetadata(page, config)
            output != null && output.isFile && output.length() in 1..MAX_SURFACE_BYTES && surfaceReadable(output) &&
                sha256(output) == page.cleanedSha256 && page.lettering.isNotEmpty()
        }.getOrDefault(false)
        return if (usable) page else ChapterTranslationPage(page.index, page.sourcePath, hash,
            error = "Saved translation surface is missing or corrupt. Resume to rebuild this page.")
    }

    private fun current(taskId: String, generation: String): ChapterTranslationTask? = tasks[taskId]?.takeIf {
        it.generation == generation && it.chapterId !in chapterRemovals &&
            it.status in setOf(ChapterTranslationStatus.QUEUED, ChapterTranslationStatus.RUNNING)
    }

    private fun interruptedPage(page: ChapterTranslationPage): ChapterTranslationPage =
        if (page.status != ChapterTranslationPageStatus.RUNNING) page else page.copy(
            status = if (page.lettering.isEmpty()) ChapterTranslationPageStatus.PENDING else ChapterTranslationPageStatus.PARTIAL)

    private fun requireManagedSource(path: String): File {
        val file = File(path).canonicalFile
        require(file.parentFile == sourceDirectory.canonicalFile && file.name.length <= MAX_FILENAME_CHARS) {
            "Translation can only read pages in private chapter storage."
        }
        return file
    }

    private fun requireManagedOutput(path: String, taskId: String? = null): File {
        val file = File(path).canonicalFile
        val parent = file.parentFile ?: throw IllegalArgumentException("Missing output folder.")
        require(parent.parentFile == directory.canonicalFile && parent.name.matches(ID) &&
            (taskId == null || parent.name == taskId) && file.name.matches(OUTPUT_NAME)) {
            "Translation output is outside its private task folder."
        }
        return file
    }

    private fun validSource(file: File): Boolean = file.isFile && file.length() in 1..MAX_SOURCE_BYTES

    private fun validateMetadata(page: ChapterTranslationPage, config: ChapterTranslationConfig) {
        require((page.originalWidth == null) == (page.originalHeight == null))
        page.originalWidth?.let { width ->
            val height = requireNotNull(page.originalHeight)
            require(width in 1..100_000 && height in 1..100_000 && width.toLong() * height <= 100_000_000L)
            require(page.imageWidth in 1..width && page.imageHeight in 1..height)
        }
        require(page.index >= 0 && page.lettering.size <= MAX_LETTERING && page.rejectedRegions in 0..MAX_LETTERING)
        require((page.error?.length ?: 0) <= MAX_ERROR_CHARS)
        if (page.lettering.isEmpty()) {
            require(page.status != ChapterTranslationPageStatus.COMPLETED) { "A translated page needs successful lettering." }
            return
        }
        require(page.imageWidth > 0 && page.imageHeight > 0 && page.imageWidth.toLong() * page.imageHeight <= MAX_SURFACE_PIXELS)
        require(page.status in setOf(ChapterTranslationPageStatus.COMPLETED, ChapterTranslationPageStatus.PARTIAL, ChapterTranslationPageStatus.RUNNING))
        page.lettering.forEach { text ->
            text.originalSourceBounds?.validate(requireNotNull(page.originalWidth), requireNotNull(page.originalHeight))
            require(text.source.length in 1..MAX_TEXT_CHARS && text.translated.length in 1..MAX_TEXT_CHARS)
            require(text.savedHindiDraft == null || text.savedHindiDraft.length in 1..MAX_TEXT_CHARS)
            require(text.left >= 0 && text.top >= 0 && text.right > text.left && text.bottom > text.top &&
                text.right <= page.imageWidth && text.bottom <= page.imageHeight)
            require(text.sourceLeft >= 0 && text.sourceTop >= 0 && text.sourceRight > text.sourceLeft &&
                text.sourceBottom > text.sourceTop && text.sourceRight <= page.imageWidth && text.sourceBottom <= page.imageHeight)
            require(text.family in setOf("sans-serif", "sans-serif-condensed", "serif", "monospace", "cursive") && text.face in 0..3 &&
                text.size.isFinite() && text.size in 1f..4096f && text.alignment in setOf("ALIGN_NORMAL", "ALIGN_CENTER", "ALIGN_OPPOSITE"))
            require(TranslationQualityPolicy.isUsable(text.source, text.translated, config.targetLanguage, text.savedHindiDraft)) {
                "Saved translation failed quality checks."
            }
        }
    }

    /** Publish after the atomic disk commit; readers never observe a checkpoint that failed to persist. */
    private fun save(task: ChapterTranslationTask) {
        val bytes = encode(task).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JOURNAL_BYTES) { "Chapter translation metadata exceeds the safe journal limit. Completed pages have been kept." }
        val file = File(directory, task.id + ".json")
        val total = journalFiles().filter { it.name != file.name }.sumOf { it.length() }
        require(total + bytes.size <= MAX_TOTAL_JOURNAL_BYTES) { "Translation history storage is full. Existing results have been kept." }
        journalIo.write(file, bytes)
        tasks[task.id] = task
        publish()
    }

    private fun saveVerified(task: ChapterTranslationTask) {
        save(task)
        awaitingValidation.remove(task.id)
        publish()
    }

    private fun visible(task: ChapterTranslationTask): ChapterTranslationTask = if (task.id !in awaitingValidation) task else task.copy(
        status = if (task.status == ChapterTranslationStatus.CANCELLED) task.status else ChapterTranslationStatus.PAUSED,
        pages = task.pages.map { page -> if (page.lettering.isEmpty() && page.cleanedPath == null) page else
            ChapterTranslationPage(page.index, page.sourcePath, page.sourceSha256, error = "Checking saved translation files.") },
        error = "Checking saved translation files.", validationPending = true)

    private fun publish() { _states.value = tasks.values.sortedByDescending { it.updatedAt }.map(::visible) }

    private fun journalFiles(): List<File> = directory.listFiles().orEmpty().mapNotNull { file ->
        val name = if (file.name.endsWith(".json.bak")) file.name.removeSuffix(".bak") else file.name
        if (name.matches(Regex("[a-f0-9]{32}\\.json"))) File(directory, name) else null
    }.distinctBy { it.name }

    private fun encode(task: ChapterTranslationTask): JSONObject = JSONObject().put("version", 2).put("id", task.id)
        .put("generation", task.generation).put("chapterId", task.chapterId).put("title", task.title)
        .put("config", configJson(task.config)).put("status", task.status.name).put("createdAt", task.createdAt)
        .put("requestedPages", task.requestedPages?.let { JSONArray(it) }).put("ownerRequestId", task.ownerRequestId)
        .put("updatedAt", task.updatedAt).put("error", task.error).put("pages", JSONArray().apply {
            task.pages.forEach { page -> put(JSONObject().put("index", page.index)
                .put("sourceFile", page.sourcePath?.let { File(it).name }).put("sourceSha256", page.sourceSha256)
                .put("status", page.status.name).put("cleanedFile", page.cleanedPath?.let { File(it).name })
                .put("cleanedSha256", page.cleanedSha256).put("width", page.imageWidth).put("height", page.imageHeight)
                .put("originalWidth", page.originalWidth).put("originalHeight", page.originalHeight)
                .put("rejected", page.rejectedRegions).put("error", page.error).put("lettering", JSONArray().apply {
                    page.lettering.forEach { text -> put(JSONObject().put("source", text.source).put("translated", text.translated)
                        .put("savedHindiDraft", text.savedHindiDraft)
                        .put("originalSourceBounds", text.originalSourceBounds?.let { bounds -> JSONObject().put("version", bounds.version)
                            .put("left", bounds.left).put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom) })
                        .put("l", text.left).put("t", text.top).put("r", text.right).put("b", text.bottom)
                        .put("family", text.family).put("face", text.face).put("color", text.color).put("size", text.size)
                        .put("alignment", text.alignment).put("sl", text.sourceLeft).put("st", text.sourceTop)
                        .put("sr", text.sourceRight).put("sb", text.sourceBottom)) }
                })) }
        })

    private fun decode(json: JSONObject): ChapterTranslationTask {
        val version = json.getInt("version").also { require(it in 1..2) }
        val id = json.getString("id").also { require(it.matches(ID)) }
        val generation = json.getString("generation").also { require(it.matches(ID)) }
        val chapterId = json.getString("chapterId").also { require(it.matches(ID)) }
        val c = json.getJSONObject("config")
        val config = ChapterTranslationConfig(c.getString("targetLanguage"), c.getString("styleId"), c.optString("customStyle"),
            c.getString("ocrScript"), c.getBoolean("highAccuracy"), c.getBoolean("preserveStyle"), c.getBoolean("localRefinement"),
            if (!c.has("refinementRequest") || c.isNull("refinementRequest")) null
            else TranslationRefinementRequestCodec.decode(c.getJSONObject("refinementRequest"))).normalized()
        require(id == digest(chapterId + "|" + configIdentity(config)).take(32))
        val rows = json.getJSONArray("pages")
        require(rows.length() in 1..MAX_PAGES)
        val pages = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val source = row.nullableString("sourceFile")?.let { name ->
                require(name == File(name).name && name.length <= MAX_FILENAME_CHARS)
                requireManagedSource(File(sourceDirectory, name).absolutePath).absolutePath
            }
            val output = row.nullableString("cleanedFile")?.let { name ->
                require(name == File(name).name)
                requireManagedOutput(File(File(directory, id), name).absolutePath, id).absolutePath
            }
            val letters = row.getJSONArray("lettering")
            require(letters.length() <= MAX_LETTERING)
            val page = ChapterTranslationPage(row.getInt("index"), source, row.nullableString("sourceSha256"),
                ChapterTranslationPageStatus.valueOf(row.getString("status")), output, row.nullableString("cleanedSha256"),
                row.getInt("width"), row.getInt("height"), (0 until letters.length()).map { n ->
                    val t = letters.getJSONObject(n)
                    SavedMangaLettering(t.getString("source"), t.getString("translated"), t.getInt("l"), t.getInt("t"), t.getInt("r"), t.getInt("b"),
                        t.getString("family"), t.getInt("face"), t.getInt("color"), t.getDouble("size").toFloat(), t.getString("alignment"),
                        t.getInt("sl"), t.getInt("st"), t.getInt("sr"), t.getInt("sb"), t.nullableString("savedHindiDraft"),
                        if (version < 2) null else t.optJSONObject("originalSourceBounds")?.let { bounds ->
                            SavedOriginalSourceBounds(bounds.getInt("version"), bounds.getInt("left"), bounds.getInt("top"), bounds.getInt("right"), bounds.getInt("bottom"))
                        })
                }, row.getInt("rejected"), row.nullableString("error"),
                if (version < 2 || !row.has("originalWidth") || row.isNull("originalWidth")) null else row.getInt("originalWidth"),
                if (version < 2 || !row.has("originalHeight") || row.isNull("originalHeight")) null else row.getInt("originalHeight"))
            require(page.sourceSha256 == null || page.sourceSha256.matches(HASH))
            require(page.cleanedSha256 == null || page.cleanedSha256.matches(HASH))
            validateMetadata(page, config)
            page
        }
        require(pages.map { it.index }.distinct().size == pages.size)
        val scope = if (!json.has("requestedPages") || json.isNull("requestedPages")) null else {
            val requested = json.getJSONArray("requestedPages")
            require(requested.length() in 1..MAX_PAGES)
            normalizedScope((0 until requested.length()).map { requested.getInt(it) }, pages.map { it.index })
        }
        val owner = json.nullableString("ownerRequestId").also(::validateOwner)
        return ChapterTranslationTask(id, generation, chapterId, json.getString("title").also { require(it.length <= MAX_TITLE_CHARS) }, config, pages,
            ChapterTranslationStatus.valueOf(json.getString("status")), json.getLong("createdAt"), json.getLong("updatedAt"),
            json.nullableString("error")?.also { require(it.length <= MAX_ERROR_CHARS) }, requestedPages = scope, ownerRequestId = owner)
    }

    private fun configJson(c: ChapterTranslationConfig): JSONObject = JSONObject().put("targetLanguage", c.targetLanguage)
        .put("styleId", c.styleId).put("customStyle", c.customStyle).put("ocrScript", c.ocrScript)
        .put("highAccuracy", c.highAccuracy).put("preserveStyle", c.preserveStyle).put("localRefinement", c.localRefinement)
        .put("refinementRequest", c.refinementRequest?.let(TranslationRefinementRequestCodec::encode))

    internal fun memoryConfigurationIdentity(c: ChapterTranslationConfig) = digest(configIdentity(c))

    private fun configIdentity(c: ChapterTranslationConfig): String = (listOf(c.targetLanguage, c.styleId, c.customStyle,
        c.ocrScript, c.highAccuracy.toString(), c.preserveStyle.toString(), c.localRefinement.toString()) +
        listOfNotNull(c.refinementRequest?.let(TranslationRefinementRequestCodec::identity)))
        .joinToString("|") { value -> "${value.length}:$value" }

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key) || !has(key)) null else getString(key)

    companion object {
        internal const val MAX_JOURNAL_BYTES = 4_000_000L
        private const val MAX_TOTAL_JOURNAL_BYTES = 32_000_000L
        private const val MAX_TASKS = 64
        internal const val MAX_PAGES = 1000
        internal const val MAX_LETTERING = 256
        internal const val MAX_TEXT_CHARS = 8000
        internal const val MAX_ERROR_CHARS = 300
        private const val MAX_TITLE_CHARS = 250
        private const val MAX_OWNER_CHARS = 160
        private const val MAX_FILENAME_CHARS = 240
        private const val MAX_SOURCE_BYTES = 40L * 1024L * 1024L
        internal const val MAX_SURFACE_BYTES = 64L * 1024L * 1024L
        internal const val MAX_SURFACE_PIXELS = 12_000_000L
        private val ID = Regex("[a-f0-9]{32}")
        private val HASH = Regex("[a-f0-9]{64}")
        private val OUTPUT_NAME = Regex("[a-f0-9]{32}_[0-9]+_[a-f0-9]{32}\\.png")
        @Volatile private var instance: ChapterTranslationStore? = null

        fun shared(context: Context): ChapterTranslationStore = instance ?: synchronized(this) {
            instance ?: ChapterTranslationStore(File(context.applicationContext.filesDir, "chapter_translations"),
                File(context.applicationContext.filesDir, "chapters")).also { instance = it }
        }

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_SURFACE_BYTES) { "Page file grew beyond the safe storage limit." }
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        private fun token(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
