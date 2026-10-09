package com.mangalens.core.translation

import java.io.File
import java.io.IOException

/** Preferences describe a UI choice. They do not contain a saved model or full captured style. */
internal data class ReaderTranslationChoice(
    val target: String, val style: String, val custom: String, val script: String,
    val highAccuracy: Boolean, val preserveStyle: Boolean, val localRefinement: Boolean
) {
    fun matches(configuration: ChapterTranslationConfig): Boolean =
        target == configuration.targetLanguage && style == configuration.styleId && custom == configuration.customStyle &&
            script == configuration.ocrScript && highAccuracy == configuration.highAccuracy &&
            preserveStyle == configuration.preserveStyle && localRefinement == configuration.localRefinement

    companion object {
        fun from(configuration: ChapterTranslationConfig): ReaderTranslationChoice {
            require(configuration.refinementRequest == null) { "A captured request must be selected by its full receipt." }
            val normalized = configuration.normalized()
            return ReaderTranslationChoice(normalized.targetLanguage, normalized.styleId, normalized.customStyle,
                normalized.ocrScript, normalized.highAccuracy, normalized.preserveStyle, normalized.localRefinement)
        }
    }
}

internal data class ReaderTranslationSource(val index: Int, val path: String?, val sha256: String?)

/** Once chosen, flow updates cannot replace any model/style/source/owner/generation in this receipt. */
internal data class ReaderTranslationReceipt(val taskId: String, val generation: String, val owner: String?,
    val chapterId: String, val configuration: ChapterTranslationConfig, val sources: List<ReaderTranslationSource>)

internal object ReaderTranslationPresentation {
    fun capture(task: ChapterTranslationTask, chapterId: String, choice: ReaderTranslationChoice,
        paths: Map<Int, String?>, directory: File): ReaderTranslationReceipt? {
        val normalized = runCatching { task.config.normalized() }.getOrNull() ?: return null
        if (task.config != normalized) return null
        return task.savedReceipt().takeIf { matchesReader(it, chapterId, choice, paths, directory) }
    }

    /** Cold discovery chooses one saved receipt; no ambient model selection or recapture occurs. */
    fun restore(tasks: List<ChapterTranslationTask>, chapterId: String, choice: ReaderTranslationChoice,
        paths: Map<Int, String?>, directory: File): ReaderTranslationReceipt? = tasks.asSequence()
        .sortedWith(compareByDescending<ChapterTranslationTask> { it.updatedAt }.thenBy { it.id })
        .mapNotNull { capture(it, chapterId, choice, paths, directory) }.firstOrNull()

    fun select(tasks: List<ChapterTranslationTask>, receipt: ReaderTranslationReceipt, chapterId: String,
        choice: ReaderTranslationChoice, paths: Map<Int, String?>, directory: File): ChapterTranslationTask? =
        if (!matchesReader(receipt, chapterId, choice, paths, directory)) null
        else tasks.firstOrNull { matchesTask(receipt, it) }

    fun matchesTask(receipt: ReaderTranslationReceipt, task: ChapterTranslationTask): Boolean =
        task.id == receipt.taskId && task.generation == receipt.generation && task.ownerRequestId == receipt.owner &&
            task.chapterId == receipt.chapterId && task.config == receipt.configuration && sameSourceScope(receipt, task)

    fun matchesReader(receipt: ReaderTranslationReceipt, chapterId: String, choice: ReaderTranslationChoice,
        paths: Map<Int, String?>, directory: File): Boolean = receipt.chapterId == chapterId &&
        choice.matches(receipt.configuration) && receipt.sources.map { it.index }.toSet() == paths.keys &&
        receipt.sources.size == paths.size && receipt.sources.all { source ->
            sameManagedPath(source.path, paths[source.index], directory)
        }

    /** Only the exact returned explicit command receipt may advance a bound generation. */
    fun continueReceipt(receipt: ReaderTranslationReceipt, task: ChapterTranslationTask): ReaderTranslationReceipt? =
        task.savedReceipt().takeIf { next -> next.taskId == receipt.taskId && next.owner == receipt.owner &&
            next.chapterId == receipt.chapterId && next.configuration == receipt.configuration && sameSourceScope(receipt, task) }

    fun receipt(task: ChapterTranslationTask) = task.savedReceipt()

    // This is metadata scope, including failed/missing pages. The Store validates source hashes
    // and outputs before presentation; the rendering filter separately requires an existing file.
    private fun sameManagedPath(stored: String?, visible: String?, directory: File): Boolean {
        if (stored == null) return visible == null
        if (stored.isBlank() || visible?.isBlank() == true) return false
        val expected = File(stored)
        if (!expected.isAbsolute || visible != null && !File(visible).isAbsolute) return false
        return try {
            val managed = directory.canonicalFile
            val source = expected.canonicalFile
            source.parentFile == managed && if (visible == null) !source.isFile else File(visible).canonicalFile == source
        } catch (_: IOException) { false }
        catch (_: SecurityException) { false }
    }

    private fun sameSourceScope(receipt: ReaderTranslationReceipt, task: ChapterTranslationTask): Boolean {
        if (task.pages.size != receipt.sources.size) return false
        return task.pages.zip(receipt.sources).all { (page, source) ->
            page.index == source.index && page.sourcePath == source.path &&
                (page.sourceSha256 == source.sha256 || !page.isComplete && page.cleanedPath == null &&
                    page.cleanedSha256 == null && page.lettering.isEmpty() && page.imageWidth == 0 && page.imageHeight == 0)
        }
    }

    private fun ChapterTranslationTask.savedReceipt() = ReaderTranslationReceipt(id, generation, ownerRequestId, chapterId,
        config, sourceRows(this))
    private fun sourceRows(task: ChapterTranslationTask) = task.pages.map { ReaderTranslationSource(it.index, it.sourcePath, it.sourceSha256) }
}
