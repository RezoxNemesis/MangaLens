package com.mangalens.core.translation

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

internal data class DecodedOriginalMangaPage(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int)

/** Actual file bounds accompany the bounded bitmap; no viewport dimensions enter source proof. */
internal object OriginalMangaPageDecoder {
    fun decode(context: Context, file: File): DecodedOriginalMangaPage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 100_000_000L) return null
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo().also { activity?.getMemoryInfo(it) }
        val budget = if (memory.lowMemory || (activity != null && memory.availMem < 96L * 1024 * 1024)) 1_500_000L
            else if ((activity?.memoryClass ?: 192) <= 128) 6_000_000L else 12_000_000L
        var sample = 1
        while (bounds.outWidth / sample > 2400 || (bounds.outWidth.toLong() / sample) * (bounds.outHeight / sample) > budget) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = true
        }) ?: return null
        val mutable = if (decoded.isMutable) decoded else try { decoded.copy(Bitmap.Config.ARGB_8888, true) } finally { decoded.recycle() }
        return mutable?.let { DecodedOriginalMangaPage(it, bounds.outWidth, bounds.outHeight) }
    }
}
