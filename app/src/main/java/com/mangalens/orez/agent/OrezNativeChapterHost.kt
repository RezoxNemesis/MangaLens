package com.mangalens.orez.agent

import android.content.Context
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.translation.ChapterTranslationConfig
import com.mangalens.core.translation.ChapterTranslationJobs
import com.mangalens.core.translation.ChapterTranslationStore
import com.mangalens.core.translation.ChapterTranslationTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Actual native provider: private library sources -> durable foreground translation -> validated journal. */
class OrezNativeChapterHost(
    context: Context,
    private val translations: ChapterTranslationStore = ChapterTranslationStore.shared(context),
    private val library: ChapterLibrary = ChapterLibrary(context)
) : OrezChapterHost {
    private val appContext = context.applicationContext
    private val sources = File(appContext.filesDir, "chapters")

    override suspend fun inspect(chapterId: String): OrezChapterSnapshot? = withContext(Dispatchers.IO) {
        val chapter = library.list().firstOrNull { it.id == chapterId } ?: return@withContext null
        OrezChapterSourceEvidence.inspect(sources, chapter.id, chapter.title, chapter.pages.map { OrezChapterSource(it.index, it.localPath) })
    }

    override suspend fun start(chapter: OrezChapterSnapshot, options: OrezTranslationOptions,
        requestId: String, allowReplacement: Boolean): OrezChapterReceipt = withContext(Dispatchers.IO) {
        val saved = requireNotNull(library.list().firstOrNull { it.id == chapter.chapterId }) { "Selected saved chapter is missing." }
        val current = requireNotNull(inspect(chapter.chapterId))
        require(current.sourceFingerprint == chapter.sourceFingerprint && current.pageCount == chapter.pageCount) { "Chapter source changed before dispatch." }
        val config = options.nativeConfig()
        if (!allowReplacement) {
            val slot = translations.states.value.firstOrNull { it.chapterId == chapter.chapterId && it.config == config }
            require(slot == null || slot.ownerRequestId == requestId) { "The native translation slot now belongs to another request." }
        }
        val started = ChapterTranslationJobs.start(appContext, saved, config, ownerRequestId = requestId,
            allowOwnerReplacement = allowReplacement)
        try {
            requireNotNull(observe(started.id)) { "Native translation journal could not be verified." }.also {
                OrezChapterTools.verify(it, chapter, options, requestId)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // A source replacement during native hashing must not widen this workflow's scope.
            withContext(NonCancellable) {
                if (translations.get(started.id)?.let { it.ownerRequestId == requestId && it.generation == started.generation } == true)
                    ChapterTranslationJobs.cancel(appContext, started.id, started.generation)
            }
            throw failure
        }
    }

    override suspend fun observe(taskId: String): OrezChapterReceipt? = withContext(Dispatchers.IO) {
        translations.refresh(taskId)?.receipt()
    }

    override suspend fun findOwned(requestId: String): OrezChapterReceipt? = withContext(Dispatchers.IO) {
        val owned = translations.states.value.firstOrNull { it.ownerRequestId == requestId } ?: return@withContext null
        observe(owned.id)?.takeIf { it.ownerRequestId == requestId }
    }

    override suspend fun pause(receipt: OrezChapterReceipt): OrezChapterReceipt? = withContext(Dispatchers.IO) {
        owned(receipt) ?: return@withContext null
        ChapterTranslationJobs.pause(appContext, receipt.taskId, receipt.generation)?.receipt()
    }
    override suspend fun resume(receipt: OrezChapterReceipt): OrezChapterReceipt? = withContext(Dispatchers.IO) {
        owned(receipt) ?: return@withContext null
        ChapterTranslationJobs.resume(appContext, receipt.taskId, receipt.generation)?.receipt()
    }
    override suspend fun cancel(receipt: OrezChapterReceipt): OrezChapterReceipt? = withContext(Dispatchers.IO) {
        owned(receipt) ?: return@withContext null
        ChapterTranslationJobs.cancel(appContext, receipt.taskId, receipt.generation)?.receipt()
    }

    private suspend fun owned(receipt: OrezChapterReceipt): OrezChapterReceipt? = observe(receipt.taskId)?.takeIf {
        it.generation == receipt.generation && it.ownerRequestId == receipt.ownerRequestId && it.ownerRequestId != null &&
            it.chapter.sourceFingerprint == receipt.chapter.sourceFingerprint && it.options == receipt.options
    }

    private fun ChapterTranslationTask.receipt(): OrezChapterReceipt {
        require(!validationPending) { "Native translation files are still awaiting validation." }
        require(requestedPages == null) { "This workflow requires its captured whole-chapter scope." }
        val chapter = OrezChapterSourceEvidence.snapshot(chapterId, title, pages.map { OrezChapterSource(it.index, it.sourcePath, it.sourceSha256) })
        return OrezChapterReceipt(id, generation, ownerRequestId, chapter, config.agentOptions(),
            OrezNativeChapterStatus.valueOf(status.name), completedPages, pages.sumOf { it.lettering.size }, error)
    }

    private fun OrezTranslationOptions.nativeConfig() = ChapterTranslationConfig(targetLanguage, styleId, customStyle, ocrScript,
        highAccuracy, preserveStyle, localRefinement).normalized()
    private fun ChapterTranslationConfig.agentOptions() = OrezTranslationOptions(targetLanguage, styleId, customStyle, ocrScript,
        highAccuracy, preserveStyle, localRefinement)
}
