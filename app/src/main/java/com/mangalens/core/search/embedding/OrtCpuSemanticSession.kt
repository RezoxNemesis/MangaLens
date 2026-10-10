package com.mangalens.core.search.embedding

import ai.onnxruntime.*
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

internal interface SemanticCpuSession : AutoCloseable {
    fun embed(input: SemanticTokenizedInput): FloatArray
}
internal fun interface SemanticCpuSessionFactory { fun open(model: ByteArray): SemanticCpuSession }

/** Production default is the actual pinned ORT CPU JNI engine, with no alternate provider. */
internal class OrtCpuSemanticSessionFactory : SemanticCpuSessionFactory {
    override fun open(model: ByteArray): SemanticCpuSession {
        require(model.size == SemanticEmbeddingPin.MODEL_BYTES && SemanticModelArtifactStore.sha256(model) == SemanticEmbeddingPin.MODEL_SHA256)
        val environment = OrtEnvironment.getEnvironment()
        check(environment.version == SemanticEmbeddingPin.RUNTIME_VERSION) { "The installed ONNX runtime does not match the search cache pin." }
        check(OrtEnvironment.getAvailableProviders().contains(OrtProvider.CPU)) { "The CPU embedding provider is unavailable on this ABI." }
        val options = OrtSession.SessionOptions()
        var session: OrtSession? = null
        try {
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.addCPU(false)
            val opened = environment.createSession(model, options)
            session = opened; validate(opened)
            return Session(environment, opened, options)
        } catch (failure: Throwable) {
            if (session != null) try { session.close() } catch (close: Throwable) {
                throw SemanticNativeCloseUnproven(listOf(session, options), close)
            }
            try { options.close() } catch (close: Throwable) { throw SemanticNativeCloseUnproven(listOf(options), close) }
            throw failure
        }
    }
    private fun validate(session: OrtSession) {
        val names = setOf("input_ids", "attention_mask", "token_type_ids")
        require(session.inputNames == names && session.outputNames == setOf("last_hidden_state")) { "The optional model has an unsupported embedding graph." }
        session.inputInfo.values.forEach { node ->
            val info = node.info as? TensorInfo ?: error("The embedding graph requires tensor inputs.")
            require(info.type == OnnxJavaType.INT64 && info.shape.size == 2 && (info.shape[0] < 0 || info.shape[0] == 1L) &&
                (info.shape[1] < 0 || info.shape[1] == SemanticEmbeddingPin.TOKENS.toLong()))
        }
        val output = session.outputInfo.getValue("last_hidden_state").info as? TensorInfo ?: error("The embedding graph requires a float hidden-state output.")
        require(output.type == OnnxJavaType.FLOAT && output.shape.size == 3 && (output.shape[0] < 0 || output.shape[0] == 1L) &&
            (output.shape[1] < 0 || output.shape[1] == SemanticEmbeddingPin.TOKENS.toLong()) && output.shape[2] == SemanticEmbeddingPin.DIMENSIONS.toLong())
    }
    private class Session(val environment: OrtEnvironment, val session: OrtSession, val options: OrtSession.SessionOptions) : SemanticCpuSession {
        private val closed = AtomicBoolean()
        private var unsafe: SemanticNativeCloseUnproven? = null
        override fun embed(input: SemanticTokenizedInput): FloatArray {
            check(!closed.get() && unsafe == null)
            val resources = arrayListOf<AutoCloseable>()
            var answer: FloatArray? = null
            var failure: Throwable? = null
            try {
                val shape = longArrayOf(1, SemanticEmbeddingPin.TOKENS.toLong())
                val ids = OnnxTensor.createTensor(environment, LongBuffer.wrap(input.inputIds), shape).also { resources += it }
                val mask = OnnxTensor.createTensor(environment, LongBuffer.wrap(input.attentionMask), shape).also { resources += it }
                val types = OnnxTensor.createTensor(environment, LongBuffer.wrap(input.tokenTypeIds), shape).also { resources += it }
                val result = session.run(mapOf("input_ids" to ids, "attention_mask" to mask, "token_type_ids" to types)).also { resources += it }
                val output = result.get("last_hidden_state").orElseThrow { IllegalStateException("The embedding graph did not return hidden states.") } as? OnnxTensor
                    ?: error("The embedding output is not a tensor.")
                require(output.info.type == OnnxJavaType.FLOAT && output.info.shape.contentEquals(longArrayOf(1, SemanticEmbeddingPin.TOKENS.toLong(), SemanticEmbeddingPin.DIMENSIONS.toLong())))
                val buffer = requireNotNull(output.floatBuffer)
                require(buffer.remaining() == SemanticEmbeddingPin.TOKENS * SemanticEmbeddingPin.DIMENSIONS)
                val hidden = FloatArray(buffer.remaining()); buffer.get(hidden)
                answer = SemanticEmbeddingMath.maskedMeanNormalized(hidden, input.attentionMask)
            } catch (caught: Throwable) { failure = caught }
            finally {
                // Output is owned by Result. Stop closing on an unproven failure and retain it.
                try { SemanticNativeResources.closeReverse(resources, this) } catch (close: SemanticNativeCloseUnproven) {
                    unsafe = close; failure = close
                }
            }
            failure?.let { throw it }
            return requireNotNull(answer)
        }
        override fun close() {
            unsafe?.let { throw it }
            if (closed.compareAndSet(false, true)) {
                try { session.close() } catch (close: Throwable) {
                    unsafe = SemanticNativeCloseUnproven(listOf(this, session, options), close); throw unsafe!!
                }
                try { options.close() } catch (close: Throwable) {
                    unsafe = SemanticNativeCloseUnproven(listOf(this, options), close); throw unsafe!!
                }
            }
        }
    }
}
