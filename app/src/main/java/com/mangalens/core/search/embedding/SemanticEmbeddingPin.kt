package com.mangalens.core.search.embedding

/** Fixed optional artifact; neither a descriptor nor downloaded bytes establish native readiness. */
internal object SemanticEmbeddingPin {
    const val MODEL_ID = "all-MiniLM-L6-v2"
    const val REPOSITORY = "sentence-transformers/all-MiniLM-L6-v2"
    const val REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
    const val MODEL_BYTES = 90_405_214
    const val MODEL_SHA256 = "6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452"
    const val VOCAB_BYTES = 231_508
    const val VOCAB_SHA256 = "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3"
    const val RUNTIME_VERSION = "1.20.0"
    const val RUNTIME_AAR_SHA256 = "07a8f71ef890afed8c6087a56220e6d558a492804276ee2dd7cb7f6262242027"
    const val DIMENSIONS = 384
    const val TOKENS = 128
    const val VOCAB_ROWS = 30_522
    const val MAX_INPUT_CHARS = 4_096
    const val MODEL_URL = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/1110a243fdf4706b3f48f1d95db1a4f5529b4d41/onnx/model.onnx"
    const val LICENSE_ASSET = "orez/licenses/Qwen-GGUF-Apache-2.0.txt"
    const val INDEX_REVISION = "english-library-metadata-v1"
    val cachePin: String get() = "$INDEX_REVISION:$MODEL_SHA256:$VOCAB_SHA256:$RUNTIME_VERSION:$RUNTIME_AAR_SHA256"
}
