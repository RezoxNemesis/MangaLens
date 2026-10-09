package com.mangalens.orez.agent

import android.content.Context
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.translation.ChapterTranslationConfig
import com.mangalens.core.translation.ChapterTranslationJobs
import com.mangalens.core.translation.ChapterTranslationStore
import com.mangalens.core.translation.ChapterTranslationTask
import com.mangalens.orez.OrezRoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Actual native provider: private library sources -> durable foreground translation -> validated journal. */
class OrezNativeChapterHost(
    context: Context,
    private val translations: ChapterTranslationStore = ChapterTranslationStore.shared(context),
    private val library: ChapterLibrary = ChapterLibrary(context),
    private val ownerPlan: (suspend (String) -> OrezTaskPlan?)? = null
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
        options.requireCapturedChapterRefinement()
        val config = options.nativeChapterConfig()
        if (!allowReplacement) {
            val slot = translations.states.value.firstOrNull { it.chapterId == chapter.chapterId && it.config == config }
            require(slot == null || slot.ownerRequestId == requestId) { "The native translation slot now belongs to another request." }
        }
        val started = ChapterTranslationJobs.start(appContext, saved, config, ownerRequestId = requestId,
            allowOwnerReplacement = allowReplacement)
        try {
            requireNotNull(observe(started.id, requestId, chapter, options, started.generation)) { "Native translation journal could not be verified." }.also {
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
        val captured = translations.get(taskId) ?: return@withContext null
        val owner = captured.ownerRequestId ?: return@withContext null
        val scope = scope(owner) ?: return@withContext null
        observe(taskId, owner, scope.first, scope.second, captured.generation)
    }

    override suspend fun observe(taskId: String, requestId: String, chapter: OrezChapterSnapshot,
        options: OrezTranslationOptions, expectedGeneration: String): OrezChapterReceipt? = withContext(Dispatchers.IO) {
        OrezOwnedChapterLookup.refresh(translations, taskId, requestId, expectedGeneration) { captured ->
            OrezChapterTools.verify(OrezChapterNativeEvidence.metadataReceipt(captured), chapter, options, requestId)
        }?.receipt()
    }

    override suspend fun findOwned(requestId: String): OrezChapterReceipt? = withContext(Dispatchers.IO) {
        val captured = translations.states.value.firstOrNull { it.ownerRequestId == requestId } ?: return@withContext null
        val scope = scope(requestId) ?: return@withContext null
        observe(captured.id, requestId, scope.first, scope.second, captured.generation)
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

    private suspend fun owned(receipt: OrezChapterReceipt): OrezChapterReceipt? = receipt.ownerRequestId?.let { owner ->
        observe(receipt.taskId, owner, receipt.chapter, receipt.options, receipt.generation)
    }

    private suspend fun scope(requestId: String): Pair<OrezChapterSnapshot, OrezTranslationOptions>? {
        val plan = (if (ownerPlan != null) ownerPlan.invoke(requestId) else {
            val plans = OrezTaskStore(OrezRoomDatabase.get(appContext).tasks())
            OrezDownloadTaskLink.candidates(requestId).firstNotNullOfOrNull { id -> plans.load(id)?.takeIf {
                it.steps.any { step -> step.call.name == "translate_saved_chapter" && OrezDurablePlanRules.requestId(id, step.index) == requestId }
            } }
        }) ?: return null
        val step = plan.steps.firstOrNull { it.call.name == "translate_saved_chapter" &&
            OrezDurablePlanRules.requestId(plan.id, it.index) == requestId } ?: return null
        return OrezChapterPlanScope.expected(plan, step) to requireNotNull(plan.authorization?.translation)
    }

    private fun ChapterTranslationTask.receipt() = OrezChapterNativeEvidence.receipt(this)
}
