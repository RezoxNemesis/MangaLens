package com.mangalens.download

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

internal data class NativeMediaArchiveEntry(val path: String, val bytes: Long, val sha256: String, val symlink: String? = null)
internal data class NativeMediaArchivePin(val bytes: Long, val sha256: String, val entries: List<NativeMediaArchiveEntry>, val completeInventory: Boolean = true)

/** Verified, versioned dependency data; an already mapped library is never overwritten. */
internal object NativeMediaArchiveInstaller {
    fun install(base: File, archive: File, pin: NativeMediaArchivePin, checkActive: () -> Unit): File {
        checkActive()
        require(archive.length() == pin.bytes && sha256(archive, checkActive) == pin.sha256) {
            "Packaged original-media runtime failed its integrity check."
        }
        require(pin.entries.isNotEmpty() && pin.entries.size <= 512)
        require(pin.entries.map { it.path }.distinct().size == pin.entries.size)
        val expected = pin.entries.associateBy { it.path }
        pin.entries.forEach { entry ->
            checkActive()
            require(entry.path.matches(Regex("usr/lib/[A-Za-z0-9._+/-]+")) &&
                entry.path.split('/').all { it.isNotBlank() && it !in setOf(".", "..") })
            require(entry.bytes in 1L..256L * 1024L * 1024L && entry.sha256.matches(Regex("[a-f0-9]{64}")))
            entry.symlink?.let { target ->
                require(target.matches(Regex("[A-Za-z0-9._+/-]+")) &&
                    target.split('/').all { it.isNotBlank() && it !in setOf(".", "..") })
            }
        }
        // Admit the whole declared graph before extracting anything. Real library aliases may
        // have several pinned hops; none may escape the inventory or revisit a declared path.
        pin.entries.forEach { entry ->
            var current = entry
            val visited = mutableSetOf<String>()
            while (true) {
                checkActive()
                val target = current.symlink ?: break
                require(visited.add(current.path)) { "Packaged media dependency contains a cyclic alias." }
                val resolved = java.nio.file.Paths.get(current.path).parent.resolve(target).normalize().toString()
                require(resolved.startsWith("usr/lib/"))
                current = requireNotNull(expected[resolved]) { "Packaged media dependency alias is not pinned." }
            }
        }
        base.mkdirs()
        val prefix = pin.sha256.take(24)
        val pointer = File(base, "active-$prefix")
        val existingName = pointer.takeIf { it.isFile && it.length() <= 128 }?.readText()?.trim()
        if (existingName?.matches(Regex("$prefix-[a-f0-9-]{36}")) == true) {
            val existing = File(base, existingName)
            if (valid(existing, pin, checkActive)) return existing
        }
        val version = File(base, "$prefix-${UUID.randomUUID()}")
        val staging = File(base, ".${version.name}.installing").apply { mkdirs() }
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
                val names = entries.map { it.name }.toSet()
                require(entries.size <= 4096 && names.size == entries.size &&
                    (if (pin.completeInventory) entries.size == expected.size && names == expected.keys else names.containsAll(expected.keys))) {
                    "Packaged media dependency inventory does not match its pin."
                }
                for (entry in entries) {
                    checkActive()
                    val record = expected[entry.name] ?: continue
                    require(entry.size == record.bytes)
                    val output = File(staging, record.path)
                    output.parentFile?.mkdirs()
                    if (record.symlink == null) {
                        zip.getInputStream(entry).use { input -> output.outputStream().use { destination ->
                            val buffer = ByteArray(64 * 1024)
                            var count = 0L
                            while (true) {
                                checkActive()
                                val size = input.read(buffer); if (size < 0) break
                                count += size; require(count <= record.bytes)
                                destination.write(buffer, 0, size)
                            }
                            require(count == record.bytes)
                        } }
                        require(sha256(output, checkActive) == record.sha256)
                        output.setReadOnly()
                    } else {
                        val payload = zip.getInputStream(entry).use { it.readBytes() }
                        require(payload.size.toLong() == record.bytes && hash(payload) == record.sha256 &&
                            payload.toString(Charsets.UTF_8) == record.symlink)
                    }
                }
            }
            for (entry in pin.entries.filter { it.symlink != null }) {
                checkActive()
                Files.createSymbolicLink(File(staging, entry.path).toPath(), java.nio.file.Paths.get(requireNotNull(entry.symlink)))
            }
            require(valid(staging, pin, checkActive))
            checkActive()
            Files.move(staging.toPath(), version.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val nextPointer = File(base, ".active-${UUID.randomUUID()}")
            try {
                nextPointer.writeText(version.name)
                checkActive()
                Files.move(nextPointer.toPath(), pointer.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { nextPointer.delete() }
            return version
        } finally { staging.deleteRecursively() }
    }

    private fun valid(directory: File, pin: NativeMediaArchivePin, checkActive: () -> Unit): Boolean {
        if (!directory.isDirectory) return false
        for (entry in pin.entries) {
            checkActive()
            val file = File(directory, entry.path)
            if (entry.symlink != null) {
                if (!Files.isSymbolicLink(file.toPath()) || Files.readSymbolicLink(file.toPath()).toString() != entry.symlink) return false
            } else if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() != entry.bytes ||
                sha256(file, checkActive) != entry.sha256) return false
        }
        return true
    }

    fun sha256(file: File, checkActive: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive(); val size = input.read(buffer); if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
