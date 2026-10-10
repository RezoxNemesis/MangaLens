package com.mangalens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.ProgressiveChapterRepository
import com.mangalens.core.reader.PngRasterIntegrity
import com.mangalens.ui.reader.MangaContinuousReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** Cached originals with unreadable pixels require a real source-repair control. */
@RunWith(AndroidJUnit4::class)
class ReaderSourceRecoveryTest {
    @Test fun aNonemptyUnreadableSavedOriginalOffersRetryWithoutDeletingTheOtherPage() = coreScreenSmoke("reader-source-recovery") {
        val fixture = UUID.randomUUID().toString()
        val corrupt = File(context.cacheDir, "reader-corrupt-$fixture.img").apply { writeText("Nonempty unreadable original") }
        val healthy = File(context.cacheDir, "reader-healthy-$fixture.png")
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.GREEN); healthy.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        val healthyBytes = healthy.readBytes()
        val clicked = AtomicBoolean()
        val genericRetry = AtomicBoolean()
        val translated = AtomicBoolean()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity -> activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    MangaContinuousReader("Source repair", listOf(
                        ChapterPage(1, "local:corrupt", corrupt.absolutePath),
                        ChapterPage(2, "local:healthy", healthy.absolutePath)), chapterId = "source-repair-$fixture",
                        translated = false, overlays = emptyMap(), onRetry = { genericRetry.set(true) },
                        onRetryPage = { clicked.set(it.index == 1 && it.localPath == corrupt.absolutePath) },
                        onTranslate = { translated.set(true) }, onDownload = {}, onMenu = {}, onLongPressPage = {})
                }
            } }
            clickSettledUi(device, By.text("Retry page loading").pkg(context.packageName), 8000)
            assertTrue("The source repair callback must be reachable", clicked.get())
            assertFalse("Source repair must not dispatch the generic retry", genericRetry.get())
            assertFalse("Source repair must not restart chapter translation", translated.get())
            assertEquals("Nonempty unreadable original", corrupt.readText())
            assertArrayEquals("Repair controls must retain unrelated originals", healthyBytes, healthy.readBytes())
            capture("source-retry-visible")
        } catch (failure: Throwable) { recordFailure(failure); throw failure }
        finally { scenario?.close(); corrupt.delete(); healthy.delete() }
    }

    @Test fun aBoundsReadableRasterFailureOffersRetryAndShowsTheReacquiredPixels() =
        boundsReadableRasterRecovery(fatalNativeRaster = true)

    @Test fun aRecomputedCrcPngWithBrokenZlibOffersRetryAndShowsTheReacquiredPixels() =
        boundsReadableRasterRecovery(fatalNativeRaster = false)

    private fun boundsReadableRasterRecovery(fatalNativeRaster: Boolean) =
        coreScreenSmoke(if (fatalNativeRaster) "reader-raster-recovery" else "reader-png-raster-recovery") {
        val fixture = UUID.randomUUID().toString()
        val directory = File(context.cacheDir,"reader-raster-$fixture").apply { check(mkdirs()) }
        val fixtureContext = object : ContextWrapper(context) { override fun getFilesDir(): File = directory }
        val title = "Raster repair $fixture"
        val prefs = context.getSharedPreferences("mangalens_reader",0)
        val oldMode = prefs.getString("default_mode","vertical")
        prefs.edit().putString("default_mode","vertical").remove("mode_$title").commit()
        var scenario: ActivityScenario<MainActivity>? = null
        val failure = AtomicReference<Throwable?>()
        val retryCount = AtomicInteger()
        val genericRetry = AtomicBoolean()
        val translated = AtomicBoolean()
        try {
            val original = File(directory,"selected-original.png")
            val neighbour = File(directory,"selected-neighbour.png")
            writePng(original,Color.RED); writePng(neighbour,Color.GREEN)
            val repository = ProgressiveChapterRepository(fixtureContext)
            val imported = runBlocking { repository.persistLocalImages(listOf(Uri.fromFile(original),Uri.fromFile(neighbour)),fixtureContext) }
            val target = File(requireNotNull(imported[0].localPath))
            target.writeBytes(if (fatalNativeRaster) BoundsReadableRasterFixture.create(320, 480)
                else unreadablePng(target.readBytes()))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFile(target.absolutePath,bounds)
            assertEquals(320,bounds.outWidth); assertEquals(480,bounds.outHeight)
            if (fatalNativeRaster) {
                val decoded = BitmapFactory.decodeFile(target.absolutePath)
                decoded?.recycle()
                assertNull("An actual Android decoder must reject the fixture despite intact bounds",decoded)
            } else {
                try {
                    PngRasterIntegrity.verifyIfPng(target)
                    fail("A recomputed chunk CRC cannot verify the broken original PNG zlib raster")
                } catch (expected: IllegalStateException) {
                    assertTrue(expected.message.orEmpty().contains("zlib"))
                }
            }
            val neighbourBytes = File(requireNotNull(imported[1].localPath)).readBytes()
            writePng(original,Color.BLUE)
            val sourceBytes = original.readBytes()
            val pages = mutableStateOf(imported)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity -> activity.setContent {
                androidx.compose.material3.MaterialTheme {
                    MangaContinuousReader(title,pages.value,chapterId="raster-$fixture",translated=false,overlays=emptyMap(),
                        onRetry={genericRetry.set(true)},onTranslate={translated.set(true)},onDownload={},onMenu={},onLongPressPage={},
                        onRetryPage={ page ->
                            retryCount.incrementAndGet()
                            activity.lifecycleScope.launch {
                                try { repository.repairPage(page,fixtureContext); pages.value=repository.pages.value }
                                catch(cancelled:CancellationException) { throw cancelled }
                                catch(problem:Throwable) { failure.set(problem) }
                            }
                        })
                }
            } }
            clickSettledUi(device,By.text("Retry page loading").pkg(context.packageName),8000)
            val deadline = android.os.SystemClock.uptimeMillis()+8000
            waitFor("The source retry did not publish verified replacement pixels",8000) {
                failure.get()?.let { throw AssertionError("The selected source retry failed",it) }
                repository.pages.value[0].contentRevision != imported[0].contentRevision
            }
            ReaderSourceFrameOracle.awaitVisibleSource(this,"Page 1",target,emptyList(),deadline)
            assertEquals(1,retryCount.get())
            assertFalse(genericRetry.get()); assertFalse(translated.get())
            assertArrayEquals(sourceBytes,target.readBytes())
            assertArrayEquals(neighbourBytes,File(requireNotNull(imported[1].localPath)).readBytes())
            assertArrayEquals(sourceBytes,original.readBytes())
            assertNotEquals(imported[0].contentRevision,repository.pages.value[0].contentRevision)
            capture(if (fatalNativeRaster) "bounds-readable-raster-source-repaired" else "png-zlib-source-repaired")
        } catch(problem:Throwable) { recordFailure(problem); throw problem }
        finally {
            scenario?.close()
            prefs.edit().putString("default_mode",oldMode).remove("mode_$title").commit()
            directory.deleteRecursively()
        }
    }

    private fun writePng(file:File,color:Int) {
        val bitmap=Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(color); file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { bitmap.recycle() }
    }

    private fun unreadablePng(valid:ByteArray):ByteArray {
        val damaged=valid.copyOf()
        var offset=8
        var changed=false
        while(offset+12<=damaged.size) {
            val length=ByteBuffer.wrap(damaged,offset,4).int
            check(length>=0 && offset+12L+length<=damaged.size)
            if(String(damaged,offset+4,4,Charsets.US_ASCII)=="IDAT") {
                damaged.fill(0,offset+8,offset+8+length)
                val crc=CRC32().apply { update(damaged,offset+4,length+4) }
                ByteBuffer.wrap(damaged).putInt(offset+8+length,crc.value.toInt())
                changed=true
            }
            offset+=12+length
        }
        check(changed)
        return damaged
    }
}
