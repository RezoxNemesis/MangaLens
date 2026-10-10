package com.mangalens.core.search.embedding

import com.mangalens.core.compute.NativeComputeAdmission
import com.mangalens.core.compute.MemoryPressure
import com.mangalens.core.compute.ResourceGovernorRuntime
import com.mangalens.core.compute.ResourcePressure
import com.mangalens.core.compute.ResourcePausedException
import com.mangalens.core.reader.SavedChapter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class SemanticMetadataHit(val entry: SemanticLibraryMetadataEntry, val score: Float, val tokenCount: Int, val truncated: Boolean)
internal data class SemanticMetadataResult(val query: String, val hits: List<SemanticMetadataHit>, val indexed: Int, val candidates: Int,
    val omitted: Int, val inventoryLimited: Boolean, val truncated: Int, val cacheUnreadable: Boolean, val passLimited: Boolean)
internal data class SemanticSearchState(val busy: Boolean = false, val encodedThisPass: Int = 0, val plannedThisPass: Int = 0,
    val result: SemanticMetadataResult? = null, val error: String? = null)

/** Explicit user actions only. Query changes retire work without scanning or hashing metadata/images. */
internal class SemanticLibrarySearchController(private val scope: CoroutineScope, private val provider: SemanticNativeEmbeddingProvider,
    private val cache: SemanticVectorIndex) {
    private val library = AtomicReference<List<SavedChapter>>(emptyList())
    private val guard = Any()
    private val generation = AtomicLong()
    private val enabled = AtomicBoolean()
    private var active: Job? = null
    private var query = ""
    private val values = MutableStateFlow(SemanticSearchState())
    val state: StateFlow<SemanticSearchState> = values
    fun setLibrary(current: List<SavedChapter>) { if (library.get() !== current) { library.set(current); retire() } }
    fun setQuery(current: String) { if (query != current) { query = current; retire() } }
    fun setActive(current: Boolean) { if (enabled.getAndSet(current) != current && !current) retire() }
    private fun retire() {
        val previous = synchronized(guard) { generation.incrementAndGet(); val previous = active; active = null; values.value = SemanticSearchState(); previous }
        previous?.cancel()
    }
    fun close() { enabled.set(false); retire() }
    fun pause() { retire() }
    fun search() {
        if (!enabled.get() || active != null) return
        val accepted = try { SemanticLibraryMetadata.query(query) } catch (failure: IllegalArgumentException) {
            values.value = values.value.copy(error = failure.message); return
        }
        if (provider.busy.value) { values.value = values.value.copy(error = "The previous native semantic pass is still closing. Retry when it finishes."); return }
        val expected = synchronized(guard) { generation.incrementAndGet() }; val captured = library.get()
        val current = { enabled.get() && generation.get() == expected && library.get() === captured }
        synchronized(guard) { values.value = SemanticSearchState(busy = true) }
        active = scope.launch {
            val caller = currentCoroutineContext()
            fun checkpoint() {
                caller.ensureActive(); if (!current()) throw CancellationException("Library metadata search changed.")
                val pressure = ResourceGovernorRuntime.shared.snapshot()
                if (pressure.signals.memory in setOf(MemoryPressure.LOW, MemoryPressure.CRITICAL) || pressure.pressure == ResourcePressure.CRITICAL)
                    throw ResourcePausedException("Semantic indexing paused for device memory or cooling. Its committed index is retained.")
            }
            try {
                val found = withContext(Dispatchers.IO) {
                    checkpoint()
                    val corpus = SemanticLibraryMetadata.snapshot(captured, ::checkpoint)
                    val loaded = cache.read(::checkpoint)
                    val keys = corpus.entries.map { it.cacheKey }.toSet()
                    val indexed = loaded.entries.filter { it.key in keys }.associateBy { it.key }.toMutableMap()
                    val missing = corpus.entries.filter { it.cacheKey !in indexed }.take(32)
                    val texts = listOf(accepted) + missing.map { it.text }
                    checkpoint()
                    synchronized(guard) { if (current()) values.value = values.value.copy(plannedThisPass = texts.size) }
                    val pass = provider.runPass(texts, NativeComputeAdmission.Priority.INTERACTIVE, current) { done ->
                        synchronized(guard) { if (current()) values.value = values.value.copy(encodedThisPass = done) }
                    }
                    checkpoint()
                    val encodedQuery = pass.encoded.firstOrNull() ?: error("Model loading reached the pass time budget. Retry the explicit search.")
                    pass.encoded.drop(1).zip(missing).forEach { (encoded, entry) ->
                        checkpoint()
                        require(captured.singleOrNull { it.id == entry.id }?.let(entry::matchesCurrent) == true)
                        indexed[entry.cacheKey] = SemanticVectorEntry(entry.id, entry.sourceSha256, entry.textSha256, encoded.vector, encoded.tokenCount,
                            encoded.truncated || entry.bodyTruncated)
                    }
                    checkpoint()
                    if (pass.encoded.size > 1) cache.replace(indexed.values.toList(), ::checkpoint)
                    checkpoint()
                    val hits = corpus.entries.mapNotNull { entry -> indexed[entry.cacheKey]?.let { vector ->
                        SemanticMetadataHit(entry, SemanticEmbeddingMath.cosine(encodedQuery.vector, vector.vector), vector.tokenCount, vector.truncated)
                    } }.sortedWith(compareByDescending<SemanticMetadataHit> { it.score }.thenBy { it.entry.id }).take(16)
                    SemanticMetadataResult(accepted, hits, indexed.size, corpus.entries.size, corpus.omitted, corpus.inventoryLimited,
                        indexed.values.count { it.truncated }, loaded.unreadable, pass.passLimited)
                }
                checkpoint()
                synchronized(guard) { checkpoint(); if (current()) values.value = values.value.copy(busy = false, result = found) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { synchronized(guard) { if (current()) values.value = values.value.copy(busy = false,
                error = "English CPU search could not finish. Check the pinned model or restart if native cleanup requires it. The committed index is retained.") } }
            finally { synchronized(guard) { if (generation.get() == expected) { active = null; values.value = values.value.copy(busy = false) } } }
        }
    }
}
