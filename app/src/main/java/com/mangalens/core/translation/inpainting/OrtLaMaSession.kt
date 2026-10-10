package com.mangalens.core.translation.inpainting

import ai.onnxruntime.*
import com.mangalens.core.compute.NativeComputePrecondition
import com.mangalens.core.compute.ResourceGovernorRuntime
import com.mangalens.core.compute.ResourceWorkKind
import kotlinx.coroutines.*
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal class LaMaNativeCloseUnproven(val retained: List<Any>, val owner: Any, cause: Throwable) :
    IllegalStateException("Repair engine cleanup could not be confirmed. Restart the app before another repair preview.", cause)

internal interface LaMaCpuSession : AutoCloseable { suspend fun repair(input: LaMaTensorInput): FloatArray }
internal fun interface LaMaCpuSessionFactory { suspend fun open(model: ByteArray, checkpoint: () -> Unit): LaMaCpuSession }

/** Real pinned ORT CPU engine is the sole production default. No hosted/fake/alternate engine path. */
internal class OrtLaMaSessionFactory(private val reserveNativeMemory: () -> Unit = {}) : LaMaCpuSessionFactory {
    override suspend fun open(model: ByteArray, checkpoint: () -> Unit): LaMaCpuSession {
        require(model.size == LaMaReconstructionPin.MODEL_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        for (at in model.indices step 65_536) { checkpoint(); digest.update(model, at, minOf(65_536, model.size - at)) }
        require(digest.digest().joinToString("") { "%02x".format(it) } == LaMaReconstructionPin.MODEL_SHA256)
        suspend fun entry() {
            currentCoroutineContext().ensureActive()
            currentCoroutineContext()[NativeComputePrecondition]?.validate(true)
            ResourceGovernorRuntime.shared.requireNativeEntry(ResourceWorkKind.INTERACTIVE)
            reserveNativeMemory(); checkpoint()
        }
        entry()
        val environment = OrtEnvironment.getEnvironment()
        check(environment.version == LaMaReconstructionPin.RUNTIME_VERSION)
        check(OrtEnvironment.getAvailableProviders().contains(OrtProvider.CPU)) { "This runtime has no CPU repair provider." }
        val options = OrtSession.SessionOptions()
        var session: OrtSession? = null
        try {
            options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            options.addCPU(false)
            entry()
            val opened = environment.createSession(model, options); session = opened
            checkpoint(); validate(opened)
            return Session(model, environment, opened, options)
        } catch (failure: Throwable) {
            val opened = session
            if (opened != null) try { opened.close() } catch (close: Throwable) {
                throw LaMaNativeCloseUnproven(listOf(opened, options), model, close)
            }
            try { options.close() } catch (close: Throwable) { throw LaMaNativeCloseUnproven(listOf(options), model, close) }
            throw failure
        }
    }

    private fun validate(session: OrtSession) {
        fun tensor(node: NodeInfo): LaMaGraphTensor {
            val info = node.info as? TensorInfo ?: error("The repair graph requires FP32 tensors.")
            return LaMaGraphTensor(info.type.name, info.shape)
        }
        LaMaGraphContract.validate(session.inputInfo.mapValues { tensor(it.value) }, session.outputInfo.mapValues { tensor(it.value) })
    }

    private class Session(private val pinnedWeights: ByteArray, private val environment: OrtEnvironment,
        private val session: OrtSession, private val options: OrtSession.SessionOptions) : LaMaCpuSession {
        private val closed = AtomicBoolean()
        @Volatile private var unsafe: LaMaNativeCloseUnproven? = null
        private val running = AtomicBoolean()

        override suspend fun repair(input: LaMaTensorInput): FloatArray {
            check(!closed.get() && unsafe == null && running.compareAndSet(false, true))
            var runOptions: OrtSession.RunOptions? = null
            var workerScope: CoroutineScope? = null
            try {
            currentCoroutineContext().ensureActive()
            val requestContext = currentCoroutineContext()
            val actualOptions = OrtSession.RunOptions().also { runOptions = it }
            val actualScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { workerScope = it }
            val actualWorker = actualScope.async {
                // Allocate the fixed ownership slots before any JNI handle can be returned.
                val resources = ArrayList<AutoCloseable>(3)
                var answer: FloatArray? = null; var failure: Throwable? = null
                try {
                    requestContext.ensureActive()
                    requestContext[NativeComputePrecondition]?.validate(false)
                    ResourceGovernorRuntime.shared.requireNativeEntry(ResourceWorkKind.INTERACTIVE)
                    val image = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.image), longArrayOf(1, 3, 512, 512)).also { resources += it }
                    val mask = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.mask), longArrayOf(1, 1, 512, 512)).also { resources += it }
                    requestContext.ensureActive()
                    requestContext[NativeComputePrecondition]?.validate(false)
                    ResourceGovernorRuntime.shared.requireNativeEntry(ResourceWorkKind.INTERACTIVE)
                    val result = session.run(mapOf("image" to image, "mask" to mask), actualOptions).also { resources += it }
                    val output = result.get("output").orElseThrow { IllegalStateException("The repair graph returned no pixels.") } as? OnnxTensor
                        ?: error("The repair output is not a tensor.")
                    require(output.info.type == OnnxJavaType.FLOAT && output.info.shape.contentEquals(longArrayOf(1, 3, 512, 512)))
                    val values = requireNotNull(output.floatBuffer); require(values.remaining() == LaMaReconstructionPin.FLOATS)
                    val copied = FloatArray(values.remaining()); values.get(copied); answer = copied
                } catch (caught: Throwable) { failure = caught }
                finally {
                    try { closeReverse(resources, this@Session) } catch (close: LaMaNativeCloseUnproven) { unsafe = close; failure = close }
                }
                failure?.let { throw it }; requireNotNull(answer)
            }
            return awaitLaMaNativeReturn(actualWorker, { actualOptions.setTerminate(true) }, { unsafe })
            }
            finally {
                // If dispatch itself failed, no worker entered JNI. Otherwise await proved actual return.
                workerScope?.cancel(); running.set(false)
                try { runOptions?.close() } catch (close: Throwable) {
                    val prior = unsafe
                    val problem = LaMaNativeCloseUnproven(listOfNotNull(runOptions) + prior?.retained.orEmpty(),
                        listOfNotNull(this, prior), close); unsafe = problem; throw problem
                }
            }
        }

        override fun close() {
            check(!running.get()) { "The native repair is still running." }
            unsafe?.let { throw it }
            if (closed.compareAndSet(false, true)) {
                try { session.close() } catch (close: Throwable) {
                    val problem = LaMaNativeCloseUnproven(listOf(session, options), this, close); unsafe = problem; throw problem
                }
                try { options.close() } catch (close: Throwable) {
                    val problem = LaMaNativeCloseUnproven(listOf(options), this, close); unsafe = problem; throw problem
                }
            }
        }
    }

    private companion object {
        fun closeReverse(resources: List<AutoCloseable>, owner: Any) {
            for (index in resources.indices.reversed()) try { resources[index].close() } catch (close: Throwable) {
                throw LaMaNativeCloseUnproven(resources.take(index + 1), owner, close)
            }
        }
    }
}
