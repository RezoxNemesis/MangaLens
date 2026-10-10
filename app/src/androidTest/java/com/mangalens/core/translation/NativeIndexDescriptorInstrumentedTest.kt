package com.mangalens.core.translation

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Authored UNRUN: actual Android Os descriptor checks, distinct from the JVM handle seam. */
class NativeIndexDescriptorInstrumentedTest {
    @Test fun actualOpenDescriptorRejectsIdenticalAtomicPathReplacement() = fixture { file ->
        AndroidNativeIndexReadHandle(file).use { handle ->
            assertTrue(handle.matchesPath())
            val replacement = File(file.parentFile, "replacement").apply { writeBytes(file.readBytes()) }
            Files.setLastModifiedTime(replacement.toPath(), Files.getLastModifiedTime(file.toPath()))
            Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            assertFalse(handle.matchesPath())
        }
    }
    @Test fun noFollowOpenRejectsALinkToAnotherManagedFile() = fixture { file ->
        val link = File(file.parentFile, "link.jpg")
        Files.createSymbolicLink(link.toPath(), file.toPath())
        var accepted = false
        try { AndroidNativeIndexReadHandle(link).use { accepted = true } } catch (_: Exception) {}
        assertFalse(accepted)
    }
    @Test fun actualAndroidDescriptorDigestContinuesAcrossClosedPasses() = fixture { file ->
        runBlocking {
            val cursor = NativeIndexIncrementalHasher(NativeIndexFileSpec(7, true, file,
                ChapterTranslationStore.sha256(file), 40L * 1024 * 1024, false))
            val first = NativeIndexPassBudget(1024); assertNull(cursor.pass(first) { null }); assertEquals(1024L, first.used)
            val second = NativeIndexPassBudget(); assertNotNull(cursor.pass(second) { null })
            assertEquals(file.length() - 1024, second.used)
        }
    }
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "native-index-fd-").toFile()
        try { block(File(root, "source.jpg").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }) }
        finally { root.deleteRecursively() }
    }
}
