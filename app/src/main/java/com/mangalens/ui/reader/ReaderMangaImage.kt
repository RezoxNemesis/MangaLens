package com.mangalens.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.ImageRequest
import coil.request.Options
import coil.size.Precision
import coil.size.Scale
import com.mangalens.core.reader.MangaImagePolicy
import com.mangalens.core.translation.ChapterTranslationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor

internal data class MangaImageSource(val path: String, val size: MangaImagePolicy.Dimensions, val bytes: Long,
    val modified: Long, val regionCapable: Boolean, val contentSha256: String? = null)
internal data class MangaImageTile(val source: MangaImageSource, val region: MangaImagePolicy.Region, val width: Int)
private data class MangaImageViewport(val width: Int, val height: Int, val firstRow: Int, val lastRow: Int)
private data class MangaSourceRead(val source: MangaImageSource? = null, val complete: Boolean = false)

/** A region never retains a decoder or bitmap outside Coil's ordinary image ownership. */
private class MangaRegionFetcher(private val tile: MangaImageTile, private val context: Context) : Fetcher {
    override suspend fun fetch(): DrawableResult {
        currentCoroutineContext().ensureActive()
        check(tile.source.regionCapable) { "This image format needs the bounded software decoder." }
        val decoder = BitmapRegionDecoder.newInstance(tile.source.path, false)
            ?: error("Manga page could not be decoded.")
        var bitmap: Bitmap? = null
        try {
            check(decoder.width == tile.source.size.width && decoder.height == tile.source.size.height) {
                "Manga page geometry changed while loading."
            }
            val sample = MangaImagePolicy.sampleSize(decoder.width, tile.region.height, tile.width)
            bitmap = decoder.decodeRegion(Rect(0, tile.region.top, decoder.width, tile.region.bottom), BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = false
            }) ?: error("Manga page region could not be decoded.")
            check(bitmap.width <= MangaImagePolicy.MAX_DECODE_SIDE && bitmap.height <= MangaImagePolicy.MAX_DECODE_SIDE &&
                bitmap.width.toLong() * bitmap.height <= MangaImagePolicy.MAX_DECODE_PIXELS) { "Manga region exceeds the decode limit." }
            currentCoroutineContext().ensureActive()
            return DrawableResult(BitmapDrawable(context.resources, bitmap), sample > 1, DataSource.DISK).also { bitmap = null }
        } finally {
            bitmap?.recycle()
            decoder.recycle()
        }
    }

    class Factory : Fetcher.Factory<MangaImageTile> {
        override fun create(data: MangaImageTile, options: Options, imageLoader: ImageLoader): Fetcher =
            MangaRegionFetcher(data, options.context)
    }
}

/** Check the actual decoder once; unknown/unsupported formats retain Coil's bounded software path. */
internal fun readMangaImageSource(file: File, checkActive: () -> Unit = {}): MangaImageSource? {
    if (!file.isFile || file.length() !in 1..ChapterTranslationStore.MAX_SURFACE_BYTES) return null
    checkActive()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val size = MangaImagePolicy.dimensions(bounds.outWidth, bounds.outHeight) ?: return null
    val regionCapable = MangaImagePolicy.supportsRegionFormat(bounds.outMimeType) && runCatching {
        val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false) ?: return@runCatching false
        try { decoder.width == size.width && decoder.height == size.height } finally { decoder.recycle() }
    }.getOrDefault(false)
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkActive()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            check(total <= ChapterTranslationStore.MAX_SURFACE_BYTES) { "Reader source exceeded its safe byte limit while loading." }
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it) }
    return MangaImageSource(file.absolutePath, size, file.length(), file.lastModified(), regionCapable, hash)
}

