package com.mangalens.core.reader
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NativeLibraryMetadataGateTest {
    @Test fun nativePublicationDoesNotWaitForActualOrdinaryManifestSync() {
        val root=Files.createTempDirectory("native-library-gate-").toFile()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val failure=AtomicReference<Throwable?>()
        val io=object:ChapterLibraryJournalIo {
            override fun read(file:File):InputStream=file.inputStream()
            override fun delete(file:File){file.delete()}
            override fun write(file:File,bytes:ByteArray){file.outputStream().use { it.write(bytes);it.fd.sync();entered.countDown();check(release.await(5,TimeUnit.SECONDS)) }}
        }
        val library=ChapterLibrary(root,io)
        val image=File(root,"chapters/page.img").apply { parentFile!!.mkdirs();writeText("original") }
        val chapter=SavedChapter("a".repeat(32),"Saved","https://example.org/chapter",listOf(ChapterPage(1,"https://example.org/page.png",image.path)))
        val producer=Thread { try {library.save(chapter)} catch(caught:Throwable){failure.set(caught)} }
        try {
            producer.start();assertTrue(entered.await(5,TimeUnit.SECONDS));var renamed=false
            val result=runCatching { ChapterLibrary.publishNativeMetadata { renamed=true } }
            assertTrue(result.exceptionOrNull() is NativeLibraryMetadataBusyException);assertFalse(renamed)
            release.countDown();producer.join(5_000);assertFalse(producer.isAlive);assertNull(failure.get())
            assertEquals(chapter,library.findMetadata(chapter.id))
        } finally { release.countDown();producer.join(5_000);root.deleteRecursively() }
    }
}
