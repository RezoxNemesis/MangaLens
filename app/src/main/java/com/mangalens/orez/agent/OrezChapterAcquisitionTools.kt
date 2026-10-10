package com.mangalens.orez.agent

import android.content.Context
import com.mangalens.core.acquisition.GenericMangaSourceAdapter
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.reader.ProgressiveChapterRepository
import com.mangalens.core.reader.SavedChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** The existing WorkManager request owns acquisition; a route or renderer is never its completion. */
internal class OrezChapterAcquisitionTools(context: Context, private val store: OrezTaskStore, private val plan: OrezTaskPlan) {
    private val app = context.applicationContext
    private val scope = requireNotNull(plan.authorization?.chapterAcquisition).validated()
    suspend fun execute(step: OrezPlanStep, requestId: String): OrezToolResult {
        return try {
            OrezDurablePlanRules.validate(plan)
            OrezToolRegistry().validate(step.call)
            require(requestId == OrezDurablePlanRules.requestId(plan.id, step.index) && step.call == plan.steps[step.index].call)
            require(step.call.name in setOf("save_next_chapter", "save_chapter_url"))
            withContext(Dispatchers.IO) { OrezChapterAcquisitionOwnership.withFiles(app.filesDir) { owner ->
                withTimeoutOrNull(8L * 60 * 1000) { acquire(requestId, owner) }
                    ?: OrezToolResult.Failed("Native chapter acquisition reached its bounded time limit. Resume retains acquired originals and the exact target.")
            } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { OrezToolResult.Failed(failure.message?.take(300) ?: "Unable to acquire and verify this public chapter.") }
    }

    private var currentOwner: OrezAcquisitionPrivateOwner? = null
    private var sourceProof: OrezAcquisitionPrivateOwner.Proof? = null
    private var targetProof: OrezAcquisitionPrivateOwner.Proof? = null
    private fun fresh(owner: OrezAcquisitionPrivateOwner) {
        check(owner.privateReleaseProven()) { "Private chapter close is unproven." }
        owner.requireCurrent(sourceProof); owner.requireCurrent(targetProof)
    }
    private suspend fun acquire(requestId: String, owner: OrezAcquisitionPrivateOwner): OrezToolResult {
        currentOwner = owner
        sourceProof = null; targetProof = null
        owner.publicationGuard = { fresh(owner) }
        current()
        val library = ChapterLibrary(app.filesDir, OrezOwnedChapterJournalIo(owner))
        val journal = OrezChapterAcquisitionJournal(app.filesDir, requestId, owner)
        var record = journal.read()
        record?.let { require(it.scopeFingerprint == scope.fingerprint) { "Native acquisition belongs to another captured source or target." } }
        val budget = OrezChapterSourceReadBudget(OrezNextChapterPolicy.MAX_LOCAL_PROOF_BYTES)
        val recovered = record
        if (recovered?.completed == true) {
            val saved = saved(recovered)
            val proof = verifySaved(library, saved, recovered, budget, owner)
            current(); fresh(owner)
            return OrezToolResult.Completed(outputs(requestId, saved, proof))
        }
        // READY + the exact owned manifest recovers a process death between Library save and receipt.
        if (recovered != null && recovered.allPagesAcquired && library.findMetadata(ChapterLibrary.id(scope.targetUrl)) != null) {
            verifySource(library, budget, owner)
            val saved = saved(recovered)
            val proof = verifySaved(library, saved, recovered, budget, owner)
            current()
            store.publishAcquisition(plan.id, plan.executionEpoch, scope) {
                fresh(owner)
                library.confirmAcquisition(saved) { journal.write(recovered.copy(completed = true)) }
            }
            return OrezToolResult.Completed(outputs(requestId, saved, proof))
        }
        require(library.findMetadata(ChapterLibrary.id(scope.targetUrl)) == null) {
            "This chapter is already in Library. Open its saved record; this request preserves existing successful work."
        }
        verifySource(library, budget, owner)
        val network = OrezChapterAcquisitionNetwork(owner)
        try {
            scope.next?.let { next ->
                current()
                val document = network.readHtml(next.sourceUrl)
                OrezNextChapterPolicy.requireChapterDocument(document.url, next.sourceUrl)
                val target = OrezNextChapterPolicy.next(document.html, document.url)
                require(target == scope.targetUrl && OrezNextChapterPolicy.relation(next.sourceUrl, target) == next.relationFingerprint) {
                    "The next-chapter relation changed. Start a new explicit request; the saved target will not be replaced."
                }
            }
            current()
            val document = network.readHtml(scope.targetUrl)
            OrezNextChapterPolicy.readable(document.html, document.url)
            scope.next?.let {
                OrezNextChapterPolicy.requireChapterDocument(document.url, it.targetUrl)
            }
            val extracted = GenericMangaSourceAdapter().parse(document.html, document.url)
            require(extracted.pageCandidates.size in 1..OrezNextChapterPolicy.MAX_PAGES) {
                "This source has no bounded static chapter image catalog. Open it in Reader; JavaScript, login or challenge bypass is unavailable here."
            }
            extracted.pageCandidates.forEach { OrezNextChapterPolicy.publicUrl(it.url) }
            if (record == null) {
                record = OrezChapterAcquisitionRecord(requestId, scope.fingerprint, extracted.title.take(250), document.sha256, extracted.pageCandidates.toList())
                current(); journal.write(record!!)
            } else require(record!!.candidates == extracted.pageCandidates) {
                "The acquired image catalog changed. This request retains its original files; start a new request for the changed source."
            }
            val repository = ProgressiveChapterRepository(app, network.client, record!!.namespace, owner)
            var originalBytes = 0L
            for ((offset, candidate) in record!!.candidates.withIndex()) {
                current()
                val previous = record!!.pages.firstOrNull { it.index == offset + 1 }
                val verifiedPrevious = previous?.takeIf { it.error == null && it.localPath != null && it.contentRevision != null }
                val acquired = if (verifiedPrevious == null) {
                    try { repository.persistPage(offset + 1, candidate.url, promotion = candidate.promotion) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        // Cancellation/source retirement or an actual unproven private close remains fatal.
                        current()
                        val failed = com.mangalens.core.reader.ChapterPageAcquisitionPolicy.failure(offset + 1, candidate.url)
                        record = record!!.copy(pages = (record!!.pages.filterNot { it.index == failed.index } + failed).sortedBy { it.index })
                        journal.write(record!!)
                        continue
                    }
                } else {
                    val original = SavedChapter(ChapterLibrary.id(scope.targetUrl), record!!.title, scope.targetUrl, listOf(verifiedPrevious))
                    val expected = OrezChapterSourceEvidence.snapshot(original.id, original.title, listOf(
                        OrezChapterSource(verifiedPrevious.index, verifiedPrevious.localPath, requireNotNull(verifiedPrevious.contentRevision).substringBefore(':'))))
                    val actual = owner.inspect(original, budget)
                    require(actual.chapter.sourceFingerprint == expected.sourceFingerprint) {
                        "An earlier acquired original changed or is missing. This request preserves it and cannot reacquire over its captured bytes."
                    }
                    verifiedPrevious
                }
                val bytes = File(requireNotNull(acquired.localPath)).length()
                require(bytes in 1..40L * 1024 * 1024 && bytes <= OrezNextChapterPolicy.MAX_CHAPTER_BYTES - originalBytes) {
                    "The chapter originals exceed the bounded aggregate byte limit. Acquired originals remain intact."
                }
                originalBytes += bytes
                verifiedPrevious?.let { require(it.contentRevision?.substringBefore(':') == acquired.contentRevision?.substringBefore(':')) {
                    "An earlier acquired original changed. This request cannot silently replace its verified source."
                } }
                current()
                if (verifiedPrevious == null) {
                    record = record!!.copy(pages = (record!!.pages.filterNot { it.index == acquired.index } + acquired).sortedBy { it.index })
                    journal.write(record!!)
                }
            }
            if (!record!!.allPagesAcquired) {
                current()
                val missing = record!!.candidates.size - record!!.pages.count { it.error == null && it.localPath != null && it.contentRevision != null }
                return OrezToolResult.Failed("$missing of ${record!!.candidates.size} original chapter pages are unavailable. Acquired originals are retained; resume retries only missing or failed pages. The chapter is not complete or published to Library.")
            }
            val saved = saved(record!!)
            // Exact original-byte proof precedes publication; source selection is checked again after IO.
            verifyOriginals(saved, record!!, budget, owner)
            val selected = verifySource(library, budget, owner)
            current()
            store.publishAcquisition(plan.id, plan.executionEpoch, scope) { fresh(owner); library.saveNewAcquisition(saved, selected) }
            val proof = verifySaved(library, saved, record!!, budget, owner)
            verifySource(library, budget, owner)
            current()
            store.publishAcquisition(plan.id, plan.executionEpoch, scope) {
                fresh(owner)
                library.confirmAcquisition(saved) { journal.write(record!!.copy(completed = true)) }
            }
            return OrezToolResult.Completed(outputs(requestId, saved, proof))
        } finally { network.client.connectionPool.evictAll() }
    }

    private suspend fun current() {
        currentCoroutineContext().ensureActive()
        check(currentOwner?.privateReleaseProven() != false) { "Private chapter close is unproven. Restart before another acquisition." }
        if (!store.isExecuting(plan.id, plan.executionEpoch)) throw CancellationException("Chapter request was paused, cancelled or replaced.")
    }
    private suspend fun verifySource(library: ChapterLibrary, budget: OrezChapterSourceReadBudget, owner: OrezAcquisitionPrivateOwner): SavedChapter? {
        val next = scope.next ?: return null
        current()
        val selected = requireNotNull(library.findMetadata(next.chapterId)) { "The captured source chapter was removed." }
        require(selected.sourceUrl == next.sourceUrl) { "The selected source URL changed." }
        val proof = owner.inspect(selected, budget)
        require(proof.chapter.sourceFingerprint == next.sourceFingerprint && proof.chapter.pageCount == next.sourcePageCount) { "Saved originals changed after the next-chapter request." }
        sourceProof = proof
        return selected
    }
    private fun saved(record: OrezChapterAcquisitionRecord): SavedChapter {
        require(record.allPagesAcquired) { "Every captured original page is required before Library publication." }
        return SavedChapter(ChapterLibrary.id(scope.targetUrl), record.title, scope.targetUrl, record.pages)
    }
    private suspend fun verifyOriginals(saved: SavedChapter, record: OrezChapterAcquisitionRecord, budget: OrezChapterSourceReadBudget,
        owner: OrezAcquisitionPrivateOwner): Pair<OrezChapterSnapshot, Long> {
        require(record.allPagesAcquired)
        val expected = OrezChapterSourceEvidence.snapshot(saved.id, saved.title, record.pages.map {
            OrezChapterSource(it.index, it.localPath, requireNotNull(it.contentRevision).substringBefore(':'))
        })
        val observed = owner.inspect(saved, budget)
        require(observed.chapter.sourceFingerprint == expected.sourceFingerprint && observed.bytes in 1..OrezNextChapterPolicy.MAX_CHAPTER_BYTES) {
            "Acquired original-byte proof changed or exceeded its limit."
        }
        targetProof = observed
        return observed.chapter to observed.bytes
    }
    private suspend fun verifySaved(library: ChapterLibrary, saved: SavedChapter, record: OrezChapterAcquisitionRecord,
        budget: OrezChapterSourceReadBudget, owner: OrezAcquisitionPrivateOwner): Pair<OrezChapterSnapshot, Long> {
        val persisted = requireNotNull(library.findMetadata(saved.id)) { "Acquisition has no saved Library record." }
        require(persisted.sourceUrl == scope.targetUrl && persisted.pages.sortedBy { it.index }.map { Triple(it.index, it.sourceUrl, it.localPath) } ==
            saved.pages.sortedBy { it.index }.map { Triple(it.index, it.sourceUrl, it.localPath) }) { "Library contains another acquisition or page scope." }
        return verifyOriginals(persisted, record, budget, owner)
    }
    private fun outputs(requestId: String, saved: SavedChapter, proof: Pair<OrezChapterSnapshot, Long>): Map<String, String> = mapOf(
        "requestId" to requestId, "ownerRequestId" to requestId, "chapterId" to saved.id, "title" to saved.title,
        "sourceFingerprint" to proof.first.sourceFingerprint, "pageCount" to proof.first.pageCount.toString(),
        "originalBytes" to proof.second.toString(), "acquisitionScopeFingerprint" to scope.fingerprint,
        "targetUrlSha256" to OrezNextChapterPolicy.sha(scope.targetUrl), "status" to "COMPLETED", "destination" to "library:${saved.id}")
}
