package com.mangalens.download

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

class PackagedOriginalMediaAdmissionTest {
    @get:Rule val temp = TemporaryFolder()
    private val tools = listOf("libmangalens_ffmpeg.so", "libmangalens_ffprobe.so")
    private val needed = listOf("libc.so", "libm.so")
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** A synthetic ELF64 Android PIE descriptor, not executable native code. */
    private fun elf(machine: Int = 62, dependencies: List<String> = needed, runpath: Boolean = false): ByteArray {
        val bytes = ByteArray(1024)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0] = 0x7f; bytes[1] = 'E'.code.toByte(); bytes[2] = 'L'.code.toByte(); bytes[3] = 'F'.code.toByte()
        bytes[4] = 2; bytes[5] = 1; bytes[6] = 1
        b.putShort(16, 3); b.putShort(18, machine.toShort()); b.putInt(20, 1)
        b.putLong(24, 256); b.putLong(32, 64); b.putShort(52, 64); b.putShort(54, 56); b.putShort(56, 3)
        fun segment(index: Int, type: Int, offset: Long, size: Long) {
            val pos = 64 + index * 56
            b.putInt(pos, type); b.putInt(pos + 4, 5); b.putLong(pos + 8, offset)
            b.putLong(pos + 16, offset); b.putLong(pos + 32, size); b.putLong(pos + 40, size); b.putLong(pos + 48, 4096)
        }
        val interpreter = "/system/bin/linker64\u0000".toByteArray()
        interpreter.copyInto(bytes, 256)
        segment(0, 1, 0, bytes.size.toLong())
        segment(1, 3, 256, interpreter.size.toLong())
        val strings = ByteArrayOutput().apply {
            append(0)
            for (dependency in dependencies) { append(dependency.toByteArray()); append(0) }
        }.bytes()
        strings.copyInto(bytes, 512)
        var pos = 320
        fun dynamic(type: Long, value: Long) { b.putLong(pos, type); b.putLong(pos + 8, value); pos += 16 }
        dynamic(5, 512); dynamic(10, strings.size.toLong())
        var nameOffset = 1L
        for (dependency in dependencies) { dynamic(1, nameOffset); nameOffset += dependency.length + 1 }
        if (runpath) dynamic(29, 1)
        dynamic(0, 0)
        segment(2, 2, 320, (pos - 320).toLong())
        return bytes
    }
    private class ByteArrayOutput {
        private val out = java.io.ByteArrayOutputStream()
        fun append(value: Int) { out.write(value) }
        fun append(value: ByteArray) { out.write(value) }
        fun bytes() = out.toByteArray()
    }
    private fun manifest(actual: Map<String, ByteArray> = tools.associateWith { elf() }): JSONObject {
        val rows = JSONArray()
        for ((abi, machine) in listOf("arm64-v8a" to 183, "x86_64" to 62)) {
            val binaries = JSONObject()
            for (name in tools) {
                val data = if (machine == 62) actual.getValue(name) else elf(machine)
                binaries.put(name, JSONObject().put("bytes", data.size).put("sha256", hash(data)).put("systemNeeded", JSONArray(needed)))
            }
            rows.put(JSONObject().put("abi", abi).put("buildReceiptSha256", hash("test receipt $abi".toByteArray())).put("binaries", binaries))
        }
        return JSONObject().put("schemaVersion", 1).put("version", "7.1.1")
            .put("sourceSha256", "733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1")
            .put("license", "LGPL-2.1-or-later").put("buildRecipeSha256", hash("test recipe".toByteArray())).put("abis", rows)
    }
    private fun read(json: JSONObject) = PackagedOriginalMediaAdmission.readPins(ByteArrayInputStream(json.toString().toByteArray())) {}
    private fun directory(actual: Map<String, ByteArray> = tools.associateWith { elf() }): File = temp.newFolder().also { dir ->
        actual.forEach { (name, data) -> File(dir, name).writeBytes(data) }
    }
    private fun rejected(block: () -> Unit) = assertTrue("Invalid installed runtime was admitted", runCatching(block).isFailure)

    @Test fun admitsOnlyBothPinnedSystemOnlyExecutablesForTheInstalledAbi() {
        val dir = directory()
        val admitted = PackagedOriginalMediaAdmission.admit(dir, read(manifest()), listOf("x86_64", "x86")) {}
        assertEquals("x86_64", admitted)
        assertEquals(tools.toSet(), dir.listFiles().orEmpty().map { it.name }.toSet())
    }
    @Test fun wrongOrUnsupportedAbiCannotFallBackToAnotherInstalledArchitecture() {
        val dir = directory()
        rejected { PackagedOriginalMediaAdmission.admit(dir, read(manifest()), listOf("armeabi-v7a")) {} }
        rejected { PackagedOriginalMediaAdmission.admit(dir, read(manifest()), listOf("arm64-v8a", "x86_64")) {} }
    }
    @Test fun tamperedBytesOrMissingSecondToolCannotBeAdmitted() {
        val dir = directory()
        val original = File(dir, tools.first()).readBytes()
        original[900] = 1
        File(dir, tools.first()).writeBytes(original)
        rejected { PackagedOriginalMediaAdmission.admit(dir, read(manifest()), listOf("x86_64")) {} }
        File(dir, tools.first()).writeBytes(elf())
        File(dir, tools.last()).delete()
        rejected { PackagedOriginalMediaAdmission.admit(dir, read(manifest()), listOf("x86_64")) {} }
    }
    @Test fun aSymlinkCannotReplaceARegularPackagedExecutableEvenWithMatchingBytes() {
        val dir = directory()
        val external = temp.newFile().apply { writeBytes(elf()) }
        val first = File(dir, tools.first()).toPath()
        Files.delete(first); Files.createSymbolicLink(first, external.toPath())
        rejected { PackagedOriginalMediaAdmission.admit(dir, read(manifest()), listOf("x86_64")) {} }
    }
    @Test fun actualNonSystemDependenciesCannotHideBehindCleanManifestMetadata() {
        val files = tools.associateWith { elf(dependencies = listOf("libc.so", "libcrypto.so.3")) }
        val dir = directory(files)
        rejected { PackagedOriginalMediaAdmission.admit(dir, read(manifest(files)), listOf("x86_64")) {} }
    }
    @Test fun declaredDependenciesMustMatchEveryActualNeededLibrary() {
        val json = manifest()
        json.getJSONArray("abis").getJSONObject(1).getJSONObject("binaries").getJSONObject(tools.first())
            .put("systemNeeded", JSONArray(listOf("libc.so")))
        rejected { PackagedOriginalMediaAdmission.admit(directory(), read(json), listOf("x86_64")) {} }
    }
    @Test fun loaderSearchPathsAreRejectedEvenWhenTheirBytesArePinned() {
        val files = tools.associateWith { elf(runpath = true) }
        rejected { PackagedOriginalMediaAdmission.admit(directory(files), read(manifest(files)), listOf("x86_64")) {} }
    }
    @Test fun malformedElfTablesOrInterpreterCannotBecomeAcceptedPinnedExecutables() {
        val invalid = listOf(
            elf().also { it[0] = 0 },
            elf().also { it[4] = 1 },
            elf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(16, 2) },
            elf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(32, Long.MAX_VALUE) },
            elf().also { it[256] = 'x'.code.toByte() },
            elf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(328, Long.MAX_VALUE) })
        for (data in invalid) {
            val files = tools.associateWith { data }
            rejected { PackagedOriginalMediaAdmission.admit(directory(files), read(manifest(files)), listOf("x86_64")) {} }
        }
    }
    @Test fun schemaRejectsUnknownDuplicateAndIncompleteAbiOrToolInventories() {
        val cases = listOf(
            manifest().also { it.getJSONArray("abis").getJSONObject(1).put("abi", "arm64-v8a") },
            manifest().also { it.getJSONArray("abis").getJSONObject(1).put("abi", "x86") },
            manifest().also { it.getJSONArray("abis").getJSONObject(0).getJSONObject("binaries").remove(tools.last()) },
            manifest().also { it.getJSONArray("abis").getJSONObject(0).getJSONObject("binaries").put("libpython.so", JSONObject()) },
            manifest().also { it.put("unexpected", true) })
        cases.forEach { rejected { read(it) } }
    }
    @Test fun sourceLicenseVersionAndReceiptMetadataCannotSilentlyChange() {
        for ((field, value) in listOf("version" to "7.1.2", "schemaVersion" to 2, "license" to "GPL-3.0", "sourceSha256" to "b".repeat(64), "buildRecipeSha256" to "unrecorded")) {
            rejected { read(manifest().put(field, value)) }
        }
        val missingReceipt = manifest().also { it.getJSONArray("abis").getJSONObject(0).remove("buildReceiptSha256") }
        rejected { read(missingReceipt) }
    }
    @Test fun metadataAndDeclaredSizesAreBoundedBeforeAdmission() {
        rejected { PackagedOriginalMediaAdmission.readPins(ByteArrayInputStream(ByteArray(65_537))) {} }
        for (size in listOf<Any>(0, -1, Long.MAX_VALUE, 1.5, "1024")) {
            val json = manifest()
            json.getJSONArray("abis").getJSONObject(0).getJSONObject("binaries").getJSONObject(tools.first()).put("bytes", size)
            rejected { read(json) }
        }
        val json = manifest()
        json.getJSONArray("abis").getJSONObject(0).getJSONObject("binaries").getJSONObject(tools.first())
            .put("systemNeeded", JSONArray(listOf("libc.so", "libpython.so")))
        rejected { read(json) }
    }
    @Test fun cancellationPropagatesBeforeReadingOrAdmittingTools() {
        val stopped = InterruptedException("test cancelled")
        assertSame(stopped, runCatching { PackagedOriginalMediaAdmission.readPins(ByteArrayInputStream(ByteArray(0))) { throw stopped } }.exceptionOrNull())
        assertSame(stopped, runCatching { PackagedOriginalMediaAdmission.admit(directory(), read(manifest()), listOf("x86_64")) { throw stopped } }.exceptionOrNull())
    }
}
