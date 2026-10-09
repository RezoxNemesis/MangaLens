package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** Real filesystem identity checks; image decoding/quality remains the Android gate. */
class ChapterTranslationSourceIdentityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun managedDirectory() = temporary.newFolder("private", "chapters")
    private fun source(directory: File, name: String = "page.png") = File(directory, name).apply { writeText("source bytes") }

    @Test fun androidStyleDirectoryAliasMatchesTheSameCanonicalPrivateSource() {
        val directory = managedDirectory()
        val expected = source(directory)
        val alias = File(temporary.root, "data-user-alias")
        Files.createSymbolicLink(alias.toPath(), directory.parentFile.toPath())
        val reader = File(alias, "chapters/${expected.name}")
        assertNotEquals(expected.absolutePath, reader.absolutePath)
        assertEquals(expected.canonicalFile, reader.canonicalFile)
        assertTrue(ChapterTranslationSourceIdentity.matches(expected.canonicalPath, reader.absolutePath, directory))
    }

    @Test fun ordinaryManagedPathStillMatches() {
        val directory = managedDirectory()
        val image = source(directory)
        assertTrue(ChapterTranslationSourceIdentity.matches(image.canonicalPath, image.absolutePath, directory))
    }

    @Test fun identicalBytesInAnotherPrivateFileCannotBorrowASurface() {
        val directory = managedDirectory()
        val first = source(directory)
        val second = source(directory, "other.png")
        assertEquals(first.readText(), second.readText())
        assertFalse(ChapterTranslationSourceIdentity.matches(first.canonicalPath, second.absolutePath, directory))
    }

    @Test fun unchangedBasenameOutsideManagedStorageIsRejected() {
        val directory = managedDirectory()
        val outside = source(temporary.newFolder("outside"))
        assertFalse(ChapterTranslationSourceIdentity.matches(outside.absolutePath, outside.absolutePath, directory))
    }

    @Test fun aManagedSymlinkCannotBorrowAnOutsideSource() {
        val directory = managedDirectory()
        val outside = source(temporary.newFolder("outside"))
        val alias = File(directory, "page.png")
        Files.createSymbolicLink(alias.toPath(), outside.toPath())
        assertFalse(ChapterTranslationSourceIdentity.matches(outside.canonicalPath, alias.absolutePath, directory))
    }

    @Test fun retargetingAnAliasCannotReuseAStaleIdentity() {
        val directory = managedDirectory()
        val first = source(directory)
        val second = source(directory, "other.png")
        val alias = File(directory, "visible.png")
        Files.createSymbolicLink(alias.toPath(), first.toPath())
        assertTrue(ChapterTranslationSourceIdentity.matches(first.canonicalPath, alias.absolutePath, directory))
        Files.delete(alias.toPath())
        Files.createSymbolicLink(alias.toPath(), second.toPath())
        assertFalse(ChapterTranslationSourceIdentity.matches(first.canonicalPath, alias.absolutePath, directory))
    }

    @Test fun missingPrivateSourceIsNotARestorableSurface() {
        val directory = managedDirectory()
        val missing = File(directory, "missing.png")
        assertFalse(ChapterTranslationSourceIdentity.matches(missing.absolutePath, missing.absolutePath, directory))
    }

    @Test fun blankAndAbsentSourcesNeverMatch() {
        val directory = managedDirectory()
        assertFalse(ChapterTranslationSourceIdentity.matches(null, null, directory))
        assertFalse(ChapterTranslationSourceIdentity.matches("", "", directory))
        assertFalse(ChapterTranslationSourceIdentity.matches("  ", "  ", directory))
    }
}
