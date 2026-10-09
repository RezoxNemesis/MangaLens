package com.mangalens

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.decode.DataSource
import coil.request.SuccessResult
import com.mangalens.ui.reader.mangaImageRequest
import com.mangalens.ui.reader.readMangaImageSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** A stable managed path cannot cause Coil to reuse pixels from an older source incarnation. */
@RunWith(AndroidJUnit4::class)
class ReaderSourceCacheReloadTest {
    @Test fun aColdReopenDerivesTheNewPixelsFingerprintAtTheSamePathAndModificationTime() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "reader-cache-revision-${UUID.randomUUID()}.png")
        val loader = ImageLoader(context)
        try {
            withTimeout(20_000L) {
                writeImage(file, Color.RED)
                val originalModified = file.lastModified()
                val originalHash = requireNotNull(readMangaImageSource(file)?.contentSha256)
                val oldRequest = mangaImageRequest(context, file.absolutePath, 32, 48, originalHash)
                val first = loader.execute(oldRequest) as? SuccessResult ?: error("Original fixture did not decode")
                assertEquals(Color.RED, (first.drawable as BitmapDrawable).bitmap.getPixel(16, 24))
                val cached = loader.execute(oldRequest) as? SuccessResult ?: error("Fixture did not enter the real memory cache")
                assertEquals("The precondition requires actual cached pixels", DataSource.MEMORY_CACHE, cached.dataSource)

                writeImage(file, Color.BLUE)
                check(file.setLastModified(originalModified))
                val replacementHash = requireNotNull(readMangaImageSource(file)?.contentSha256)
                assertNotEquals("Cold presentation must hash current bytes rather than trust file metadata", originalHash, replacementHash)
                val newRequest = mangaImageRequest(context, file.absolutePath, 32, 48, replacementHash)
                assertNotEquals(oldRequest.memoryCacheKey, newRequest.memoryCacheKey)
                assertNotEquals(oldRequest.diskCacheKey, newRequest.diskCacheKey)
                val repaired = loader.execute(newRequest) as? SuccessResult ?: error("Verified replacement did not decode")
                assertEquals("The repaired source must show its actual replacement pixels", Color.BLUE,
                    (repaired.drawable as BitmapDrawable).bitmap.getPixel(16, 24))
            }
        } finally { loader.shutdown(); file.delete() }
    }

    private fun writeImage(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
