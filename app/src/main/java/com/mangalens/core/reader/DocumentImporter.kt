package com.mangalens.core.reader

import android.content.Context
import android.content.Intent
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
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

object DocumentImporter {
    val MIME_TYPES = arrayOf("image/*", "application/pdf", "application/zip", "application/x-zip-compressed", "application/vnd.comicbook+zip", "application/x-cbz")
    data class Result(val title: String, val images: List<Uri>, val directory: File,
        val documentSources: List<ChapterDocumentSource?> = emptyList()) {
        fun close() { directory.deleteRecursively() }
    }

    suspend fun prepare(context: Context, uris: List<Uri>): Result = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty() && uris.size <= 1000) { "Select between 1 and 1000 pages" }
        val directory = File(context.cacheDir, "chapter_imports/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val coroutine = currentCoroutineContext()
        try {
            // OpenDocument may offer a durable read grant. Record only the actual observed grant;
            // providers without it still allow importing the selected bytes for offline reading.
            uris.filter { it.scheme == "content" }.forEach { uri ->
                coroutine.ensureActive()
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            val selectedName = documentName(context, uris.first())
            val extension = selectedName.substringAfterLast('.').lowercase()
            val mime = context.contentResolver.getType(uris.first()).orEmpty()
            val archive = extension in setOf("zip", "cbz") || mime.contains("zip") || mime.contains("cbz")
            val pdf = extension == "pdf" || mime == "application/pdf"
            require(uris.size == 1 || (!archive && !pdf)) { "Select one archive or PDF at a time" }
            var provenance = emptyList<ChapterDocumentSource?>()
            val pages = when {
                archive -> {
                    val source = File(directory, "source.zip")
                    val hash = copyDocument(context, uris.first(), source)
                    val entries = source.inputStream().use { SafeChapterArchive.extractEntries(it, File(directory, "pages"),
                        checkActive = { coroutine.ensureActive() }) }
                    val persisted = hasPersistedRead(context, uris.first())
                    provenance = entries.mapIndexed { index, entry -> ChapterDocumentSource(uris.first().toString(),
                        "archive", index, hash, entry.entryName, persisted).validate() }
                    entries.map { Uri.fromFile(it.file) }
                }
                pdf -> {
                    val source = File(directory, "source.pdf")
                    val hash = copyDocument(context, uris.first(), source)
                    val persisted = hasPersistedRead(context, uris.first())
                    var renderedBytes = 0L
                    ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            require(renderer.pageCount in 1..300) { "PDF must contain 1–300 pages" }
                            provenance = (0 until renderer.pageCount).map { ChapterDocumentSource(uris.first().toString(),
                                "pdf", it, hash, persistedReadPermission = persisted).validate() }
                            (0 until renderer.pageCount).map { index ->
                                coroutine.ensureActive()
                                val image = File(directory, "pdf_${index}.png")
                                renderPdfPage(renderer, index, image)
                                renderedBytes += image.length()
                                require(renderedBytes <= 512L * 1024 * 1024) { "Rendered PDF exceeds chapter limit" }
                                Uri.fromFile(image)
                            }
                        }
                    }
                }
                else -> uris
            }
            Result(if (uris.size == 1) selectedName.substringBeforeLast('.').ifBlank { "Imported chapter" }
                else "Imported chapter (${uris.size} pages)", pages, directory, provenance)
        } catch (failure: Throwable) { directory.deleteRecursively(); throw failure }
    }

    /** Recreate just the selected page from the exact original selected document. */
    suspend fun reacquirePage(context: Context, provenance: ChapterDocumentSource, target: File) = withContext(Dispatchers.IO) {
        provenance.validate()
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        val uri = Uri.parse(provenance.uri)
        check(!provenance.persistedReadPermission || hasPersistedRead(context, uri)) {
            "Read access to the original document has expired. Select that original again from Files."
        }
        val directory = File(context.cacheDir, "chapter_repairs/${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val source = File(directory, "source.document")
            check(copyDocument(context, uri, source) == provenance.documentSha256) {
                "The original document has changed. Import it again as a new chapter before replacing this page."
            }
            coroutine.ensureActive()
            if (provenance.kind == "archive") {
                source.inputStream().use { SafeChapterArchive.extractSelected(it, target,
                    requireNotNull(provenance.archiveEntryName), checkActive = { coroutine.ensureActive() }) }
            } else {
                ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        require(renderer.pageCount in 1..300 && provenance.pageIndex < renderer.pageCount) { "The saved PDF page is missing" }
                        renderPdfPage(renderer, provenance.pageIndex, target)
                    }
                }
            }
            coroutine.ensureActive()
        } finally { directory.deleteRecursively() }
    }

    private fun documentName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun hasPersistedRead(context: Context, uri: Uri): Boolean = uri.scheme == "content" && runCatching {
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }.getOrDefault(false)

    private suspend fun copyDocument(context: Context, uri: Uri, target: File): String {
        val coroutine = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        val selected = context.contentResolver.openInputStream(uri) ?: error("Cannot reopen the selected original document. Choose it again from Files.")
        DigestInputStream(selected, digest).use { input ->
            target.outputStream().use { output ->
                BoundedTransfer.copy(input, output, 256L * 1024 * 1024, checkActive = { coroutine.ensureActive() })
            }
        }
        coroutine.ensureActive()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun renderPdfPage(renderer: PdfRenderer, index: Int, image: File) {
        renderer.openPage(index).use { page ->
            require(page.width > 0 && page.height > 0) { "Invalid PDF page" }
            val factor = minOf(3.25f, 3000f / maxOf(page.width, page.height))
            val bitmap = Bitmap.createBitmap((page.width * factor).toInt().coerceAtLeast(1),
                (page.height * factor).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                image.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }
}
