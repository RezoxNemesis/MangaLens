package com.mangalens.core.imports

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeChapterArchiveTest {
    private fun archive(vararg pages: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { out -> pages.forEach { (name, bytes) -> out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() } }
    }.toByteArray()
    @Test fun naturallyOrdersPagesAndIgnoresNonImages() {
        val dir = Files.createTempDirectory("chapter").toFile()
        try {
            val input = archive("chapter/10.png" to byteArrayOf(10), "chapter/2.jpg" to byteArrayOf(2), "chapter/1.webp" to byteArrayOf(1), "readme.txt" to byteArrayOf(9))
            assertEquals(listOf(1, 2, 10), SafeChapterArchive.extract(ByteArrayInputStream(input), dir).map { it.readBytes().single().toInt() })
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectsTraversalAbsoluteAndWindowsPaths() {
        listOf("../outside.png", "/outside.png", "C:/outside.png", "..\\outside.png").forEach { name ->
            val dir = Files.createTempDirectory("chapter").toFile()
            try { SafeChapterArchive.extract(ByteArrayInputStream(archive(name to byteArrayOf(1))), dir); fail("Accepted $name") }
            catch (_: IllegalArgumentException) { assertFalse(dir.exists()) }
            finally { dir.deleteRecursively() }
        }
    }
    @Test fun rejectsExpansionAndCleansPartialPages() {
        val dir = Files.createTempDirectory("chapter").toFile()
        try { SafeChapterArchive.extract(ByteArrayInputStream(archive("1.png" to ByteArray(16), "2.png" to ByteArray(128))), dir, maxTotal = 64, maxPage = 64); fail("Accepted oversized expansion") }
        catch (_: IllegalArgumentException) { assertFalse(dir.exists()) }
        finally { dir.deleteRecursively() }
    }
    @Test fun rejectsNestedArchivesAndPageOverflow() {
        for (input in listOf(archive("1.png" to byteArrayOf(1), "nested.zip" to byteArrayOf(2)), archive("1.png" to byteArrayOf(1), "2.png" to byteArrayOf(2)))) {
            val dir = Files.createTempDirectory("chapter").toFile()
            try { SafeChapterArchive.extract(ByteArrayInputStream(input), dir, maxPages = 1); fail("Accepted unsupported archive") }
            catch (_: IllegalArgumentException) { assertFalse(dir.exists()) }
            finally { dir.deleteRecursively() }
        }
    }
}
