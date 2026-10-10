package com.mangalens.core.translation.inpainting

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.translation.memory.MemoryRegionBounds
import com.mangalens.core.translation.memory.MemorySourceProof
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Authored UNRUN real Android original-FD/decode controls, without model/OCR or reconstruction qualification. */
@RunWith(AndroidJUnit4::class)
class LaMaOriginalPixelsInstrumentedTest {
    @Test fun separatelyDecodedOpaqueOriginalKeepsActualCropPixelsAndClosesItsNativeDecoder() = runBlocking {
        Fixture().use { f ->
            val original = requireNotNull(LaMaOriginalPixels.open(f.root, f.proof) {})
            try {
                assertEquals(65, original.bitmap.width); assertEquals(97, original.bitmap.height)
                assertEquals(0xff102030.toInt(), original.bitmap.getPixel(0, 0))
                assertEquals(0xff405060.toInt(), original.bitmap.getPixel(32, 48))
                assertTrue(original.isCurrent()); assertArrayEquals(f.before, f.file.readBytes())
            } finally { original.close() }
            assertTrue(original.bitmap.isRecycled); assertFalse(original.isCurrent()); original.close()
        }
    }
    @Test fun sameBytesAtomicReplacementCannotRevalidateTheHeldOriginalInode() = runBlocking {
        Fixture().use { f ->
            val original = requireNotNull(LaMaOriginalPixels.open(f.root, f.proof) {})
            try {
                val replacement = File(f.root, "replacement.png"); FileOutputStream(replacement).use { it.write(f.before); it.fd.sync() }
                Files.move(replacement.toPath(), f.file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                assertFalse(original.isCurrent()); assertArrayEquals(f.before, f.file.readBytes())
            } finally { original.close() }
        }
    }
    @Test fun sampledInputUsesRealRoundedBitmapDimensionsRatherThanAssumingDivisionIsExact() = runBlocking {
        Fixture(1025, 513).use { f ->
            val original = requireNotNull(LaMaOriginalPixels.open(f.root, f.proof) {})
            try {
                assertTrue(original.bitmap.width in 256..257); assertTrue(original.bitmap.height in 128..129)
                assertEquals(com.mangalens.core.translation.MangaWritableRect(0, 0, original.bitmap.width, original.bitmap.height), original.sampled(f.proof.bounds))
                assertTrue(original.isCurrent()); assertArrayEquals(f.before, f.file.readBytes())
            } finally { original.close() }
        }
    }
    private class Fixture(width: Int = 65, height: Int = 97) : AutoCloseable {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "lama-original-${UUID.randomUUID()}").apply { check(mkdirs()) }.canonicalFile
        val file = File(root, "original.png")
        val proof: MemorySourceProof; val before: ByteArray
        init {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xff102030.toInt()); bitmap.setPixel(width / 2, height / 2, 0xff405060.toInt())
                FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() }
            } finally { bitmap.recycle() }
            before = file.readBytes()
            proof = MemorySourceProof("chapter-fixture", 0, file.canonicalPath,
                MessageDigest.getInstance("SHA-256").digest(before).joinToString("") { "%02x".format(it) }, width, height, MemoryRegionBounds(0, 0, width, height))
        }
        override fun close() { root.deleteRecursively() }
    }
}
