package com.mangalens.core.imports

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Extracts only images with bounded expansion and never trusts archive paths. */
object SafeChapterArchive {
    data class ExtractedEntry(val entryName: String, val file: File)

    fun extract(input: InputStream, directory: File, maxTotal: Long = 512L * 1024 * 1024, maxPage: Long = 40L * 1024 * 1024, maxPages: Int = 1000): List<File> =
        extractEntries(input, directory, maxTotal, maxPage, maxPages).map { it.file }

    fun extractEntries(input: InputStream, directory: File, maxTotal: Long = 512L * 1024 * 1024,
        maxPage: Long = 40L * 1024 * 1024, maxPages: Int = 1000,
        checkActive: () -> Unit = {}): List<ExtractedEntry> {
        directory.mkdirs()
        try {
            return scan(input, maxTotal, maxPage, maxPages, checkActive) { _, index -> File(directory, "page_$index.img") }
                .sortedWith { a, b -> naturalCompare(a.entryName, b.entryName) }
                .map { ExtractedEntry(it.entryName, requireNotNull(it.file)) }
        } catch (failure: Throwable) { directory.deleteRecursively(); throw failure }
    }

    /** Scan the entire archive, but create only the caller-owned selected temporary image. */
    fun extractSelected(input: InputStream, target: File, expectedEntryName: String,
        maxTotal: Long = 512L * 1024 * 1024, maxPage: Long = 40L * 1024 * 1024, maxPages: Int = 1000,
        checkActive: () -> Unit = {}) {
        require(!target.exists()) { "Selected archive output must be a new temporary file" }
        try {
            val selected = scan(input, maxTotal, maxPage, maxPages, checkActive) { name, _ ->
                if (name == expectedEntryName) target else null
            }.singleOrNull { it.entryName == expectedEntryName }
            require(selected != null && target.isFile && target.length() > 0) { "The saved page entry is missing from the original archive" }
        } catch (failure: Throwable) { target.delete(); throw failure }
    }

    private data class ScannedEntry(val entryName: String, val file: File?)

    private fun scan(input: InputStream, maxTotal: Long, maxPage: Long, maxPages: Int,
        checkActive: () -> Unit, outputFor: (String, Int) -> File?): List<ScannedEntry> {
        require(maxTotal > 0 && maxPage > 0 && maxPages > 0)
        val result = mutableListOf<ScannedEntry>()
        val identities = mutableSetOf<String>()
        var total = 0L
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                checkActive()
                val entry = zip.nextEntry ?: break
                require(++entries <= 5000) { "Archive contains too many entries" }
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(name) && name.split('/').none { it == ".." }) { "Unsafe archive path" }
                if (entry.isDirectory) { zip.closeEntry(); continue }
                require(name.substringAfterLast('.').lowercase() !in setOf("zip", "cbz", "rar", "7z")) { "Nested archives are not supported" }
                val image = name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
                if (image) require(result.size < maxPages) { "Chapter has too many pages" }
                if (image) require(identities.add(name)) { "Archive contains duplicate page identities" }
                val output = if (image) outputFor(name, result.size) else null
                var pageBytes = 0L
                val buffer = ByteArray(64 * 1024)
                output?.outputStream().use { stream ->
                    while (true) {
                        checkActive()
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count; pageBytes += count
                        require(total <= maxTotal && pageBytes <= maxPage) { "Archive exceeds safe expansion limit" }
                        stream?.write(buffer, 0, count)
                    }
                }
                if (image && pageBytes > 0) result += ScannedEntry(name, output)
                zip.closeEntry()
            }
        }
        require(result.isNotEmpty()) { "Archive contains no image pages" }
        return result
    }
    internal fun naturalCompare(a: String, b: String): Int {
        val pattern = Regex("\\d+|\\D+")
        val aa = pattern.findAll(a.lowercase()).map { it.value }.toList()
        val bb = pattern.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(aa.size, bb.size)) {
            val x = aa[i]; val y = bb[i]
            val compare = if (x.first().isDigit() && y.first().isDigit()) {
                val xx = x.trimStart('0').ifEmpty { "0" }; val yy = y.trimStart('0').ifEmpty { "0" }
                xx.length.compareTo(yy.length).takeIf { it != 0 } ?: xx.compareTo(yy)
            } else x.compareTo(y)
            if (compare != 0) return compare
        }
        return aa.size.compareTo(bb.size)
    }
}
