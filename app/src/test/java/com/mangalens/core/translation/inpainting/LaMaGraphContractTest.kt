package com.mangalens.core.translation.inpainting

import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN metadata controls; synthetic metadata does not prove the installed graph or ABI. */
class LaMaGraphContractTest {
    private fun tensor(channels: Long, batch: Long = 1) = LaMaGraphTensor("FLOAT", longArrayOf(batch, channels, 512, 512))
    private val inputs get() = mapOf("image" to tensor(3), "mask" to tensor(1))
    private val outputs get() = mapOf("output" to tensor(3))
    @Test fun exactExporterContractSupportsBatchOneAndDynamicBatchOnly() {
        LaMaGraphContract.validate(inputs, outputs)
        LaMaGraphContract.validate(mapOf("image" to tensor(3, -1), "mask" to tensor(1, -1)), mapOf("output" to tensor(3, -1)))
    }
    @Test fun wrongNamesOrAdditionalInputsAndOutputsCannotGrantRuntimeCompatibility() {
        assertThrows(IllegalArgumentException::class.java) { LaMaGraphContract.validate(inputs + ("hint" to tensor(3)), outputs) }
        assertThrows(IllegalArgumentException::class.java) { LaMaGraphContract.validate(inputs, mapOf("pixels" to tensor(3))) }
    }
    @Test fun wrongChannelsRankEdgeAndFixedBatchFailClosed() {
        for (invalid in listOf(tensor(4), tensor(3, 2), tensor(3).copy(shape = longArrayOf(1, 3, 512, 511)), tensor(3).copy(shape = longArrayOf(3, 512, 512))))
            assertThrows(IllegalArgumentException::class.java) { LaMaGraphContract.validate(inputs + ("image" to invalid), outputs) }
    }
    @Test fun halfPrecisionAndNonTensorTypeAreNotInterpretedAsFloatPixels() {
        assertThrows(IllegalArgumentException::class.java) { LaMaGraphContract.validate(inputs, mapOf("output" to tensor(3).copy(type = "FLOAT16"))) }
    }
}
