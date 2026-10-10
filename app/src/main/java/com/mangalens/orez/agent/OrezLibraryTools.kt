package com.mangalens.orez.agent

import android.content.Context
import android.system.Os
import com.mangalens.core.reader.*
import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Native saved metadata only. Nothing here opens a page, contacts a source or invokes a model. */
internal class OrezLibraryTools(context: Context, private val store: OrezTaskStore, private val plan: OrezTaskPlan) {
    private val files = context.applicationContext.filesDir.canonicalFile
    private val scope = requireNotNull(plan.authorization?.libraryScope).validate()
    suspend fun execute(step: OrezPlanStep, requestId: String): OrezToolResult {
        return try {
            OrezDurablePlanRules.validate(plan)
            require(step.call == plan.steps.single().call && requestId == OrezDurablePlanRules.requestId(plan.id,0))
            OrezLibraryRules.validate(plan)
            val result = OrezLibraryOwnedIo.run(files) { access -> executeOwned(access,requestId) }
            if (scope.request.operation == OrezLibraryOperation.BOOKMARK) NativeLibraryMetadataHints.committed(requireNotNull(scope.selectedChapterId))
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: NativeLibraryMetadataBusyException) { OrezToolResult.Pending("Library metadata is still saving. Retrying this captured request.") }
        catch (failure: Exception) { OrezToolResult.Failed(failure.message?.take(300) ?: "The captured Library metadata is unavailable. Start a new explicit request for changed records.") }
    }
    private suspend fun executeOwned(access: OrezLibraryOwnedIo.Access, requestId: String): OrezToolResult {
        current(); val records = ArrayList<Pair<OrezLibraryMetadataEntry,OrezLibraryOwnedIo.Read>>()
        val revision = scope.inventory.singleOrNull()
        for (captured in scope.inventory) {
            val read = ChapterLibrary.readNativeMetadata { access.readLibrary(File(files,"chapter_library/${captured.chapterId}.json"),2_000_000) }
            val entry = OrezLibraryMetadata.decode(captured.chapterId,read.bytes)
            require(entry.sourceTopologySha256 == captured.sourceTopologySha256) { "The captured saved chapter source metadata changed." }
            val ownedReplay = scope.request.operation == OrezLibraryOperation.BOOKMARK && entry.operation?.let { owned(it,requestId) } == true
            require(read.sha256 == captured.sha256 && read.bytes.size.toLong() == captured.bytes || ownedReplay) {
                "Captured Library metadata changed. Start a new request; this task will not overwrite a later edit."
            }
            records += entry to read
        }
        val series = scope.series?.let { captured ->
            val chapter = access.readMemory(File(files,"reader_memory/chapters/${scope.selectedChapterId}.json"),2_097_152)
            val journal = SeriesMemoryCodec.readChapter(chapter.bytes)
            require(chapter.sha256 == captured.chapterJournalSha256 && journal.chapterId == scope.selectedChapterId && !journal.removed &&
                journal.associationRevision == captured.associationRevision && journal.association?.seriesId == captured.seriesId) {
                "The chapter's linked series or association revision changed. Start a new explicit request."
            }
            val profileRead = access.readMemory(File(files,"reader_memory/series/${captured.seriesId}.json"),1_048_576)
            val profile = SeriesMemoryCodec.readProfile(profileRead.bytes)
            require(profile.id == captured.seriesId && !profile.removed)
            val ownedReplay = scope.request.operation == OrezLibraryOperation.SET_TERM && profile.nativeLibraryOperation?.let { owned(it,requestId) } == true
            require(profileRead.sha256 == captured.profileSha256 || ownedReplay) { "The captured series profile changed; later personal edits are preserved." }
            Triple(chapter,profileRead,profile)
        }
        current(); access.publicationReady()
        when (scope.request.operation) {
            OrezLibraryOperation.BOOKMARK -> {
                val record = records.single(); val desired = requireNotNull(scope.request.bookmarked)
                val value = NativeLibraryOperationReceipt.valueDigest("BOOKMARK",record.first.chapterId,record.first.sourceTopologySha256,desired.toString())
                val previousReceipt = record.first.operation
                if (previousReceipt?.let { owned(it,requestId) } == true) {
                    require(record.first.bookmarked == desired && previousReceipt.valueSha256 == value) { "The earlier persisted bookmark receipt no longer represents the requested value." }
                } else {
                    requireNotNull(revision)
                    val body = JSONObject(record.second.bytes.toString(Charsets.UTF_8)).put("bookmarked",desired)
                    val receipt = receipt(requestId,value,body)
                    body.put("nativeLibraryOperation",NativeLibraryOperationReceiptCodec.encode(receipt))
                    val bytes = body.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= 2_000_000)
                    publish(access,"library",File(files,"chapter_library/${record.first.chapterId}.json"),bytes,records.map { it.second.stamp },requestId)
                    val saved = ChapterLibrary.readNativeMetadata { access.readLibrary(File(files,"chapter_library/${record.first.chapterId}.json"),2_000_000) }
                    val observed = OrezLibraryMetadata.decode(record.first.chapterId,saved.bytes)
                    require(observed.operation == receipt && observed.bookmarked == desired && observed.sourceTopologySha256 == record.first.sourceTopologySha256)
                    records[0] = observed to saved
                }
            }
            OrezLibraryOperation.SET_TERM -> {
                val captured = requireNotNull(scope.series); val bundle = requireNotNull(series); val profile = bundle.third
                val request = scope.request
                val value = NativeLibraryOperationReceipt.valueDigest("SET_TERM",captured.seriesId,request.source,request.preferred,request.target)
                if (profile.nativeLibraryOperation?.let { owned(it,requestId) } == true) {
                    require(profile.nativeLibraryOperation.valueSha256 == value && profile.glossary.count { requestedUserTerm(it) } == 1) { "The persisted user term no longer matches this native receipt." }
                } else {
                    val existing = profile.glossary.filter { it.origin == null && it.originSourceSha256 == null &&
                        memoryTextKey(it.source) == memoryTextKey(request.source) && it.targetLanguage == request.target }
                    require(existing.size <= 1) { "Multiple user terms already use this source and target. Resolve the ambiguity in Series Memory first." }
                    val term = existing.singleOrNull()?.copy(source=request.source,preferred=request.preferred,targetLanguage=request.target,updatedAt=System.currentTimeMillis())
                        ?: SeriesGlossaryTerm(NativeLibraryOperationReceipt.valueDigest("orez-user-term",requestId).take(32),request.source,request.preferred,request.target,
                            MemoryTermKind.CUSTOM,updatedAt=System.currentTimeMillis())
                    val updated = profile.copy(glossary=profile.glossary.filterNot { it.id == term.id } + term,nativeLibraryOperation=null).also { it.validate() }
                    val body = JSONObject(SeriesMemoryCodec.profile(updated).toString(Charsets.UTF_8)); val receipt = receipt(requestId,value,body)
                    val persisted = updated.copy(nativeLibraryOperation=receipt)
                    val bytes = SeriesMemoryCodec.profile(persisted); require(bytes.size <= 1_048_576)
                    val stamps = records.map { it.second.stamp } + listOf(bundle.first.stamp,bundle.second.stamp)
                    publish(access,"profile",File(files,"reader_memory/series/${captured.seriesId}.json"),bytes,stamps,requestId)
                    val saved = access.readMemory(File(files,"reader_memory/series/${captured.seriesId}.json"),1_048_576)
                    val observed = SeriesMemoryCodec.readProfile(saved.bytes)
                    require(observed.nativeLibraryOperation == receipt && observed.glossary.count { requestedUserTerm(it) } == 1)
                    require(bundle.first.stamp.isCurrent()) { "The linked series changed during native readback." }
                    val output = outputs(requestId,records.map { it.first },observed)
                    current(); access.publicationReady()
                    return store.publishLibrary(plan.id,plan.executionEpoch,scope) {
                        ChapterLibrary.publishNativeMetadata { SeriesMemoryStore.publishNativeLibraryMetadata {
                            access.publicationReady(); require(records.all { it.second.stamp.isCurrent() } && bundle.first.stamp.isCurrent() && saved.stamp.isCurrent())
                            OrezToolResult.Completed(output)
                        } }
                    }
                }
            }
            else -> Unit
        }
        val output = outputs(requestId,records.map { it.first },series?.third)
        val stamps = records.map { it.second.stamp } + series?.let { listOf(it.first.stamp,it.second.stamp) }.orEmpty()
        current(); access.publicationReady()
        return store.publishLibrary(plan.id,plan.executionEpoch,scope) {
            ChapterLibrary.publishNativeMetadata {
                val complete = { access.publicationReady(); require(stamps.all { it.isCurrent() }) { "Native metadata changed before its task result was published." }; OrezToolResult.Completed(output) }
                if (series == null) complete() else SeriesMemoryStore.publishNativeLibraryMetadata(complete)
            }
        }
    }
    private fun requestedUserTerm(term: SeriesGlossaryTerm) = term.origin == null && term.originSourceSha256 == null &&
        term.source == scope.request.source && term.preferred == scope.request.preferred && term.targetLanguage == scope.request.target
    private fun owned(receipt: NativeLibraryOperationReceipt,requestId: String) = receipt.requestId == requestId &&
        receipt.scopeIdentity == scope.identity && receipt.operation == scope.request.operation.name
    private fun receipt(id: String,value: String,body: JSONObject) = NativeLibraryOperationReceipt(id,scope.identity,scope.request.operation.name,
        value,NativeLibraryOperationReceipt.stateDigest(body),System.currentTimeMillis()).validate()
    private suspend fun publish(access: OrezLibraryOwnedIo.Access,kind: String,destination: File,bytes: ByteArray,
        stamps: List<OrezLibraryOwnedIo.Stamp>,requestId: String) {
        require(requestId == OrezDurablePlanRules.requestId(plan.id,0))
        val stage = access.prepare(kind,bytes)
        try {
            current(); access.publicationReady()
            store.publishLibrary(plan.id,plan.executionEpoch,scope) {
                ChapterLibrary.publishNativeMetadata {
                    val rename = { access.publicationReady(); require(stamps.all { it.isCurrent() } && stage.stamp.isCurrent()) { "Metadata, linked series or prepared bytes changed before the write." }
                        Os.rename(stage.file.path,destination.path) }
                    if (kind == "profile") SeriesMemoryStore.publishNativeLibraryMetadata(rename) else rename()
                }
            }
        } finally { access.cleanup(stage.file) }
    }
    private suspend fun current() { if (!store.isExecuting(plan.id,plan.executionEpoch)) throw CancellationException("Library request was paused, cancelled or replaced.") }
    private fun outputs(id: String,entries: List<OrezLibraryMetadataEntry>,profile: SeriesMemoryProfile?): Map<String,String> {
        var incomplete = scope.incomplete
        val candidates = when (scope.request.operation) {
            OrezLibraryOperation.SEARCH -> entries.filter { OrezLibraryMetadata.matches(it,scope.request.query) }
            OrezLibraryOperation.RECENT -> entries.filter { it.lastReadAt > 0 }.sortedWith(compareByDescending<OrezLibraryMetadataEntry> { it.lastReadAt }.thenBy { it.chapterId })
            else -> entries
        }
        val rows = candidates.take(scope.request.limit).map { it.summary() }.toMutableList()
        if (rows.size < candidates.size) incomplete = true
        val terms = profile?.glossary.orEmpty().let { if (scope.request.operation == OrezLibraryOperation.SET_TERM) it.filter(::requestedUserTerm) else it }.take(scope.request.limit).map { term -> JSONObject().put("source",term.source).put("preferred",term.preferred)
            .put("targetLanguage",term.targetLanguage).put("origin",if (term.origin == null) "USER_LITERAL" else "SAVED_SOURCE_METADATA") }.toMutableList()
        if (profile != null && terms.size < profile.glossary.size) incomplete = true
        fun payload() = JSONObject().put("trust","APP_SAVED_METADATA").put("chapters",JSONArray(rows)).put("seriesId",profile?.id ?: JSONObject.NULL)
            .put("seriesTitle",profile?.title ?: JSONObject.NULL).put("terms",JSONArray(terms)).put("styleId",profile?.style?.styleId ?: JSONObject.NULL)
            .put("styleTarget",profile?.style?.targetLanguage ?: JSONObject.NULL).put("styleInstructions",profile?.style?.customInstructions ?: JSONObject.NULL)
        while (payload().toString().length > 7_000 && (rows.isNotEmpty() || terms.isNotEmpty())) {
            if (terms.isNotEmpty()) terms.removeAt(terms.lastIndex) else rows.removeAt(rows.lastIndex); incomplete = true
        }
        val body = payload().toString(); require(body.length <= 7_000)
        return mapOf("requestId" to id,"scopeIdentity" to scope.identity,"operation" to scope.request.operation.name,"chapterId" to scope.selectedChapterId.orEmpty(),
            "seriesId" to scope.series?.seriesId.orEmpty(),"associationRevision" to (scope.series?.associationRevision?.toString().orEmpty()),
            "metadata" to body,"incomplete" to incomplete.toString(),"persisted" to scope.request.operation.mutation.toString(),
            "nativeReceipt" to when (scope.request.operation) {
                OrezLibraryOperation.BOOKMARK -> NativeLibraryOperationReceiptCodec.encode(requireNotNull(entries.single().operation)).toString()
                OrezLibraryOperation.SET_TERM -> NativeLibraryOperationReceiptCodec.encode(requireNotNull(profile?.nativeLibraryOperation)).toString()
                else -> ""
            })
    }
}
