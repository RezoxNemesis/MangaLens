package com.mangalens.download

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeMediaArchiveChainTest {
    @get:Rule val temp = TemporaryFolder()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun archive(values: Map<String, ByteArray>, links: Map<String, String>): Pair<File, NativeMediaArchivePin> {
        val data = values + links.mapValues { it.value.toByteArray(Charsets.UTF_8) }
        val zipFile = temp.newFile()
        ZipOutputStream(zipFile.outputStream()).use { zip -> data.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
        val entries = data.map { (name, bytes) -> NativeMediaArchiveEntry(name, bytes.size.toLong(), hash(bytes), links[name]) }
        return zipFile to NativeMediaArchivePin(zipFile.length(), hash(zipFile.readBytes()), entries)
    }
    private fun chain() = archive(mapOf("usr/lib/libglib-2.0.so.0.8600.0" to "verified native data".toByteArray()),
        linkedMapOf("usr/lib/libglib-2.0.so" to "libglib-2.0.so.0", "usr/lib/libglib-2.0.so.0" to "libglib-2.0.so.0.8600.0"))

    @Test fun bothPinnedAliasesReachTheRegularLibraryWithoutReplacingItsBytes() {
        val (archive, pin) = chain()
        val installed = NativeMediaArchiveInstaller.install(temp.newFolder(), archive, pin) {}
        for (name in listOf("libglib-2.0.so", "libglib-2.0.so.0")) {
            val alias = File(installed, "usr/lib/$name")
            assertTrue(Files.isSymbolicLink(alias.toPath()))
            assertEquals("verified native data", alias.readText())
        }
        assertEquals("libglib-2.0.so.0", Files.readSymbolicLink(File(installed, "usr/lib/libglib-2.0.so").toPath()).toString())
        assertEquals("libglib-2.0.so.0.8600.0", Files.readSymbolicLink(File(installed, "usr/lib/libglib-2.0.so.0").toPath()).toString())
    }

    @Test fun all178DeclaredPublisherEntryPathsAndSymlinkTargetsAreAdmittedInARealZip() {
        val text = requireNotNull(javaClass.getResourceAsStream("/media/ffmpeg-0.18.1-x86_64-entry-pins.json")).use { it.readBytes().toString(Charsets.UTF_8) }
        val entries = JSONObject(text).getJSONArray("entries")
        assertEquals(178, entries.length())
        val regular = linkedMapOf<String, ByteArray>()
        val links = linkedMapOf<String, String>()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.has("symlink")) links[entry.getString("path")] = entry.getString("symlink")
            else regular[entry.getString("path")] = "generated fixture for ${entry.getString("path")}".toByteArray()
        }
        assertEquals(55, links.size)
        val (archive, pin) = archive(regular, links)
        val installed = NativeMediaArchiveInstaller.install(temp.newFolder(), archive, pin) {}
        for ((name, data) in regular) assertArrayEquals(data, File(installed, name).readBytes())
        for ((name, target) in links) {
            assertEquals(target, Files.readSymbolicLink(File(installed, name).toPath()).toString())
            assertTrue("Declared chain did not terminate at a regular pinned file: $name", File(installed, name).isFile)
        }
    }

    @Test fun cyclesMissingOutsideAndSelfTargetsAreRejectedBeforeCreatingTheBase() {
        val cases = listOf(
            linkedMapOf("usr/lib/a.so" to "b.so", "usr/lib/b.so" to "a.so"),
            linkedMapOf("usr/lib/a.so" to "missing.so"),
            linkedMapOf("usr/lib/a.so" to "../outside.so"),
            linkedMapOf("usr/lib/a.so" to "/outside.so"),
            linkedMapOf("usr/lib/a.so" to "a.so"))
        for ((index, links) in cases.withIndex()) {
            val (archive, pin) = archive(mapOf("usr/lib/regular.so" to "verified".toByteArray()), links)
            val base = File(temp.root, "rejected-$index")
            assertTrue("Unsafe alias set $index was accepted", runCatching { NativeMediaArchiveInstaller.install(base, archive, pin) {} }.isFailure)
            assertFalse("Unsafe alias set $index was extracted before graph admission", base.exists())
        }
    }

    @Test fun duplicatePinPathsAreRejectedBeforeMapLookupOrExtraction() {
        val (archive, pin) = chain()
        val duplicate = pin.copy(entries = pin.entries + pin.entries.first().copy(sha256 = "b".repeat(64)))
        val base = File(temp.root, "duplicate")
        assertTrue(runCatching { NativeMediaArchiveInstaller.install(base, archive, duplicate) {} }.isFailure)
        assertFalse(base.exists())
    }

    @Test fun chainedRegularPayloadStillRequiresItsPinnedHashBeforeActivation() {
        val (archive, pin) = chain()
        val invalid = pin.copy(entries = pin.entries.map { if (it.symlink == null) it.copy(sha256 = "b".repeat(64)) else it })
        val base = temp.newFolder()
        assertTrue(runCatching { NativeMediaArchiveInstaller.install(base, archive, invalid) {} }.isFailure)
        assertFalse(base.listFiles().orEmpty().any { it.name.startsWith("active-") })
        assertFalse(base.listFiles().orEmpty().any { it.name.endsWith(".installing") })
    }

    @Test fun cachedIntermediateAliasTamperingCannotReuseTheOldVersion() {
        val (archive, pin) = chain()
        val base = temp.newFolder()
        val first = NativeMediaArchiveInstaller.install(base, archive, pin) {}
        val middle = File(first, "usr/lib/libglib-2.0.so.0").toPath()
        Files.delete(middle); Files.createSymbolicLink(middle, Paths.get("not-pinned.so"))
        val restored = NativeMediaArchiveInstaller.install(base, archive, pin) {}
        assertNotEquals(first, restored)
        assertEquals("verified native data", File(restored, "usr/lib/libglib-2.0.so").readText())
        assertEquals("not-pinned.so", Files.readSymbolicLink(middle).toString())
    }
}
