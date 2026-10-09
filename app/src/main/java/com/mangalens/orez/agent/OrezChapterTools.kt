package com.mangalens.orez.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class OrezChapterSnapshot(val chapterId: String, val title: String, val pageCount: Int, val sourceFingerprint: String)
enum class OrezNativeChapterStatus { QUEUED, RUNNING, PAUSED, COMPLETED, PARTIAL, FAILED, CANCELLED }
data class OrezChapterReceipt(
    val taskId: String,
    val generation: String,
    val ownerRequestId: String?,
    val chapter: OrezChapterSnapshot,
    val options: OrezTranslationOptions,
    val status: OrezNativeChapterStatus,
    val completedPages: Int,
    val translatedRegions: Int = 0,
    val error: String? = null
)

/** Typed host boundary, implemented by saved-library IO and the native translation journal/worker. */
interface OrezChapterHost {
    suspend fun inspect(chapterId: String): OrezChapterSnapshot?
    suspend fun start(chapter: OrezChapterSnapshot, options: OrezTranslationOptions, requestId: String, allowReplacement: Boolean): OrezChapterReceipt
    suspend fun observe(taskId: String): OrezChapterReceipt?
    suspend fun observe(taskId: String, requestId: String, chapter: OrezChapterSnapshot, options: OrezTranslationOptions,
        expectedGeneration: String): OrezChapterReceipt? = observe(taskId)?.takeIf {
        it.ownerRequestId == requestId && it.generation == expectedGeneration
    }?.also { OrezChapterTools.verify(it, chapter, options, requestId) }
    suspend fun findOwned(requestId: String): OrezChapterReceipt?
    suspend fun pause(receipt: OrezChapterReceipt): OrezChapterReceipt?
    suspend fun resume(receipt: OrezChapterReceipt): OrezChapterReceipt?
    suspend fun cancel(receipt: OrezChapterReceipt): OrezChapterReceipt?
}

