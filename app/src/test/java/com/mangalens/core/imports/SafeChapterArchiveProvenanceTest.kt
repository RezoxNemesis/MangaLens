package com.mangalens.core.imports

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeChapterArchiveProvenanceTest {
    @Test fun namedEntriesRetainTheOriginalIdentityAfterNaturalOrdering() {
        val directory = Files.createTempDirectory("archive-proof").toFile()
        try {
            val pages = SafeChapterArchive.extractEntries(ByteArrayInputStream(archive(
                "chapter/10.png" to byteArrayOf(10), "chapter/2.png" to byteArrayOf(2))), directory)
            assertEquals(listOf("chapter/2.png", "chapter/10.png"), pages.map { it.entryName })
            assertEquals(listOf(2, 10), pages.map { it.file.readBytes().single().toInt() })
        } finally { directory.deleteRecursively() }
    }

    @Test fun selectedEntryExtractionWritesOnlyTheSelectedTemporaryOutput() {
        val directory = Files.createTempDirectory("archive-selected").toFile()
        val neighbour = java.io.File(directory,"retained.img").apply { writeText("keep neighbour") }
        val target = java.io.File(directory,"page.part")
        try {
            SafeChapterArchive.extractSelected(ByteArrayInputStream(archive(
                "pages/1.png" to byteArrayOf(1), "pages/2.png" to byteArrayOf(2), "pages/10.png" to byteArrayOf(10))),
                target, "pages/2.png")
            assertArrayEquals(byteArrayOf(2), target.readBytes())
            assertEquals("keep neighbour", neighbour.readText())
            assertEquals(setOf("retained.img","page.part"), directory.listFiles()!!.map { it.name }.toSet())
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidLaterEntriesCannotLeaveASuccessfullySelectedPageBehind() {
        val directory = Files.createTempDirectory("archive-reject").toFile()
        val target = java.io.File(directory,"page.part")
        val neighbour = java.io.File(directory,"retained.img").apply { writeText("keep neighbour") }
        try {
            try {
                SafeChapterArchive.extractSelected(ByteArrayInputStream(archive(
                    "pages/1.png" to byteArrayOf(1), "../outside.png" to byteArrayOf(9))), target, "pages/1.png")
                fail("All archive paths must be checked even after finding the selected entry")
            } catch (_: IllegalArgumentException) { assertFalse(target.exists()) }
            assertEquals("keep neighbour", neighbour.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun nonselectedEntriesStillCountTowardsTheExpansionLimit() {
        val directory = Files.createTempDirectory("archive-limit").toFile()
        val target = java.io.File(directory,"page.part")
        try {
            try {
                SafeChapterArchive.extractSelected(ByteArrayInputStream(archive(
                    "1.png" to byteArrayOf(1), "2.png" to ByteArray(128))), target, "1.png", maxTotal = 64, maxPage = 256)
                fail("Skipping neighbour writes cannot disable archive expansion limits")
            } catch (_: IllegalArgumentException) { assertFalse(target.exists()) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun normalizedDuplicateEntryNamesCannotChooseAnAmbiguousSource() {
        val directory = Files.createTempDirectory("archive-ambiguous").toFile()
        val target = java.io.File(directory,"page.part")
        try {
            try {
                SafeChapterArchive.extractSelected(ByteArrayInputStream(archive(
                    "pages/1.png" to byteArrayOf(1), "pages\\1.png" to byteArrayOf(2))), target, "pages/1.png")
                fail("Duplicate normalized names cannot identify a recoverable page")
            } catch (_: IllegalArgumentException) { assertFalse(target.exists()) }
        } finally { directory.deleteRecursively() }
    }

    private fun archive(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
}
