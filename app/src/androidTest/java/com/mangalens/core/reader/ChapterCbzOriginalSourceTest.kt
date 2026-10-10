package com.mangalens.core.reader

import android.graphics.Bitmap
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android FD/PNG/decode oracles, authored only; not compiled or run in this phase. */
@RunWith(AndroidJUnit4::class)
class ChapterCbzOriginalSourceTest {
    private lateinit var root: File
    private lateinit var managed: File
    @Before fun prepare() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        root = File(app.cacheDir, "cbz-source-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        managed = File(root, "chapters").apply { check(mkdirs()) }
    }
    @After fun cleanup() { root.deleteRecursively() }
    private fun png(name: String = "page.png", transparent: Boolean = true): File {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(if (transparent) 0 else android.graphics.Color.BLACK)
            return File(managed, name).also { file -> file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        } finally { bitmap.recycle() }
    }
    private fun page(file: File) = ChapterCbzPage(4, "https://example.test/page", file.absolutePath, null)
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected source rejection") } catch (_: IOException) {} catch (_: IllegalArgumentException) {}
    }
    @Test fun validTransparentBlankAndOpaqueBlankPngRemainExportable() {
        val source = ChapterCbzOriginalSource(managed)
        listOf(png("transparent.png"), png("black.png", false)).forEach { file ->
            source.open(page(file)) {}.use { held ->
                val out = ByteArrayOutputStream(); held.copyTo(out) {}
                assertArrayEquals(file.readBytes(), out.toByteArray()); assertEquals("png", held.pin.extension)
            }
        }
    }
    @Test fun truncatedPngIsRejectedBeforeItCanBecomeAnEntry() {
        val file = png(); val bytes = file.readBytes(); file.writeBytes(bytes.copyOf(bytes.size - 8))
        rejected { ChapterCbzOriginalSource(managed).open(page(file)) {}.close() }
    }
    @Test fun restoredMtimeSameLengthContentChangeFailsFreshSourceHash() {
        val file = png(); val source = ChapterCbzOriginalSource(managed)
        val pin = source.open(page(file)) {}.use { it.pin }; val stamp = file.lastModified()
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        assertTrue(file.setLastModified(stamp))
        rejected { source.verify(page(file), pin) {} }
    }
    @Test fun atomicReplacementWithIdenticalBytesRejectsOldFileOwner() {
        val file = png(); val source = ChapterCbzOriginalSource(managed)
        val pin = source.open(page(file)) {}.use { it.pin }
        val replacement = File(managed, "replacement.png").apply { writeBytes(file.readBytes()) }
        Os.rename(replacement.absolutePath, file.absolutePath)
        rejected { source.verify(page(file), pin) {} }
    }
    @Test fun selectedSymlinkAndExternalFileAreRejected() {
        val original = png(); val link = File(managed, "linked.png")
        Os.symlink(original.absolutePath, link.absolutePath)
        val external = File(root, "outside.png").apply { writeBytes(original.readBytes()) }
        rejected { ChapterCbzOriginalSource(managed).open(page(link)) {}.close() }
        rejected { ChapterCbzOriginalSource(managed).open(page(external)) {}.close() }
    }
    @Test fun sourceDeletedWhileDescriptorIsHeldCannotBeCopiedAsCurrentOriginal() {
        val original = png(); val source = ChapterCbzOriginalSource(managed)
        source.open(page(original)) {}.use { held ->
            assertTrue(original.delete())
            try { held.copyTo(ByteArrayOutputStream()) {}; fail("Deleted original accepted") }
            catch (_: Exception) {}
        }
    }
    @Test fun actualHeldPngSourceMakesByteIdenticalOrdinalCbzWithoutPrivateMetadata() {
        val first = png("original-eight.png"); val second = png("original-two.png", false)
        val chapter = SavedChapter("b".repeat(32), "title", "https://example.test/chapter",
            listOf(ChapterPage(8, "eight", first.absolutePath), ChapterPage(2, "two", second.absolutePath)), notes = "private note")
        val captured = ChapterCbzScope.capture(chapter)
        val artifact = ChapterCbzArchive(File(root, "exports"), ChapterCbzOriginalSource(managed)) { it.matches(chapter) }
            .prepare(captured, ChapterCbzOperation()) {}
        ZipFile(artifact.file).use { zip ->
            assertEquals(listOf("00001.png", "00002.png"), zip.entries().asSequence().map { it.name }.toList())
            assertArrayEquals(first.readBytes(), zip.getInputStream(zip.getEntry("00001.png")).use { it.readBytes() })
            assertArrayEquals(second.readBytes(), zip.getInputStream(zip.getEntry("00002.png")).use { it.readBytes() })
            assertEquals(2, zip.size())
        }
        assertEquals(listOf(8, 2), artifact.receipt.pageHashes.map { it.sourceIndex })
    }
}
