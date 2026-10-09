package com.mangalens

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.mangalens.core.reader.DocumentImporter
import com.mangalens.core.reader.ProgressiveChapterRepository
import com.mangalens.ui.reader.MangaContinuousReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Different pages of the same selected archive retain one original-document URI. */
@RunWith(AndroidJUnit4::class)
class ReaderDocumentPagesTest {
    @Test fun twoImportedPagesWithOneOriginalDocumentUriRenderAndNavigateIndependently() = coreScreenSmoke("reader-document-pages") {
        val fixture = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "reader-document-$fixture").apply { check(mkdirs()) }
        val fixtureContext = object : ContextWrapper(context) { override fun getFilesDir(): File = directory }
        val title = "Imported document $fixture"
        val prefs = context.getSharedPreferences("mangalens_reader", 0)
        val oldMode = prefs.getString("default_mode", "vertical")
        prefs.edit().putString("default_mode", "vertical").remove("mode_$title").commit()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val images = listOf(File(directory,"first.png"),File(directory,"second.png"))
            images.forEachIndexed { index,file ->
                val bitmap = Bitmap.createBitmap(320,if(index==0)160 else 960,Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(if(index==0)Color.RED else Color.BLUE)
                    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
                } finally { bitmap.recycle() }
            }
            val archive = File(directory,"original.cbz")
            ZipOutputStream(archive.outputStream()).use { output ->
                images.forEachIndexed { index,file ->
                    output.putNextEntry(ZipEntry("pages/${index+1}.png"))
                    output.write(file.readBytes()); output.closeEntry()
                }
            }
            val originalBytes = archive.readBytes()
            val repository = ProgressiveChapterRepository(fixtureContext)
            val pages = runBlocking {
                val imported = DocumentImporter.prepare(fixtureContext,listOf(Uri.fromFile(archive)))
                try { repository.persistLocalImages(imported.images,fixtureContext,imported.documentSources) }
                finally { imported.close() }
            }
            assertEquals(2,pages.size)
            assertEquals("The original-document URI is deliberately shared; it cannot be a LazyColumn page key",
                pages[0].sourceUrl,pages[1].sourceUrl)
            assertNotEquals(pages[0].localPath,pages[1].localPath)
            val cachedBytes = pages.map { File(requireNotNull(it.localPath)).readBytes() }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity -> activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    MangaContinuousReader(title,pages,chapterId="document-$fixture",translated=false,overlays=emptyMap(),
                        onTranslate={},onDownload={},onMenu={},onLongPressPage={})
                }
            } }
            assertTrue("The first imported page must render", device.wait(androidx.test.uiautomator.Until.hasObject(By.desc("Page 1")),8000))
            assertTrue("The second page must also compose without duplicate document-URI keys",
                device.wait(androidx.test.uiautomator.Until.hasObject(By.desc("Page 2")),8000))
            clickSettledUi(device,By.text("Reader tools").pkg(context.packageName),8000)
            clickSettledUi(device,By.text("Next ›").pkg(context.packageName),8000)
            assertTrue("Next must select the second imported page",
                device.wait(androidx.test.uiautomator.Until.hasObject(By.text("Page 2 / 2")),8000))
            clickSettledUi(device,By.text("Reader tools").pkg(context.packageName),8000)
            ReaderSourceFrameOracle.awaitVisibleSource(this,"Page 2",File(requireNotNull(pages[1].localPath)),emptyList(),
                android.os.SystemClock.uptimeMillis()+8000)
            assertArrayEquals(originalBytes,archive.readBytes())
            pages.forEachIndexed { index,page -> assertArrayEquals(cachedBytes[index],File(requireNotNull(page.localPath)).readBytes()) }
            capture("two-pages-one-document-uri")
        } catch(failure:Throwable) { recordFailure(failure); throw failure }
        finally {
            scenario?.close()
            prefs.edit().putString("default_mode",oldMode).remove("mode_$title").commit()
            directory.deleteRecursively()
        }
    }
}
