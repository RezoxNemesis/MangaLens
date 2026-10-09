package com.mangalens

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.request.SuccessResult
import com.mangalens.core.reader.MangaImagePolicy
import com.mangalens.ui.reader.MangaImageTile
import com.mangalens.ui.reader.mangaImageRequest
import com.mangalens.ui.reader.readMangaImageSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android/Coil decodes. No public provider, GPU-limit, or literary-quality claim. */
@RunWith(AndroidJUnit4::class)
class MangaImageDecodeTest {
    @Test fun tallGifUsesBoundedSoftwareFallbackInsteadOfAnUnsupportedRegionRequest() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "tall-gif-${UUID.randomUUID()}.gif")
        instrumentation.context.assets.open("tall-static-page.gif").use { input -> file.outputStream().use(input::copyTo) }
        val loader = ImageLoader(context)
        val report = JSONObject().put("case", "real imported GIF fallback")
        try {
            withTimeout(30_000L) {
                val source = readMangaImageSource(file)!!
                assertEquals(720, source.size.width)
                assertEquals(8940, source.size.height)
                assertFalse(source.regionCapable)
                val native = runCatching { BitmapRegionDecoder.newInstance(file.absolutePath, false) }.getOrNull()
                report.put("native_region_supported", native != null)
                native?.recycle()
                assertNull("GIF unexpectedly reached the native region path", native)
                val request = mangaImageRequest(context, file.absolutePath, 720, 8940)
                assertFalse(request.allowHardware)
                assertEquals(Bitmap.Config.ARGB_8888, request.bitmapConfig)
                val result = loader.execute(request)
                assertTrue("Bounded GIF fallback failed: $result", result is SuccessResult)
                val bitmap = (result.drawable as BitmapDrawable).bitmap
                assertSoftwareBounds(bitmap)
                report.put("decoded_width", bitmap.width).put("decoded_height", bitmap.height).put("config", bitmap.config.toString())
                assertEquals(source.size, readMangaImageSource(file)!!.size)
            }
            report.put("status", "passed")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.toString())
            throw failure
        } finally {
            loader.shutdown()
            file.delete()
            File(context.getExternalFilesDir(null), "qa/manga-image/tall-gif.json").apply { parentFile!!.mkdirs() }.writeText(report.toString(2))
        }
    }

    @Test fun longStaticPageDecodesOnlyTheRequestedOriginalCoordinateRegionAsSoftware() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "tall-page-${UUID.randomUUID()}.png")
        val original = Bitmap.createBitmap(720, 8940, Bitmap.Config.ARGB_8888)
        try {
            Canvas(original).apply {
                drawColor(Color.RED)
                val paint = android.graphics.Paint().apply { color = Color.GREEN }
                drawRect(0f, 4096f, 720f, 6144f, paint)
            }
            file.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { original.recycle() }
        val loader = ImageLoader(context)
        val report = JSONObject().put("case", "real native-width software region")
        try {
            withTimeout(30_000L) {
                val source = readMangaImageSource(file)!!
                assertTrue(source.regionCapable)
                val tile = MangaImageTile(source, MangaImagePolicy.Region(4096, 6144), 720)
                val result = loader.execute(mangaImageRequest(context, tile, 720, 2048))
                assertTrue("Software region failed: $result", result is SuccessResult)
                val bitmap = (result.drawable as BitmapDrawable).bitmap
                assertSoftwareBounds(bitmap)
                assertEquals(720, bitmap.width)
                assertEquals(2048, bitmap.height)
                assertEquals(Color.GREEN, bitmap.getPixel(360, 1024))
                assertEquals(source.size, readMangaImageSource(file)!!.size)
                report.put("decoded_width", bitmap.width).put("decoded_height", bitmap.height).put("config", bitmap.config.toString())
            }
            report.put("status", "passed")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.toString())
            throw failure
        } finally {
            loader.shutdown()
            file.delete()
            File(context.getExternalFilesDir(null), "qa/manga-image/static-region.json").apply { parentFile!!.mkdirs() }.writeText(report.toString(2))
        }
    }

    private fun assertSoftwareBounds(bitmap: Bitmap) {
        assertNotEquals(Bitmap.Config.HARDWARE, bitmap.config)
        assertTrue(bitmap.width <= MangaImagePolicy.MAX_DECODE_SIDE)
        assertTrue(bitmap.height <= MangaImagePolicy.MAX_DECODE_SIDE)
        assertTrue(bitmap.width.toLong() * bitmap.height <= MangaImagePolicy.MAX_DECODE_PIXELS)
    }
}
