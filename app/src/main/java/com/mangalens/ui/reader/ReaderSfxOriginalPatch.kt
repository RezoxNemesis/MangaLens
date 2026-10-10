package com.mangalens.ui.reader

import com.mangalens.core.translation.PersonalReaderRegion
import com.mangalens.core.translation.memory.MemoryRegionBounds

/** The held original crop is owned by the composed overlay; no generated image is written. */
internal class ReaderSfxOriginalPatch(val crop: ReaderBubbleOriginalCrop,
    val nativeBounds: android.graphics.Rect, val imageWidth: Int, val imageHeight: Int,
    val originalWidth: Int, val originalHeight: Int, val originalBounds: MemoryRegionBounds,
    private val current: () -> Boolean) : AutoCloseable {
    fun isCurrent(): Boolean = current()
    override fun close() = crop.close()
}
internal fun interface ReaderSfxRestorationGateway {
    suspend fun open(region: PersonalReaderRegion): ReaderSfxOriginalPatch?
    /** Metadata visibility only; crop/draw authority still comes from the independently verified patch. */
    fun isCurrent(region: PersonalReaderRegion): Boolean = false
}
internal val LocalReaderSfxRestoration = androidx.compose.runtime.staticCompositionLocalOf<ReaderSfxRestorationGateway?> { null }