@Composable
private fun rememberMangaSource(model: String?, contentRevision: String? = null): MangaSourceRead {
    var result by remember(model, contentRevision) { mutableStateOf(MangaSourceRead()) }
    LaunchedEffect(model, contentRevision) {
        val source = withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            try {
                model?.let(::File)?.let { readMangaImageSource(it, checkActive = { coroutine.ensureActive() }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
        result = MangaSourceRead(source, complete = true)
    }
    return result
}

internal fun mangaImageRequest(context: Context, model: Any?, width: Int, height: Int,
    contentRevision: String? = null): ImageRequest =
    ImageRequest.Builder(context).data(model)
        .allowHardware(false).bitmapConfig(Bitmap.Config.ARGB_8888).allowRgb565(false)
        .size(width.coerceIn(1, MangaImagePolicy.MAX_DECODE_SIDE), height.coerceIn(1, MangaImagePolicy.MAX_DECODE_SIDE))
        .scale(Scale.FIT).precision(Precision.EXACT)
        .apply {
            if (model is MangaImageTile) {
                fetcherFactory(MangaRegionFetcher.Factory())
                memoryCacheKey("manga-region:${model.source.path}:${model.source.bytes}:${model.source.modified}:" +
                    "${model.region.top}:${model.region.bottom}:${model.width}:${model.source.contentSha256}:$contentRevision")
            } else if (contentRevision != null) {
                val key = "manga-source:$model:$contentRevision"
                memoryCacheKey(key)
                diskCacheKey(key)
            }
        }.build()

/** Decode the top artwork of a tall page, rather than cropping a downscaled complete strip. */
@Composable
internal fun rememberMangaThumbnailRequest(model: String?, contentRevision: String? = null): ImageRequest {
    val context = LocalContext.current
    val source = rememberMangaSource(model, contentRevision).source
    val data = source?.takeIf { it.regionCapable && it.size.height.toLong() > it.size.width.toLong() * 2 }?.let {
        MangaImageTile(it, MangaImagePolicy.coverRegion(it.size), 512)
    } ?: model?.takeUnless { File(it).isAbsolute && source == null }
    val revision = listOfNotNull(contentRevision, source?.contentSha256).joinToString(":").takeIf { it.isNotBlank() }
    return remember(context, data, revision) { mangaImageRequest(context, data, 512, 768, revision) }
}

/**
 * Long local vertical pages use only visible software-decoded regions, with one tile
 * of overscan. The full source aspect and overlay coordinate system are unchanged.
 */
@Composable
internal fun ReaderMangaImage(
    model: String,
    description: String,
    modifier: Modifier,
    contentScale: ContentScale,
    viewportTransform: Any? = null,
    contentRevision: String? = null,
    onLoadError: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sourceRead = rememberMangaSource(model, contentRevision)
    val source = sourceRead.source
    val loadIdentity = remember(model, contentRevision) { Any() }
    val currentLoadIdentity = rememberUpdatedState(loadIdentity)
    val currentErrorHandler = rememberUpdatedState(onLoadError)
    val reportLoadError = remember(loadIdentity) {
        { if (currentLoadIdentity.value === loadIdentity) currentErrorHandler.value() }
    }
    LaunchedEffect(loadIdentity, sourceRead.complete) {
        if (sourceRead.complete && source == null && File(model).isAbsolute) reportLoadError()
    }
    val revision = listOfNotNull(contentRevision, source?.contentSha256).joinToString(":").takeIf { it.isNotBlank() }
    var coordinates by remember(model, contentRevision) { mutableStateOf<LayoutCoordinates?>(null) }
    var viewport by remember(model, contentRevision) { mutableStateOf<MangaImageViewport?>(null) }

    fun updateViewport(value: LayoutCoordinates) {
        if (!value.isAttached || value.size.width <= 0 || value.size.height <= 0) return
        val bounds = value.boundsInWindow()
        val localTop = value.windowToLocal(Offset(bounds.left, bounds.top)).y
        val localBottom = value.windowToLocal(Offset(bounds.right, bounds.bottom)).y
        val imageHeight = source?.size?.height ?: 1
        viewport = MangaImageViewport(value.size.width, value.size.height,
            floor(localTop / value.size.height * imageHeight).toInt().coerceIn(0, imageHeight),
            if (bounds.height <= 0f) 0 else ceil(localBottom / value.size.height * imageHeight).toInt().coerceIn(0, imageHeight))
    }
    LaunchedEffect(coordinates, source, viewportTransform) { coordinates?.let(::updateViewport) }

    val tiled = source?.takeIf { it.regionCapable && contentScale == ContentScale.FillWidth && it.size.height > MangaImagePolicy.MAX_DECODE_SIDE }
    Box(modifier.clipToBounds().onGloballyPositioned { coordinates = it; updateViewport(it) }
        .then(if (tiled != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        // A cold restored page has no runtime token. Obtain its actual fingerprint
        // before consulting Coil, whose path cache may outlive the previous reader.
        if (File(model).isAbsolute && source == null) return@Box
        if (tiled != null) {
            val view = viewport
            val regions = if (view == null) listOf(MangaImagePolicy.Region(0, minOf(tiled.size.height, MangaImagePolicy.MAX_DECODE_SIDE)))
                else MangaImagePolicy.visibleRegions(tiled.size, view.firstRow, view.lastRow)
            for (region in regions) {
                val width = view?.width ?: tiled.size.width.coerceAtMost(MangaImagePolicy.MAX_DECODE_SIDE)
                val tile = MangaImageTile(tiled, region, width)
                val request = remember(context, tile, revision) { mangaImageRequest(context, tile, width, MangaImagePolicy.MAX_DECODE_SIDE, revision) }
                val scale = (view?.height?.toFloat() ?: 0f) / tiled.size.height
                AsyncImage(request, null, Modifier.offset(y = with(density) { (region.top * scale).toDp() })
                    .fillMaxWidth().height(with(density) { (region.height * scale).toDp() }), contentScale = ContentScale.FillBounds,
                    onError = { reportLoadError() })
            }
        } else {
            val request = remember(context, model, revision, viewport?.width, viewport?.height) {
                mangaImageRequest(context, model, viewport?.width ?: MangaImagePolicy.MAX_DECODE_SIDE,
                    viewport?.height ?: MangaImagePolicy.MAX_DECODE_SIDE, revision)
            }
            AsyncImage(request, description, Modifier.fillMaxSize(), contentScale = contentScale,
                onError = { reportLoadError() })
        }
    }
}
