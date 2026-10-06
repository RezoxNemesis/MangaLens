package com.mangalens.core.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.mangalens.core.imports.SafeChapterArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.util.UUID

object DocumentImporter {
    val MIME_TYPES = arrayOf("image/*", "application/pdf", "application/zip", "application/x-zip-compressed", "application/vnd.comicbook+zip", "application/x-cbz")
    data class Result(val title: String, val images: List<Uri>, val directory: File) { fun close() { directory.deleteRecursively() } }
    suspend fun prepare(context: Context, uris: List<Uri>): Result = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty() && uris.size <= 1000) { "Select between 1 and 1000 pages" }
        val directory = File(context.cacheDir, "chapter_imports/${UUID.randomUUID()}").apply { mkdirs() }
        val coroutine = currentCoroutineContext()
        fun name(uri: Uri): String = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        fun stream(uri: Uri) = object : FilterInputStream(context.contentResolver.openInputStream(uri) ?: error("Cannot open selected document")) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { coroutine.ensureActive(); return super.read(b, off, len) }
            override fun read(): Int { coroutine.ensureActive(); return super.read() }
        }
        try {
            val selectedName = name(uris.first())
            val extension = selectedName.substringAfterLast('.').lowercase()
            val mime = context.contentResolver.getType(uris.first()).orEmpty()
            val archive = extension in setOf("zip", "cbz") || mime.contains("zip") || mime.contains("cbz")
            val pdf = extension == "pdf" || mime == "application/pdf"
            require(uris.size == 1 || (!archive && !pdf)) { "Select one archive or PDF at a time" }
            val pages = when {
                archive -> stream(uris.first()).use { SafeChapterArchive.extract(it, File(directory, "pages")) }.map { Uri.fromFile(it) }
                pdf -> {
                    val source = File(directory, "source.pdf")
                    stream(uris.first()).use { input -> source.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024); var total = 0L
                        while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 256L * 1024 * 1024) { "PDF is too large" }; out.write(buffer, 0, n) }
                    } }
                    var renderedBytes = 0L
                    ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer ->
                        require(renderer.pageCount in 1..300) { "PDF must contain 1–300 pages" }
                        (0 until renderer.pageCount).map { index ->
                            coroutine.ensureActive()
                            val image = File(directory, "pdf_${index}.png")
                            renderer.openPage(index).use { page ->
                                require(page.width > 0 && page.height > 0) { "Invalid PDF page" }
                                val factor = minOf(3.25f, 3000f / maxOf(page.width, page.height))
                                val bitmap = Bitmap.createBitmap(
                                    (page.width * factor).toInt().coerceAtLeast(1),
                                    (page.height * factor).toInt().coerceAtLeast(1),
                                    Bitmap.Config.ARGB_8888
                                )
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                    image.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                                }
                                finally { bitmap.recycle() }
                            }
                            renderedBytes += image.length(); require(renderedBytes <= 512L * 1024 * 1024) { "Rendered PDF exceeds chapter limit" }
                            Uri.fromFile(image)
                        }
                    } }
                }
                else -> uris
            }
            Result(if (uris.size == 1) selectedName.substringBeforeLast('.').ifBlank { "Imported chapter" } else "Imported chapter (${uris.size} pages)", pages, directory)
        } catch (failure: Throwable) { directory.deleteRecursively(); throw failure }
    }
}
