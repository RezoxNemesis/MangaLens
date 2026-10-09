package com.mangalens

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.reader.DocumentImporter
import com.mangalens.core.reader.ProgressiveChapterRepository
import com.mangalens.core.reader.SavedChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DocumentSourceRecoveryTest {
    @Test fun coldReopenedArchiveRepairsOnlyItsSelectedOriginalEntryAfterExtractionIsDeleted() = runBlocking<Unit> {
        Fixture().use { f ->
            val first = png(Color.RED)
            val second = png(Color.BLUE)
            val archive = File(f.directory,"original.cbz")
            writeArchive(archive,"pages/10.png" to png(Color.GREEN), "pages/2.png" to second, "pages/1.png" to first)
            val originalBytes = archive.readBytes()
            val imported = DocumentImporter.prepare(f.context,listOf(Uri.fromFile(archive)))
            val pages = try { f.repository.persistLocalImages(imported.images,f.context,imported.documentSources) }
                finally { imported.close() }
            assertFalse("The transient extraction must be gone before recovery", imported.directory.exists())
            assertEquals(listOf("pages/1.png","pages/2.png","pages/10.png"), pages.map { it.documentSource?.archiveEntryName })
            assertTrue(pages.all { it.sourceUrl == Uri.fromFile(archive).toString() })
            val library = ChapterLibrary(f.context)
            val id = ChapterLibrary.id("fixture:${UUID.randomUUID()}")
            library.save(SavedChapter(id,"Original archive", "", pages))
            val restored = ChapterLibrary(f.context).list().single()
            assertEquals(pages.map { it.documentSource }, restored.pages.map { it.documentSource })
            val neighbourBytes = restored.pages.filter { it.index != 2 }.associate { it.index to File(it.localPath!!).readBytes() }
            val target = restored.pages[1]
            val cache = File(target.localPath!!).apply { writeText("Interrupted extracted source") }
            val reopened = ProgressiveChapterRepository(f.context).apply { restorePages(restored.pages) }

            val repaired = reopened.repairPage(target,f.context)

            assertEquals(target.localPath,repaired.localPath)
            assertArrayEquals(second,cache.readBytes())
            assertEquals(3,reopened.pages.value.size)
            neighbourBytes.forEach { (index,bytes) -> assertArrayEquals(bytes,File(reopened.pages.value.single { it.index == index }.localPath!!).readBytes()) }
            assertArrayEquals("Regeneration cannot modify the selected archive",originalBytes,archive.readBytes())
            assertFalse(File(cache.path+".part").exists())
            library.remove(id)
        }
    }

    @Test fun changedArchiveCannotSilentlyReplaceACapturedSourcePage() = runBlocking<Unit> {
        Fixture().use { f ->
            val archive = File(f.directory,"original.cbz")
            writeArchive(archive,"1.png" to png(Color.RED),"2.png" to png(Color.BLUE))
            val imported = DocumentImporter.prepare(f.context,listOf(Uri.fromFile(archive)))
            val pages = try { f.repository.persistLocalImages(imported.images,f.context,imported.documentSources) }
                finally { imported.close() }
            val target = pages[1]
            val cache = File(target.localPath!!).apply { writeText("Keep interrupted original") }
            val neighbour = File(pages[0].localPath!!).readBytes()
            writeArchive(archive,"1.png" to png(Color.RED),"2.png" to png(Color.GREEN))
            val changedOriginal = archive.readBytes()
            try {
                f.repository.repairPage(target,f.context)
                fail("A document whose actual bytes changed is a new source selection")
            } catch (expected:IllegalStateException) { assertTrue(expected.message.orEmpty().contains("original document has changed")) }
            assertEquals("Keep interrupted original",cache.readText())
            assertArrayEquals(neighbour,File(pages[0].localPath!!).readBytes())
            assertArrayEquals(changedOriginal,archive.readBytes())
            assertEquals(pages,f.repository.pages.value)
            assertFalse(File(cache.path+".part").exists())
        }
    }

    @Test fun pdfRecoveryRendersOnlyTheSavedPageAfterTransientRendersAreDeleted() = runBlocking<Unit> {
        Fixture().use { f ->
            val pdf = File(f.directory,"original.pdf")
            val document = PdfDocument()
            try {
                listOf(Color.RED,Color.BLUE).forEachIndexed { index,color ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(400,600,index+1).create())
                    page.canvas.drawColor(color)
                    document.finishPage(page)
                }
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
            val sourceBytes = pdf.readBytes()
            val imported = DocumentImporter.prepare(f.context,listOf(Uri.fromFile(pdf)))
            val pages = try { f.repository.persistLocalImages(imported.images,f.context,imported.documentSources) }
                finally { imported.close() }
            assertEquals(listOf(0,1),pages.map { it.documentSource?.pageIndex })
            val neighbourBytes = File(pages[0].localPath!!).readBytes()
            val target = pages[1]
            File(target.localPath!!).writeText("Interrupted rendered PDF source")

            val repaired = f.repository.repairPage(target,f.context)

            val rendered = BitmapFactory.decodeFile(repaired.localPath) ?: error("Recovered PDF image was unreadable")
            try { assertEquals(Color.BLUE,rendered.getPixel(rendered.width/2,rendered.height/2)) }
            finally { rendered.recycle() }
            assertArrayEquals(neighbourBytes,File(pages[0].localPath!!).readBytes())
            assertArrayEquals(sourceBytes,pdf.readBytes())
            assertEquals(2,f.repository.pages.value.size)
            assertFalse(imported.directory.exists())
        }
    }

    @Test fun providerPermissionEvidenceReflectsTheActualPersistedReadGrant() = runBlocking<Unit> {
        Fixture().use { f ->
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val archive = File(File(app.filesDir,"downloads").apply { mkdirs() },"source-grant-${UUID.randomUUID()}.cbz")
            try {
                writeArchive(archive,"1.png" to png(Color.RED))
                val uri = FileProvider.getUriForFile(app,"${app.packageName}.downloads",archive)
                val imported = DocumentImporter.prepare(f.context,listOf(uri))
                try {
                    val observed = app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
                    assertEquals(observed,imported.documentSources.single()!!.persistedReadPermission)
                    assertEquals(uri.toString(),imported.documentSources.single()!!.uri)
                    assertFalse("The app's own FileProvider does not imply a persistent SAF grant",observed)
                } finally { imported.close() }
            } finally { archive.delete() }
        }
    }

    private class Fixture:AutoCloseable {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"document-repair-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object:ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getFilesDir():File = File(directory,"private-files").apply { mkdirs() }
            override fun getCacheDir():File = File(directory,"private-cache").apply { mkdirs() }
        }
        val repository = ProgressiveChapterRepository(context)
        override fun close() { directory.deleteRecursively() }
    }

    private fun png(color:Int):ByteArray {
        val bitmap = Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(color); return ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)); out.toByteArray() } }
        finally { bitmap.recycle() }
    }
    private fun writeArchive(file:File,vararg entries:Pair<String,ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name,bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }
}
