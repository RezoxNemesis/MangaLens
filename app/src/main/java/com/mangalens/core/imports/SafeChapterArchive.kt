package com.mangalens.core.imports

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Extracts only images with bounded expansion and never trusts archive paths. */
object SafeChapterArchive {
    fun extract(input: InputStream, directory: File, maxTotal: Long = 512L * 1024 * 1024, maxPage: Long = 40L * 1024 * 1024, maxPages: Int = 1000): List<File> {
        directory.mkdirs()
        val result = mutableListOf<Pair<String, File>>()
        var total = 0L
        var entries = 0
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 5000) { "Archive contains too many entries" }
                    val name = entry.name.replace('\\', '/')
                    require(!name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(name) && name.split('/').none { it == ".." }) { "Unsafe archive path" }
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    require(name.substringAfterLast('.').lowercase() !in setOf("zip", "cbz", "rar", "7z")) { "Nested archives are not supported" }
                    val image = name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
                    if (image) require(result.size < maxPages) { "Chapter has too many pages" }
                    val output = if (image) File(directory, "page_${result.size}.img") else null
                    var pageBytes = 0L
                    val buffer = ByteArray(64 * 1024)
                    output?.outputStream().use { stream ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count; pageBytes += count
                            require(total <= maxTotal && pageBytes <= maxPage) { "Archive exceeds safe expansion limit" }
                            stream?.write(buffer, 0, count)
                        }
                    }
                    if (output != null && output.length() > 0) result += name to output
                    zip.closeEntry()
                }
            }
            require(result.isNotEmpty()) { "Archive contains no image pages" }
            val comparator = Comparator<Pair<String, File>> { a, b -> naturalCompare(a.first, b.first) }
            return result.sortedWith(comparator).map { it.second }
        } catch (failure: Throwable) { directory.deleteRecursively(); throw failure }
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