class OrezChapterTools(
    private val host: OrezChapterHost,
    private val authorization: OrezTaskAuthorization,
    private val stopOwned: (suspend (OrezChapterReceipt) -> OrezChapterReceipt?)? = null,
    private val mayStopOwned: (suspend () -> Boolean)? = null,
    private val isExecuting: suspend () -> Boolean = { true }
) : OrezDurableTools {
    override suspend fun execute(step: OrezPlanStep, requestId: String): OrezToolResult {
        try {
            require(authorization.origin == OrezTrustOrigin.USER && authorization.explicitUserRequest) { "Explicit captured user scope is missing." }
            val chapterId = requireNotNull(step.call.arguments["chapterId"])
            require(chapterId in authorization.chapterIds) { "Chapter exceeds captured user scope." }
            val chapter = requireNotNull(host.inspect(chapterId)) { "The selected saved chapter is missing." }
            if (step.call.name == "inspect_saved_chapter") return OrezToolResult.Completed(chapter.outputs(requestId))
            require(step.call.name == "translate_saved_chapter") { "Unsupported native chapter tool." }
            require(chapter.sourceFingerprint == step.call.arguments["sourceFingerprint"]) { "Chapter sources changed after inspection. Start a new request." }
            val options = requireNotNull(authorization.translation)
            require(step.call.arguments["targetLanguage"] == options.targetLanguage) { "Translation target exceeds captured scope." }
            if (!isExecuting()) return OrezToolResult.Pending("Task paused before native dispatch.", needsResume = true)
            val previousTaskId = step.outputs["translationTaskId"]
            val receipt = if (previousTaskId != null) {
                require(step.outputs["requestId"] == requestId) { "Native receipt belongs to another request." }
                requireNotNull(host.observe(previousTaskId, requestId, chapter, options, step.outputs.getValue("generation"))) {
                    "Saved translation receipt is missing or replaced. Start a new request."
                }.also {
                    require(it.generation == step.outputs["generation"]) { "The native translation was replaced by another generation. Start a new request." }
                }
            } else {
                options.requireCapturedChapterRefinement()
                // After process loss, native start is idempotent for this stable owner. A running
                // replay may not overwrite a slot now owned by a different user request.
                host.start(chapter, options, requestId, allowReplacement = step.status == OrezStepStatus.PENDING)
            }
            verify(receipt, chapter, options, requestId)
            if (!isExecuting()) {
                val paused = if (canStop()) stop(receipt) ?: receipt else receipt
                return OrezToolResult.Pending("Task paused during native dispatch.", needsResume = true, outputs = paused.outputs(requestId))
            }
            val outputs = receipt.outputs(requestId)
            return when (receipt.status) {
                OrezNativeChapterStatus.COMPLETED -> {
                    require(receipt.completedPages == chapter.pageCount) { "Saved translation does not contain every scoped page." }
                    OrezToolResult.Completed(outputs + ("destination" to "chapter-translation:${receipt.taskId}"))
                }
                OrezNativeChapterStatus.PAUSED -> OrezToolResult.Pending("Chapter translation is paused. Resume this task to continue.", true, outputs)
                OrezNativeChapterStatus.CANCELLED -> OrezToolResult.Cancelled("The owned chapter translation was cancelled.")
                OrezNativeChapterStatus.PARTIAL, OrezNativeChapterStatus.FAILED -> OrezToolResult.Failed(
                    receipt.error ?: "${receipt.completedPages}/${chapter.pageCount} pages verified. Resume to retry unfinished pages.")
                else -> OrezToolResult.Pending("Chapter translation is continuing in its native worker.", outputs = outputs)
            }
        } catch (cancelled: CancellationException) {
            // A user pause/cancel can race the native commit before the Orez receipt was saved.
            // Stop only this request's owned generation; ordinary OS interruption keeps it alive.
            withContext(NonCancellable) {
                if (canStop()) host.findOwned(requestId)?.takeIf {
                    it.ownerRequestId == requestId && it.chapter.chapterId in authorization.chapterIds && it.options == authorization.translation
                }?.let { stop(it) }
            }
            throw cancelled
        } catch (failure: Exception) {
            return OrezToolResult.Failed(failure.message ?: "Native chapter workflow failed.")
        }
    }

    private suspend fun stop(receipt: OrezChapterReceipt) = if (stopOwned == null) host.pause(receipt) else stopOwned.invoke(receipt)
    private suspend fun canStop() = mayStopOwned?.invoke() ?: !isExecuting()

    companion object {
        fun verify(receipt: OrezChapterReceipt, chapter: OrezChapterSnapshot, options: OrezTranslationOptions, requestId: String) {
            require(receipt.ownerRequestId == requestId) { "Native translation belongs to another request." }
            require(receipt.chapter.chapterId == chapter.chapterId && receipt.chapter.sourceFingerprint == chapter.sourceFingerprint &&
                receipt.chapter.pageCount == chapter.pageCount) { "Native translation belongs to another chapter source or scope." }
            require(receipt.options == options) { "Native translation settings differ from the captured request." }
        }

        fun OrezChapterSnapshot.outputs(requestId: String) = mapOf("requestId" to requestId, "chapterId" to chapterId,
            "sourceFingerprint" to sourceFingerprint, "pageCount" to pageCount.toString(), "title" to title.take(250))

        fun OrezChapterReceipt.outputs(requestId: String) = chapter.outputs(requestId) + mapOf(
            "ownerRequestId" to ownerRequestId.orEmpty(), "translationTaskId" to taskId, "generation" to generation,
            "targetLanguage" to options.targetLanguage, "status" to status.name, "completedPages" to completedPages.toString(),
            "translatedRegions" to translatedRegions.toString()) + options.refinementRequestFingerprint()?.let {
                mapOf("refinementRequestFingerprint" to it)
            }.orEmpty()
    }
}
