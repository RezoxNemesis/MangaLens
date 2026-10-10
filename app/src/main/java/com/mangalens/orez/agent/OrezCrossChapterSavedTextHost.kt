package com.mangalens.orez.agent

import android.content.Context
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.translation.*
import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Collections

/** Distinct explicit Library action; never an ambient Orez retrieval or model tool. */
internal class OrezCrossChapterSavedTextHost(context: Context) {
    private val application = context.applicationContext
    private val setupGate = Mutex()
    private val warmingOwner = Any()
    private var prepared: NativeCrossChapterSavedTextSearchService? = null
    @Volatile private var nativeIndexed: NativeIndexedSavedTextSearchService? = null
    @Volatile private var warmer: NativeIndexWarmer? = null
    private val observer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableWarmState = MutableStateFlow(NativeIndexWarmState())
    val warmState: StateFlow<NativeIndexWarmState> = mutableWarmState
    private val mutableNativeRevision = MutableStateFlow(0L)
    val nativeRevision: StateFlow<Long> = mutableNativeRevision
    private suspend fun service() = withContext(Dispatchers.IO) { setupGate.withLock {
        prepared?.let { return@withLock it }
        val library = ChapterLibrary(application)
        val native = ChapterTranslationStore.shared(application)
        val warm = NativeIndexWarmer.shared(native).also { warmer = it }
        warm.refreshInventory()
        observer.launch { warm.state.collect { mutableWarmState.value = it } }
        observer.launch {
            var first = true
            native.states.collect { if (first) first = false else mutableNativeRevision.value++ }
        }
        val memory = SeriesMemoryStore(application.filesDir)
        val nativeIndex = NativeIndexedSavedTextSearchService(native, warm, memory) { receipt ->
            val chapterId = receipt.task.chapterId
            val manifest = File(application.filesDir, "chapter_library/$chapterId.json")
            val before = MemoryReadDeliveryStamp.capture(manifest)
            val chapter = library.findMetadata(chapterId)
            if (chapter == null || before.size == null || !receipt.isCurrent() || !native.nativeIndexTaskCurrent(receipt.task)) null
            else {
                val sources = receipt.files.filter { it.spec.source }
                val paths = Collections.unmodifiableMap(sources.associate { it.spec.pageIndex to it.stamp.path })
                val checked = OrezChapterSourceEvidence.snapshot(chapterId, chapter.title,
                    sources.map { OrezChapterSource(it.spec.pageIndex, it.stamp.path, it.sha256) })
                val stamps = sources.map { source -> MemoryReadDeliveryStamp.capture(File(source.stamp.path)).also {
                    require(it.canonical == source.stamp.path && it.fileKey == source.stamp.key &&
                        it.size == source.stamp.size && it.modified == source.stamp.modified)
                } }
                SavedTextChapterScope(chapter.copy(pages = Collections.unmodifiableList(chapter.pages.toList())),
                    checked.sourceFingerprint, before, paths, File(application.filesDir, "chapters").canonicalFile,
                    sources.sumOf { it.stamp.size }, Collections.unmodifiableList(stamps))
                    .takeIf { it.binds(ReaderTranslationPresentation.receipt(receipt.task)) && before.isCurrent() && receipt.isCurrent() }
            }
        }
        nativeIndexed = nativeIndex
        NativeCrossChapterSavedTextSearchService(native, memory,
            MemoryLexicalHintStore(application.filesDir), inspectChapter = { id, budget ->
                val manifest = File(application.filesDir, "chapter_library/$id.json")
                val before = MemoryReadDeliveryStamp.capture(manifest)
                val chapter = library.find(id)
                if (chapter == null || chapter.pages.size !in 1..NativeCrossChapterSavedTextSearchService.MAX_PAGES || before.size == null) null
                else SavedTextChapterScope.inspect(chapter, File(application.filesDir, "chapters"), manifest, sourceReadBudget = budget)
                    ?.takeIf { it.manifest == before && before.isCurrent() }
            }, nativeIndex = nativeIndex).also { prepared = it }
    } }

    suspend fun initialize() { service() }
    suspend fun search(query: String, refresh: Boolean, source: SavedTextSearchSource = SavedTextSearchSource.ALL): CrossChapterSavedTextSnapshot? =
        service().search(query, forceRefresh = refresh, source = source)
    suspend fun prepareOpen(snapshot: CrossChapterSavedTextSnapshot, rowId: String): PreparedSavedTextOpen? = service().prepareOpen(snapshot, rowId)
    suspend fun semanticCatalogue(): com.mangalens.core.search.embedding.NativeSemanticCatalogue = withContext(Dispatchers.IO) {
        service()
        val native = ChapterTranslationStore.shared(application)
        val caller = currentCoroutineContext()
        com.mangalens.core.search.embedding.NativeSavedDialogueSemanticCorpus.snapshot(native.nativeIndexTasks(), native::memoryConfigurationIdentity) { caller.ensureActive() }
    }
    suspend fun prepareSemanticRow(field: com.mangalens.core.search.embedding.NativeSemanticField): NativeIndexedSavedTextRow? {
        service(); return nativeIndexed?.prepareHint(field.hint)
    }
    fun tryDeliverSemanticRows(rows: List<NativeIndexedSavedTextRow>, accept: () -> Boolean): Boolean =
        nativeIndexed?.tryDeliverRows(rows, accept) == true
    suspend fun prepareSemanticOpen(row: NativeIndexedSavedTextRow): PreparedSavedTextOpen? {
        service(); return nativeIndexed?.prepareOpen(row)
    }
    suspend fun startWarming(current: () -> Boolean = { true }) {
        service(); currentCoroutineContext().ensureActive(); if (current()) warmer?.start(warmingOwner)
    }
    fun pauseWarming() { warmer?.pause(warmingOwner) }
    fun close() { pauseWarming(); observer.cancel() }
}
