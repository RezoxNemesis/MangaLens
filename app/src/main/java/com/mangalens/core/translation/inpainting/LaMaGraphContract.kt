package com.mangalens.core.translation.inpainting

internal data class LaMaGraphTensor(val type: String, val shape: LongArray)

/** Consumes actual runtime metadata; exporter documentation alone cannot grant compatibility. */
internal object LaMaGraphContract {
    fun validate(inputs: Map<String, LaMaGraphTensor>, outputs: Map<String, LaMaGraphTensor>) {
        require(inputs.keys == setOf("image", "mask") && outputs.keys == setOf("output"))
        fun tensor(value: LaMaGraphTensor, channels: Long) {
            val shape = value.shape
            require(value.type == "FLOAT" && shape.size == 4 && (shape[0] < 0 || shape[0] == 1L) &&
                shape[1] == channels && shape[2] == 512L && shape[3] == 512L) { "The installed repair graph has unsupported tensors." }
        }
        tensor(inputs.getValue("image"), 3); tensor(inputs.getValue("mask"), 1); tensor(outputs.getValue("output"), 3)
    }
}
