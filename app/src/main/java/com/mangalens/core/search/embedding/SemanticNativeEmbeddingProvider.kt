package com.mangalens.core.search.embedding

import android.app.ActivityManager
import android.content.Context
import com.mangalens.core.compute.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class SemanticEncodedText(val vector: FloatArray, val tokenCount: Int, val truncated: Boolean)
internal data class SemanticNativePass(val encoded: List<SemanticEncodedText>, val passLimited: Boolean)

/** One retained producer owns admission, exact model bytes and one CPU session until real close. */
internal class SemanticNativeEmbeddingProvider internal constructor(private val context: Context,
    private val store: SemanticModelArtifactStore, private val factory: SemanticCpuSessionFactory = OrtCpuSemanticSessionFactory()) {
    private val owned = AtomicBoolean()
    private val busyValue = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyValue
    private val restartValue = MutableStateFlow(false)
    val restartRequired: StateFlow<Boolean> = restartValue
    private var quarantined: List<Any>? = null

    suspend fun runPass(texts: List<String>, priority: NativeComputeAdmission.Priority, current: () -> Boolean,
        onCompleted: (Int) -> Unit = {}): SemanticNativePass {
        require(texts.size in 1..33 && texts.all { it.length <= SemanticEmbeddingPin.MAX_INPUT_CHARS })
        check(owned.compareAndSet(false, true)) { "The previous semantic pass is still closing its native session. Try again when it finishes." }
        busyValue.value = true
        val capturedPrecondition = currentCoroutineContext()[NativeComputePrecondition]
        return suspendCancellableCoroutine { continuation ->
            val requester = SemanticNativeRequester(continuation, current, onCompleted, capturedPrecondition)
            continuation.invokeOnCancellation { requester.retire() }
            launchProducer(requester, texts.toList(), priority)
        }
    }
    private fun launchProducer(requester: SemanticNativeRequester, texts: List<String>, priority: NativeComputeAdmission.Priority) {
        cleanupScope.launch {
            var lease: NativeComputeAdmission.Lease? = null
            var session: SemanticCpuSession? = null
            var result: SemanticNativePass? = null
            var failure: Throwable? = null
            var unproven: SemanticNativeCloseUnproven? = null
            try {
                fun checkpoint() { if (!requester.current()) throw CancellationException("The semantic search request was retired."); requireMemory(needsSession = session == null) }
                val granted = NativeComputeAdmission.shared.acquire(priority) { requester.current() }
                    ?: throw CancellationException("The semantic request retired while awaiting native admission.")
                lease = granted
                checkpoint(); requester.validate(granted.waited)
                val kind = if (priority == NativeComputeAdmission.Priority.BACKGROUND) ResourceWorkKind.BACKGROUND else ResourceWorkKind.INTERACTIVE
                ResourceGovernorRuntime.shared.requireNativeEntry(kind)
                val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
                val tokenizer = readTokenizer()
                val model = store.readVerifiedModel(::checkpoint) { requireMemory(needsWeightsArray = true, needsSession = true) }
                checkpoint(); requester.validate(false); ResourceGovernorRuntime.shared.requireNativeEntry(kind)
                val opened = factory.open(model); session = opened
                checkpoint()
                val encoded = arrayListOf<SemanticEncodedText>()
                for (text in texts) {
                    checkpoint()
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) break
                    ResourceGovernorRuntime.shared.requireNativeEntry(kind)
                    val input = tokenizer.encode(text)
                    checkpoint()
                    requester.validate(false)
                    val vector = opened.embed(input)
                    checkpoint(); SemanticEmbeddingMath.validate(vector)
                    encoded += SemanticEncodedText(vector, input.tokenCount, input.truncated)
                    requester.progress(encoded.size)
                }
                checkpoint()
                result = SemanticNativePass(encoded.toList(), encoded.size < texts.size)
            } catch (caught: Throwable) {
                failure = caught
                if (caught is SemanticNativeCloseUnproven) unproven = caught
            } finally {
                if (unproven == null) try { session?.close() } catch (close: Throwable) {
                    unproven = if (close is SemanticNativeCloseUnproven) close else SemanticNativeCloseUnproven(listOfNotNull(session), close)
                    failure = unproven
                }
                if (unproven != null) {
                    quarantined = unproven!!.retained + listOfNotNull(session, lease)
                    restartValue.value = true
                    // Do not release either capacity claim when native close was not established.
                } else {
                    lease?.close(); owned.set(false); busyValue.value = false
                }
            }
            requester.finish(result, failure)
        }
    }
    private fun readTokenizer(): SemanticWordPieceTokenizer {
        val bytes = context.assets.open("semantic/vocab.txt").use { input ->
            val output = java.io.ByteArrayOutputStream(SemanticEmbeddingPin.VOCAB_BYTES); val buffer = ByteArray(8192)
            while (output.size() <= SemanticEmbeddingPin.VOCAB_BYTES) {
                val actual = input.read(buffer, 0, minOf(buffer.size, SemanticEmbeddingPin.VOCAB_BYTES + 1 - output.size()))
                if (actual < 0) break
                if (actual == 0) error("The tokenizer asset could not be read.")
                output.write(buffer, 0, actual)
            }
            output.toByteArray()
        }
        require(bytes.size == SemanticEmbeddingPin.VOCAB_BYTES && SemanticModelArtifactStore.sha256(bytes) == SemanticEmbeddingPin.VOCAB_SHA256)
        return SemanticWordPieceTokenizer(bytes.toString(Charsets.UTF_8).split('\n').dropLastWhile { it.isEmpty() })
    }
    private fun requireMemory(needsWeightsArray: Boolean = false, needsSession: Boolean = false) {
        val pressure = ResourceGovernorRuntime.shared.snapshot()
        if (pressure.signals.memory in setOf(MemoryPressure.LOW, MemoryPressure.CRITICAL) || pressure.pressure == ResourcePressure.CRITICAL)
            throw ResourcePausedException("Semantic search is waiting for device memory or cooling. The optional index is retained.")
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo(); manager.getMemoryInfo(info)
        val estimatedReserve = (if (needsWeightsArray) SemanticEmbeddingPin.MODEL_BYTES.toLong() else 0L) +
            (if (needsSession) 192L else 32L) * 1024 * 1024
        val estimatedJavaReserve = (if (needsWeightsArray) SemanticEmbeddingPin.MODEL_BYTES.toLong() else 0L) + 32L * 1024 * 1024
        if (info.lowMemory || info.availMem < estimatedReserve + info.threshold || Runtime.getRuntime().maxMemory() -
            (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) < estimatedJavaReserve)
            throw ResourcePausedException("English semantic search needs its 90.4 MB weight array and estimated native memory headroom. Close other work and retry.")
    }
    companion object {
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val instances = ConcurrentHashMap<String, SemanticNativeEmbeddingProvider>()
        fun shared(context: Context, store: SemanticModelArtifactStore): SemanticNativeEmbeddingProvider = instances.computeIfAbsent(context.applicationContext.filesDir.canonicalPath) {
            SemanticNativeEmbeddingProvider(context.applicationContext, store)
        }
    }
}
