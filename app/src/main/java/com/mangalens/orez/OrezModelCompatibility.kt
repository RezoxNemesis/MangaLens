package com.mangalens.orez

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal class OrezModelCompatibilityException(message: String) : IllegalStateException(message)

internal data class OrezGgufMetadata(
    val version: Int,
    val architecture: String,
    val contextTokens: Long,
    val fileType: Int,
    val tensorCount: Long
)

/** Matches the pinned CPU runtime, which caps contexts at 8192 tokens. */
internal data class OrezModelRequirements(
    val architecture: String = "qwen2",
    val minimumContextTokens: Long = OrezModelCatalog.RUNTIME_CONTEXT_TOKENS.toLong(),
    val fileTypes: Set<Int> = setOf(15)
)

/** Bounded structural preflight. SHA-256 verification remains mandatory for activation. */
internal object OrezModelCompatibility {
    const val VERIFIER_VERSION = 1
    private const val MAX_HEADER_BYTES = 32L * 1024L * 1024L
    private const val MAX_STRING_BYTES = 1L * 1024L * 1024L
    private const val MAX_ARRAY_ITEMS = 1_000_000L
    private val scalarSizes = mapOf(0 to 1, 1 to 1, 2 to 2, 3 to 2, 4 to 4, 5 to 4, 6 to 4, 7 to 1, 10 to 8, 11 to 8, 12 to 8)
    // GGML type -> elements per block / bytes per block. These CPU types exist in the pinned runtime.
    private val tensorTypes = mapOf(
        0 to (1L to 4L), 1 to (1L to 2L), 2 to (32L to 18L), 3 to (32L to 20L),
        6 to (32L to 22L), 7 to (32L to 24L), 8 to (32L to 34L), 9 to (32L to 40L),
        10 to (256L to 84L), 11 to (256L to 110L), 12 to (256L to 144L),
        13 to (256L to 176L), 14 to (256L to 210L), 15 to (256L to 292L)
    )

    fun inspect(file: File, requirements: OrezModelRequirements = OrezModelRequirements()): OrezGgufMetadata {
        fun reject(message: String): Nothing = throw OrezModelCompatibilityException(message)
        if (!file.isFile || file.length() < 24L) reject("Invalid GGUF model: the file is missing or truncated.")
        try {
            DataInputStream(BufferedInputStream(file.inputStream(), 64 * 1024)).use { input ->
                val reader = HeaderReader(input, file.length())
                if (reader.uint(4) != 0x46554747L) reject("Invalid GGUF model header. Keep the working model and download this pack again.")
                val version = reader.uint(4).toInt()
                if (version !in 2..3) reject("Unsupported GGUF version $version. This runtime supports versions 2 and 3.")
                val tensors = reader.count(16_384L, "tensor count")
                val entries = reader.count(1_024L, "metadata count")
                if (tensors == 0L || entries == 0L) reject("Invalid GGUF model: missing tensors or metadata.")
                var architecture: String? = null
                var context: Long? = null
                var fileType: Int? = null
                var tokenizer: String? = null
                var modelType: String? = null
                var alignment = 32L
                val keys = hashSetOf<String>()
                repeat(entries.toInt()) {
                    val key = reader.string(4_096L)
                    if (!keys.add(key)) reject("Invalid GGUF metadata: duplicate key $key.")
                    val type = reader.uint(4).toInt()
                    when (key) {
                        "general.architecture" -> architecture = reader.textValue(type, key)
                        "${requirements.architecture}.context_length" -> context = reader.integerValue(type, key)
                        "general.file_type" -> fileType = reader.integerValue(type, key).takeIf { it <= Int.MAX_VALUE }?.toInt()
                        "general.alignment" -> alignment = reader.integerValue(type, key)
                        "general.type" -> modelType = reader.textValue(type, key)
                        "tokenizer.ggml.model" -> tokenizer = reader.textValue(type, key)
                        else -> reader.skipValue(type)
                    }
                }
                if (architecture != requirements.architecture) reject("Incompatible model architecture: expected ${requirements.architecture}, found ${architecture ?: "missing"}.")
                if (context == null || context!! < requirements.minimumContextTokens) reject("Incompatible model context: this runtime needs at least ${requirements.minimumContextTokens} tokens.")
                if (fileType !in requirements.fileTypes) reject("Incompatible model quantization: expected ${requirements.fileTypes}, found ${fileType ?: "missing"}.")
                if (tokenizer != "gpt2") reject("Incompatible Qwen tokenizer metadata.")
                if (modelType != null && modelType != "model") reject("This GGUF is an adapter, not a complete model.")
                if (alignment !in 1L..4_096L || alignment and (alignment - 1) != 0L) reject("Invalid GGUF tensor alignment.")

                val ranges = ArrayList<Pair<Long, Long>>(tensors.toInt())
                val names = hashSetOf<String>()
                repeat(tensors.toInt()) {
                    val name = reader.string(4_096L)
                    if (!names.add(name)) reject("Invalid GGUF tensor: duplicate name.")
                    val dimensions = reader.uint(4)
                    if (dimensions !in 1L..4L) reject("Invalid GGUF tensor dimensions.")
                    var elements = 1L
                    var rowElements = 0L
                    repeat(dimensions.toInt()) { dimension ->
                        val length = reader.count(Long.MAX_VALUE, "tensor dimension")
                        if (length == 0L || elements > Long.MAX_VALUE / length) reject("Invalid GGUF tensor dimensions overflow.")
                        if (dimension == 0) rowElements = length
                        elements *= length
                    }
                    val tensorType = reader.uint(4).toInt()
                    val block = tensorTypes[tensorType] ?: reject("Unsupported GGUF tensor type $tensorType.")
                    if (rowElements % block.first != 0L || elements / block.first > Long.MAX_VALUE / block.second) reject("Invalid quantized GGUF tensor dimensions.")
                    val size = (elements / block.first) * block.second
                    val offset = reader.count(Long.MAX_VALUE, "tensor offset")
                    if (offset % alignment != 0L || offset > Long.MAX_VALUE - size) reject("Invalid GGUF tensor offset.")
                    ranges += offset to offset + size
                }
                val dataStart = (reader.position + alignment - 1) / alignment * alignment
                val available = file.length() - dataStart
                var previousEnd = 0L
                for ((start, end) in ranges.sortedBy { it.first }) {
                    if (start < previousEnd || end > available) reject("Invalid GGUF tensor extent: weights are overlapping or truncated.")
                    previousEnd = end
                }
                return OrezGgufMetadata(version, architecture!!, context!!, fileType!!, tensors)
            }
        } catch (failure: IOException) {
            throw OrezModelCompatibilityException("Invalid GGUF metadata or tensor directory: ${failure.message ?: "truncated file"}.")
        }
    }

