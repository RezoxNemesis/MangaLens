package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class OrezModelCompatibilityTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun readsQwenArchitectureContextAndTensorDataWithoutLoadingWeights() {
        val file = GgufFixture.write(folder.root)
        val metadata = OrezModelCompatibility.inspect(file)
        assertEquals(3, metadata.version)
        assertEquals("qwen2", metadata.architecture)
        assertEquals(32_768L, metadata.contextTokens)
        assertEquals(15, metadata.fileType)
        assertEquals(1L, metadata.tensorCount)
    }

    @Test fun rejectsSameSizeNonGgufInsteadOfAdvertisingItAsInstalled() {
        val file = GgufFixture.write(folder.root)
        file.writeBytes(ByteArray(file.length().toInt()) { 0x41 })
        assertRejected("GGUF") { OrezModelCompatibility.inspect(file) }
    }

    @Test fun refusesUnsupportedArchitectureAndInsufficientContext() {
        assertRejected("architecture") {
            OrezModelCompatibility.inspect(GgufFixture.write(folder.root, "wrong.gguf", architecture = "llama"))
        }
        assertRejected("context") {
            OrezModelCompatibility.inspect(GgufFixture.write(folder.root, "short.gguf", context = 2_048L))
        }
    }

    @Test fun refusesWrongQuantizationAndInvalidTensorExtent() {
        assertRejected("quantization") {
            OrezModelCompatibility.inspect(GgufFixture.write(folder.root, "wrong-q.gguf", fileType = 18))
        }
        assertRejected("tensor") {
            OrezModelCompatibility.inspect(GgufFixture.write(folder.root, "outside.gguf", tensorOffset = 32_768L))
        }
    }

    @Test fun boundsMalformedMetadataBeforeAllocationOrLongLoops() {
        val file = GgufFixture.write(folder.root)
        java.io.RandomAccessFile(file, "rw").use { input ->
            input.seek(24)
            input.writeLong(java.lang.Long.reverseBytes(Long.MAX_VALUE))
        }
        assertRejected("metadata") { OrezModelCompatibility.inspect(file) }
    }

    @Test fun refusesUnsupportedRuntimeAndPausesForMemoryPressure() {
        assertTrue(OrezModelRuntimePolicy.platformIssue(25, listOf("arm64-v8a"), true)!!.contains("Android"))
        assertTrue(OrezModelRuntimePolicy.platformIssue(35, listOf("armeabi-v7a"), true)!!.contains("ABI"))
        assertTrue(OrezModelRuntimePolicy.platformIssue(35, listOf("x86_64"), false)!!.contains("runtime"))
        assertNull(OrezModelRuntimePolicy.platformIssue(28, listOf("x86_64"), true))
        assertNotNull(OrezModelRuntimePolicy.memoryIssue(500L, 600L, false, false))
        assertNotNull(OrezModelRuntimePolicy.memoryIssue(1L, Long.MAX_VALUE, true, true))
        assertNull(OrezModelRuntimePolicy.memoryIssue(500_000_000L, 300_000_000L, false, true))
        assertNotNull(OrezModelRuntimePolicy.memoryIssue(500_000_000L, 300_000_000L, false, false))
    }

    private fun assertRejected(fragment: String, block: () -> Unit) {
        try { block(); fail("Expected incompatible model: $fragment") }
        catch (failure: OrezModelCompatibilityException) {
            assertTrue(failure.message, failure.message!!.contains(fragment, true))
        }
    }
}

internal object GgufFixture {
    fun write(
        directory: File,
        name: String = "fixture.gguf",
        architecture: String = "qwen2",
        context: Long = 32_768L,
        fileType: Int = 15,
        tensorOffset: Long = 0L,
        marker: Int = 17
    ): File {
        val bytes = ByteArrayOutputStream()
        fun number(value: Long, count: Int) {
            repeat(count) { i -> bytes.write(((value ushr (8 * i)) and 255).toInt()) }
        }
        fun string(value: String) {
            val encoded = value.toByteArray(Charsets.UTF_8)
            number(encoded.size.toLong(), 8); bytes.write(encoded)
        }
        fun text(key: String, value: String) {
            string(key); number(8, 4); string(value)
        }
        fun scalar(key: String, value: Long) {
            string(key); number(4, 4); number(value, 4)
        }
        bytes.write("GGUF".toByteArray())
        number(3, 4); number(1, 8); number(4, 8)
        text("general.architecture", architecture)
        scalar("qwen2.context_length", context)
        scalar("general.file_type", fileType.toLong())
        text("tokenizer.ggml.model", "gpt2")
        string("token_embd.weight"); number(2, 4)
        number(32, 8); number(2, 8); number(0, 4); number(tensorOffset, 8)
        while (bytes.size() % 32 != 0) bytes.write(0)
        bytes.write(ByteArray(256) { marker.toByte() })
        return File(directory, name).apply { writeBytes(bytes.toByteArray()) }
    }

    fun descriptor(file: File, id: String = "fixture-${file.name}"): OrezModelDescriptor = OrezModelCatalog.lite.copy(
        id = id, fileName = file.name, bytes = file.length(), sha256 = sha256(file)
    )

    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
