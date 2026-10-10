package com.mangalens.core.search.embedding

import com.mangalens.core.compute.*
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.*
import com.mangalens.orez.agent.OrezCrossChapterSavedTextHost
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class NativeSemanticHit(val field: NativeSemanticField, val row: NativeIndexedSavedTextRow,
    val score: Float, val tokenCount: Int, val truncated: Boolean)
internal data class NativeSemanticResult(val query: String, val hits: List<NativeSemanticHit>, val indexed: Int,
    val catalogue: NativeSemanticCatalogue, val needsProof: Int, val cacheUnreadable: Boolean, val passLimited: Boolean)
internal data class NativeSemanticState(val busy: Boolean = false, val encoded: Int = 0, val planned: Int = 0,
    val result: NativeSemanticResult? = null, val error: String? = null)

/** Vectors rank hints only; actual source/native/profile/Reader admission owns display and open. */
internal class NativeSavedDialogueSemanticController(private val scope: CoroutineScope,
    private val host: OrezCrossChapterSavedTextHost, private val provider: SemanticNativeEmbeddingProvider,
    private val cache: SemanticVectorIndex) {
    private val library = AtomicReference<List<SavedChapter>>(emptyList())
    private val enabled = AtomicBoolean(); private val generation = AtomicLong(); private val guard = Any()
    private var revision = -1L; private var query = ""; private var job: Job? = null
    private val mutable = MutableStateFlow(NativeSemanticState())
    val state: StateFlow<NativeSemanticState> = mutable
    fun setLibrary(value: List<SavedChapter>) { if (library.get() !== value) { library.set(value); retire() } }
    fun setQuery(value: String) { if (query != value) { query = value; retire() } }
    fun setRevision(value: Long) { if (revision != value) { revision = value; retire() } }
    fun setActive(value: Boolean) { if (enabled.getAndSet(value) != value && !value) retire() }
    fun pause() { retire() }
    fun close() { enabled.set(false); retire() }
    private fun retire() {
        val previous = synchronized(guard) { generation.incrementAndGet(); val previous = job; job = null; mutable.value = NativeSemanticState(); previous }
        previous?.cancel()
    }
    fun search() {
        if (!enabled.get() || job != null) return
        val accepted = try { SemanticLibraryMetadata.query(query) } catch (_: IllegalArgumentException) {
            mutable.value = mutable.value.copy(error = "Enter an English phrase of 1–160 characters."); return
        }
        if (provider.busy.value) { mutable.value = mutable.value.copy(error = "The previous CPU pass is still closing. Retry when it finishes."); return }
        val expected = generation.incrementAndGet(); val captured = library.get()
        fun current() = enabled.get() && generation.get() == expected && library.get() === captured
        mutable.value = NativeSemanticState(busy = true)
        job = scope.launch {
            val caller = currentCoroutineContext()
            fun checkpoint() {
                caller.ensureActive(); if (!current()) throw CancellationException("Saved dialogue search changed.")
                val pressure = ResourceGovernorRuntime.shared.snapshot()
                if (pressure.signals.memory in setOf(MemoryPressure.LOW, MemoryPressure.CRITICAL) || pressure.pressure == ResourcePressure.CRITICAL)
                    throw ResourcePausedException("Saved dialogue search paused for device memory or cooling.")
            }
            try {
                val found = withContext(Dispatchers.IO) {
                    checkpoint()
                    val catalogue = host.semanticCatalogue()
                    val loaded = cache.read(::checkpoint)
                    val keys = catalogue.fields.map { it.key }.toSet()
                    val indexed = loaded.entries.filter { it.key in keys }.associateBy { it.key }.toMutableMap()
                    val prepared = arrayListOf<Pair<NativeSemanticField, NativeIndexedSavedTextRow>>()
                    val chapters = hashSetOf<String>(); var needsProof = 0; var attempted = 0; var proofLimited = false
                    for (field in catalogue.fields) {
                        checkpoint()
                        if (field.key in indexed) continue
                        if (prepared.size == 32) break
                        if (attempted == 64) { proofLimited = true; break }
                        if (field.hint.task.chapterId !in chapters && chapters.size == 4) continue
                        attempted++
                        val row = host.prepareSemanticRow(field)
                        if (row == null || !field.matches(row) || !matchesLibrary(captured, row)) { needsProof++; continue }
                        chapters += row.chapterId; prepared += field to row
                    }
                    checkpoint()
                    val rows = prepared.map { it.second }
                    fun proofCurrent(): Boolean = current() && host.tryDeliverSemanticRows(rows) { true }
                    require(proofCurrent()) { "Saved native source changed before embedding." }
                    synchronized(guard) { if (current()) mutable.value = mutable.value.copy(planned = prepared.size + 1) }
                    val pass = provider.runPass(listOf(accepted) + prepared.map { it.first.input }, NativeComputeAdmission.Priority.INTERACTIVE,
                        ::proofCurrent) { done -> synchronized(guard) { if (current()) mutable.value = mutable.value.copy(encoded = done) } }
                    checkpoint()
                    require(proofCurrent()) { "Saved native source changed during embedding." }
                    val queryVector = pass.encoded.firstOrNull() ?: error("The pass reached its loading budget.")
                    pass.encoded.drop(1).zip(prepared).forEach { (encoded, pair) ->
                        checkpoint()
                        val field = pair.first
                        indexed[field.key] = SemanticVectorEntry(field.hint.task.chapterId, field.sourceSha256, field.textSha256,
                            encoded.vector, encoded.tokenCount, encoded.truncated || field.bodyTruncated)
                    }
                    checkpoint()
                    if (pass.encoded.size > 1) cache.replace(indexed.values.toList()) {
                        checkpoint(); if (!proofCurrent()) throw CancellationException("Saved native source changed before cache publication.")
                    }
                    checkpoint()
                    val ranked = catalogue.fields.mapNotNull { field -> indexed[field.key]?.let { vector ->
                        Triple(field, vector, SemanticEmbeddingMath.cosine(queryVector.vector, vector.vector))
                    } }.sortedWith(compareByDescending<Triple<NativeSemanticField, SemanticVectorEntry, Float>> { it.third }.thenBy { it.first.key })
                    val hits = arrayListOf<NativeSemanticHit>(); val resultChapters = hashSetOf<String>()
                    for ((field, vector, score) in ranked.take(64)) {
                        checkpoint()
                        if (hits.size == 8) break
                        if (field.hint.task.chapterId !in resultChapters && resultChapters.size == 4) continue
                        val row = host.prepareSemanticRow(field)
                        if (row == null || !field.matches(row) || !matchesLibrary(captured, row)) { needsProof++; continue }
                        resultChapters += row.chapterId
                        hits += NativeSemanticHit(field, row, score, vector.tokenCount, vector.truncated)
                    }
                    NativeSemanticResult(accepted, hits.toList(), indexed.size, catalogue, needsProof, loaded.unreadable,
                        pass.passLimited || proofLimited || ranked.size > 64)
                }
                checkpoint()
                synchronized(guard) {
                    checkpoint()
                    if (host.tryDeliverSemanticRows(found.hits.map { it.row }) {
                        if (!current()) false else { mutable.value = mutable.value.copy(busy = false, result = found); true }
                    } != true && current()) mutable.value = mutable.value.copy(busy = false, error = "Saved dialogue changed. Verify sources and search again.")
                }
            } catch (cancelled: CancellationException) {
                synchronized(guard) { if (current() && caller.isActive) mutable.value = mutable.value.copy(busy = false,
                    error = "Saved source proof changed during this pass. Verify saved sources and search again.") }
                throw cancelled
            }
            catch (_: ResourcePausedException) { synchronized(guard) { if (current()) mutable.value = mutable.value.copy(busy = false,
                error = "Saved dialogue search paused for device memory or cooling. Its committed index is retained.") } }
            catch (_: Throwable) { synchronized(guard) { if (current()) mutable.value = mutable.value.copy(busy = false,
                error = "Saved dialogue semantic search could not finish. Check saved sources and the pinned model; restart if native cleanup requires it.") } }
            finally { synchronized(guard) { if (generation.get() == expected) { job = null; mutable.value = mutable.value.copy(busy = false) } } }
        }
    }
    fun open(hit: NativeSemanticHit, accept: (PreparedSavedTextOpen) -> Boolean) {
        if (!enabled.get() || job != null) return
        val found = mutable.value.result ?: return
        if (found.hits.none { it === hit }) return
        val expected = generation.incrementAndGet(); val captured = library.get()
        fun current() = enabled.get() && generation.get() == expected && mutable.value.result === found && library.get() === captured
        mutable.value = mutable.value.copy(busy = true)
        job = scope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    if (!matchesLibrary(captured, hit.row)) null else host.prepareSemanticOpen(hit.row)
                }
                currentCoroutineContext().ensureActive()
                if (current() && (prepared == null || !accept(prepared))) mutable.value = mutable.value.copy(result = null,
                    error = "This exact saved dialogue source changed. Verify sources and search again.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current()) mutable.value = mutable.value.copy(error = "This saved page could not be opened. Search again.") }
            finally { if (generation.get() == expected) { job = null; mutable.value = mutable.value.copy(busy = false) } }
        }
    }
    companion object {
        fun matchesLibrary(library: List<SavedChapter>, row: NativeIndexedSavedTextRow): Boolean = library.singleOrNull { it.id == row.chapterId }?.let { current ->
            current.sourceUrl == row.scope.chapter.sourceUrl && current.pages.map { Triple(it.index, it.sourceUrl, it.localPath) } ==
                row.scope.chapter.pages.map { Triple(it.index, it.sourceUrl, it.localPath) }
        } == true
    }
}
