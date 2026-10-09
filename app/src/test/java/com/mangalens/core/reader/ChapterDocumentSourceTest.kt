package com.mangalens.core.reader

import org.junit.Assert.*
import org.junit.Test

class ChapterDocumentSourceTest {
    private val archive = ChapterDocumentSource("content://documents/original.cbz","archive",1,"a".repeat(64),"chapter/2.png",true)
    @Test fun retainsTheExactOriginalAndObservedPermissionEvidence() { assertEquals(archive,archive.validate()); assertTrue(archive.persistedReadPermission) }
    @Test fun validatesPdfPageWithoutClaimingAnArchiveEntry() { assertEquals(299,archive.copy(kind="pdf",pageIndex=299,archiveEntryName=null).validate().pageIndex) }
    @Test fun rejectsInvalidUriChecksumPageAndArchiveIdentity() {
        listOf(archive.copy(uri="https://remote/original.cbz"),archive.copy(documentSha256="runtime:incarnation"),archive.copy(pageIndex=-1),
            archive.copy(archiveEntryName="../outside.png"),archive.copy(archiveEntryName="/outside.png"),archive.copy(archiveEntryName="C:/outside.png"),
            archive.copy(archiveEntryName="nested.zip"),archive.copy(kind="pdf",pageIndex=300,archiveEntryName=null)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { invalid.validate() }
        }
    }
}