    private class HeaderReader(private val input: DataInputStream, private val fileBytes: Long) {
        var position: Long = 0L
            private set

        private fun take(bytes: Long) {
            if (bytes < 0L || bytes > minOf(fileBytes, MAX_HEADER_BYTES) - position) {
                throw OrezModelCompatibilityException("Invalid GGUF metadata: header length exceeds the bounded file limits.")
            }
            position += bytes
        }

        fun uint(bytes: Int): Long {
            take(bytes.toLong())
            return when (bytes) {
                1 -> input.readUnsignedByte().toLong()
                2 -> java.lang.Short.reverseBytes(input.readShort()).toLong() and 0xffffL
                4 -> Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                8 -> java.lang.Long.reverseBytes(input.readLong()).also {
                    if (it < 0L) throw OrezModelCompatibilityException("Invalid GGUF metadata: unsigned value exceeds supported limits.")
                }
                else -> error("Invalid integer width")
            }
        }

        fun count(maximum: Long, description: String): Long = uint(8).also {
            if (it > maximum) throw OrezModelCompatibilityException("Invalid GGUF metadata: $description exceeds supported limits.")
        }

        fun string(maximum: Long): String {
            val length = count(maximum, "string length")
            take(length)
            val bytes = ByteArray(length.toInt())
            input.readFully(bytes)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }

        fun textValue(type: Int, key: String): String {
            if (type != 8) throw OrezModelCompatibilityException("Invalid GGUF metadata type for $key.")
            return string(4_096L)
        }

        fun integerValue(type: Int, key: String): Long = when (type) {
            0 -> uint(1)
            2 -> uint(2)
            4 -> uint(4)
            10 -> uint(8)
            else -> throw OrezModelCompatibilityException("Invalid GGUF metadata integer type for $key.")
        }

        private fun skip(bytes: Long) {
            take(bytes)
            var remaining = bytes
            while (remaining > 0L) {
                val skipped = input.skip(remaining)
                if (skipped > 0L) remaining -= skipped
                else { input.readByte(); remaining-- }
            }
        }

        fun skipValue(type: Int) {
            when (type) {
                8 -> skip(count(MAX_STRING_BYTES, "string length"))
                9 -> {
                    val elementType = uint(4).toInt()
                    val items = count(MAX_ARRAY_ITEMS, "array length")
                    if (elementType == 8) repeat(items.toInt()) { skip(count(MAX_STRING_BYTES, "array string length")) }
                    else {
                        val size = scalarSizes[elementType] ?: throw OrezModelCompatibilityException("Invalid GGUF metadata array type $elementType.")
                        skip(items * size)
                    }
                }
                else -> skip((scalarSizes[type] ?: throw OrezModelCompatibilityException("Invalid GGUF metadata type $type.")).toLong())
            }
        }
    }
}

internal object OrezModelRuntimePolicy {
    private const val CONTEXT_HEADROOM_BYTES = 224L * 1024L * 1024L

    fun platformIssue(api: Int, abis: List<String>, nativeAvailable: Boolean): String? = when {
        api < 26 -> "Local inference needs Android 8.0 or later. Existing model files were kept."
        abis.none { it == "arm64-v8a" || it == "x86_64" } -> "This device ABI is not supported by the local runtime. Existing model files were kept."
        !nativeAvailable -> "The local native runtime is unavailable. Reinstall the app; model files were kept."
        else -> null
    }

    fun memoryIssue(bytes: Long, available: Long, lowMemory: Boolean, alreadyLoaded: Boolean): String? {
        val modelBytes = if (alreadyLoaded) 0L else bytes.coerceAtLeast(0L)
        val required = if (modelBytes > Long.MAX_VALUE - CONTEXT_HEADROOM_BYTES) Long.MAX_VALUE else modelBytes + CONTEXT_HEADROOM_BYTES
        return if (lowMemory || available < required) {
            "Local inference is paused for low memory. Close other apps and retry, or select Lite. The installed model was kept."
        } else null
    }
}
