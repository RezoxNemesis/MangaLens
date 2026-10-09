package com.mangalens.core.reader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class ChapterDocumentManifestTest {
    @Test fun originalDocumentProvenanceSurvivesColdReopenWithoutTrustingRuntimeImageTokens() {
        fixture { root,chapter ->
            val library = ChapterLibrary(root,FileIo)
            library.save(chapter)
            val restored = ChapterLibrary(root,FileIo).list().single()
            assertEquals(chapter.pages.single().documentSource,restored.pages.single().documentSource)
            assertNull("An ephemeral presentation incarnation is not source authority",restored.pages.single().contentRevision)
            assertFalse(File(root,"chapter_library/${chapter.id}.json").readText().contains("runtime-incarnation"))
        }
    }
    @Test fun missingRenderedCacheStillRetainsTheOriginalDocumentForExplicitRecovery() {
        fixture { root,chapter ->
            ChapterLibrary(root,FileIo).save(chapter)
            File(chapter.pages.single().localPath!!).delete()
            val restored = ChapterLibrary(root,FileIo).list().single().pages.single()
            assertNull(restored.localPath)
            assertNotNull(restored.error)
            assertEquals(chapter.pages.single().documentSource,restored.documentSource)
        }
    }
    @Test fun legacyManifestsWithoutProvenanceRemainReadable() {
        fixture { root,chapter ->
            ChapterLibrary(root,FileIo).save(chapter)
            val file = File(root,"chapter_library/${chapter.id}.json")
            val json = JSONObject(file.readText())
            json.getJSONArray("pages").getJSONObject(0).remove("documentSource")
            file.writeText(json.toString())
            val restored = ChapterLibrary(root,FileIo).list().single()
            assertNull(restored.pages.single().documentSource)
            assertEquals(chapter.pages.single().localPath,restored.pages.single().localPath)
        }
    }
    @Test fun malformedOriginalDocumentMetadataCannotAuthorizeAnotherSource() {
        fixture { root,chapter ->
            ChapterLibrary(root,FileIo).save(chapter)
            val file = File(root,"chapter_library/${chapter.id}.json")
            val json = JSONObject(file.readText())
            json.getJSONArray("pages").getJSONObject(0).getJSONObject("documentSource").put("uri","https://different/archive.cbz")
            file.writeText(json.toString())
            assertTrue(ChapterLibrary(root,FileIo).list().isEmpty())
            assertTrue("Rejecting invalid metadata preserves the saved source",File(chapter.pages.single().localPath!!).exists())
        }
    }
    private fun fixture(body:(File,SavedChapter)->Unit) {
        val root = Files.createTempDirectory("document-manifest").toFile()
        try {
            val cache = File(root,"chapters/page.img").apply { parentFile.mkdirs(); writeText("private metadata fixture") }
            val source = ChapterDocumentSource("file:/selected/original.cbz","archive",0,"a".repeat(64),"pages/1.png")
            val page = ChapterPage(1,source.uri,cache.absolutePath,contentRevision="runtime-incarnation",documentSource=source)
            body(root,SavedChapter(ChapterLibrary.id("fixture:document"),"Original document","",listOf(page)))
        } finally { root.deleteRecursively() }
    }
    private object FileIo:ChapterLibraryJournalIo {
        override fun read(file:File):InputStream = file.inputStream()
        override fun write(file:File,bytes:ByteArray) { file.writeBytes(bytes) }
        override fun delete(file:File) { file.delete() }
    }
}
