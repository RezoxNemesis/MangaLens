package com.mangalens.download

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONObject

internal data class PackagedOriginalMediaBinaryPin(val bytes: Long, val sha256: String, val systemNeeded: Set<String>)
internal data class PackagedOriginalMediaAbiPin(val abi: String, val buildReceiptSha256: String, val binaries: Map<String, PackagedOriginalMediaBinaryPin>)
internal data class PackagedOriginalMediaPins(val buildRecipeSha256: String, val abis: List<PackagedOriginalMediaAbiPin>)

/** A fixed APK pin schema, not a remote installer or an executable extraction mechanism. */
internal object PackagedOriginalMediaAdmission {
    const val ASSET = "media/mangalens-ffmpeg-7.1.1-pins.json"
    private const val SOURCE_SHA256 = "733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1"
    private val ABI_MACHINES = mapOf("arm64-v8a" to 183, "x86_64" to 62)
    private val TOOL_NAMES = setOf("libmangalens_ffmpeg.so", "libmangalens_ffprobe.so")
    private val SYSTEM_LIBRARIES = setOf("libc.so", "libm.so", "libdl.so", "liblog.so")
    private val HASH = Regex("[a-f0-9]{64}")

    fun readPins(input: InputStream, checkActive: () -> Unit): PackagedOriginalMediaPins {
        val data = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(count <= 65_536 - data.size()) { "Packaged original-media pin metadata is too large." }
            data.write(buffer, 0, count)
        }
        val root = JSONObject(data.toString(Charsets.UTF_8.name()))
        fun exactKeys(json: JSONObject, expected: Set<String>) = require(json.keys().asSequence().toSet() == expected)
        fun hash(json: JSONObject, name: String) = json.getString(name).also { require(it.matches(HASH)) }
        exactKeys(root, setOf("schemaVersion", "version", "sourceSha256", "license", "buildRecipeSha256", "abis"))
        require(root.get("schemaVersion") == 1 && root.getString("version") == "7.1.1" &&
            root.getString("sourceSha256") == SOURCE_SHA256 && root.getString("license") == "LGPL-2.1-or-later") {
            "Packaged original-media source or license pin does not match this build."
        }
        val recipe = hash(root, "buildRecipeSha256")
        val rows = root.getJSONArray("abis")
        require(rows.length() == ABI_MACHINES.size)
        val abis = (0 until rows.length()).map { index ->
            checkActive()
            val row = rows.getJSONObject(index)
            exactKeys(row, setOf("abi", "buildReceiptSha256", "binaries"))
            val abi = row.getString("abi").also { require(it in ABI_MACHINES) }
            val receipt = hash(row, "buildReceiptSha256")
            val binaryRows = row.getJSONObject("binaries")
            exactKeys(binaryRows, TOOL_NAMES)
            val binaries = TOOL_NAMES.associateWith { name ->
                val binary = binaryRows.getJSONObject(name)
                exactKeys(binary, setOf("bytes", "sha256", "systemNeeded"))
                val rawSize = binary.get("bytes")
                require(rawSize is Number)
                val size = requireNotNull(rawSize.toString().toLongOrNull()).also { require(it in 1..64L * 1024L * 1024L) }
                val dependencies = binary.getJSONArray("systemNeeded")
                require(dependencies.length() in 1..SYSTEM_LIBRARIES.size)
                val needed = (0 until dependencies.length()).map { dependencies.getString(it) }
                require(needed.size == needed.toSet().size && needed.all { it in SYSTEM_LIBRARIES })
                PackagedOriginalMediaBinaryPin(size, hash(binary, "sha256"), needed.toSet())
            }
            PackagedOriginalMediaAbiPin(abi, receipt, binaries)
        }
        require(abis.map { it.abi }.toSet() == ABI_MACHINES.keys)
        checkActive()
        return PackagedOriginalMediaPins(recipe, abis)
    }

    fun admit(binaryDirectory: File, pins: PackagedOriginalMediaPins, supportedAbis: List<String>, checkActive: () -> Unit): String {
        checkActive()
        val abi = supportedAbis.firstOrNull { it in ABI_MACHINES }
            ?: error("Original-media tools are not available for this device ABI.")
        val row = pins.abis.singleOrNull { it.abi == abi }
            ?: error("Original-media tools are not pinned for this installed ABI.")
        require(binaryDirectory.isDirectory)
        for (name in TOOL_NAMES) {
            checkActive()
            val pin = requireNotNull(row.binaries[name])
            val binary = File(binaryDirectory, name)
            require(!Files.isSymbolicLink(binary.toPath()) && binary.isFile && binary.length() == pin.bytes &&
                binary.canonicalFile.parentFile == binaryDirectory.canonicalFile && sha256(binary, checkActive) == pin.sha256) {
                "Packaged original-media executable failed its integrity check. Reinstall MangaLens."
            }
            val elf = PackagedOriginalMediaElfReader.read(binary, checkActive)
            require(elf.machine == ABI_MACHINES.getValue(abi) && elf.needed == pin.systemNeeded) {
                "Packaged original-media executable ABI or system dependencies do not match its pin."
            }
        }
        checkActive()
        return abi
    }

    private fun sha256(file: File, checkActive: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive(); val count = input.read(buffer); if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
