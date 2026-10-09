package com.mangalens.download

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeMediaArchiveInstallerTest {
    @get:Rule val temp = TemporaryFolder()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun archive(values: Map<String, ByteArray>): Pair<File, NativeMediaArchivePin> {
        val file = temp.newFile()
        ZipOutputStream(file.outputStream()).use { zip -> values.forEach { (name, data) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
        } }
        val entries = values.map { (name, data) -> NativeMediaArchiveEntry(name, data.size.toLong(), hash(data),
            if (name.endsWith("/alias.so")) data.toString(Charsets.UTF_8) else null) }
        return file to NativeMediaArchivePin(file.length(), hash(file.readBytes()), entries)
    }

    @Test fun nestedVerifiedAliasesMatchTheActualPublisherArchive() {
        val (archive, pin) = archive(mapOf("usr/lib/compat/libnative.so" to "ELF-fixture".toByteArray(),
            "usr/lib/alias.so" to "compat/libnative.so".toByteArray()))
        val installed = NativeMediaArchiveInstaller.install(temp.newFolder(), archive, pin) {}
        assertTrue(java.nio.file.Files.isSymbolicLink(File(installed, "usr/lib/alias.so").toPath()))
        assertEquals("ELF-fixture", File(installed, "usr/lib/alias.so").readText())
    }

    @Test fun newVersionsNeverOverwriteAlreadyOpenLibraryBytes() {
        val root = temp.newFolder()
        val (first, firstPin) = archive(mapOf("usr/lib/libnative.so" to "old-library".toByteArray()))
        val old = NativeMediaArchiveInstaller.install(root, first, firstPin) {}
        File(old, "usr/lib/libnative.so").inputStream().use { handle ->
            val (next, nextPin) = archive(mapOf("usr/lib/libnative.so" to "new-library".toByteArray()))
            val current = NativeMediaArchiveInstaller.install(root, next, nextPin) {}
            assertNotEquals(old, current)
            assertEquals("old-library", handle.readBytes().toString(Charsets.UTF_8))
            assertEquals("new-library", File(current, "usr/lib/libnative.so").readText())
        }
        assertEquals(old, NativeMediaArchiveInstaller.install(root, first, firstPin) {})
    }

    @Test fun tamperedDataCannotActivateAPointer() {
        val root = temp.newFolder()
        val (archive, pin) = archive(mapOf("usr/lib/libnative.so" to "verified".toByteArray()))
        archive.appendBytes(byteArrayOf(1))
        assertTrue(runCatching { NativeMediaArchiveInstaller.install(root, archive, pin) {} }.isFailure)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun extractionCancellationCannotActivatePartialDependencies() {
        val root = temp.newFolder()
        val (archive, pin) = archive(mapOf("usr/lib/libnative.so" to ByteArray(128 * 1024) { 42 }))
        var calls = 0
        val failure = runCatching { NativeMediaArchiveInstaller.install(root, archive, pin) {
            if (++calls == 5) throw InterruptedException("fixture cancel")
        } }.exceptionOrNull()
        assertTrue(failure is InterruptedException)
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("active-") })
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".installing") })
    }

    @Test fun manifestCannotEscapeThePrivateVersionDirectory() {
        val root = temp.newFolder()
        val (archive, pin) = archive(mapOf("usr/lib/../../escape" to "invalid".toByteArray()))
        assertTrue(runCatching { NativeMediaArchiveInstaller.install(root, archive, pin) {} }.isFailure)
        assertFalse(File(root.parentFile, "escape").exists())
    }
}
