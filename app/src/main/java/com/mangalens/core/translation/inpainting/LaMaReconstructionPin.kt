package com.mangalens.core.translation.inpainting

/** Publisher-declared immutable pin; availability requires actual local bytes and session checks. */
internal object LaMaReconstructionPin {
    const val MODEL_BYTES = 208_044_816
    const val MODEL_SHA256 = "1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6"
    const val REVISION = "c3c0c9e468934d62e79c329e35d82dd09ff8c444"
    const val MODEL_URL = "https://huggingface.co/Carve/LaMa-ONNX/resolve/$REVISION/lama_fp32.onnx"
    const val RUNTIME_VERSION = "1.20.0"
    const val EDGE = 512
    const val PIXELS = EDGE * EDGE
    const val FLOATS = PIXELS * 3
    const val LICENSE = "Apache-2.0"
    const val PUBLISHER = "Carve/LaMa-ONNX · big-lama conversion"
    const val LICENSE_URL = "https://github.com/Carve-Photos/lama/blob/f5fb39a18022c34a71bf9a47a6ec393c804b49ca/LICENSE"
}
