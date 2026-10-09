package com.mangalens.orez.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

data class OrezChapterSource(val index: Int, val path: String?, val sha256: String? = null)

/** Reads only explicit managed chapter files. There is no Gallery, arbitrary path, or network tool. */
object OrezChapterSourceEvidence {
    private const val MAX_SOURCE_BYTES = 40L * 1024 * 1024
    private const val MAX_CHAPTER_BYTES = 1024L * 1024 * 1024

    suspend fun inspect(directory: File, chapterId: String, title: String, pages: List<OrezChapterSource>): OrezChapterSnapshot {
        require(pages.size in 1..1000 && pages.map { it.index }.distinct().size == pages.size) { "Invalid saved chapter page scope." }
        val managed = directory.canonicalFile
        var totalBytes = 0L
        val checked = pages.map { page ->
            currentCoroutineContext().ensureActive()
            val source = File(requireNotNull(page.path) { "A saved page is missing. Retry it in Reader first." }).canonicalFile
            require(source.parentFile == managed && source.name.length <= 240) { "Chapter source is outside managed storage." }
            require(source.isFile && source.length() in 1..MAX_SOURCE_BYTES) { "A saved page is missing, empty, or too large." }
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count; totalBytes += count
                    require(bytes <= MAX_SOURCE_BYTES && totalBytes <= MAX_CHAPTER_BYTES) { "Chapter exceeds the bounded local workflow budget." }
                    digest.update(buffer, 0, count)
                }
            }
            require(bytes > 0) { "Saved source became empty during inspection." }
            page.copy(path = source.absolutePath, sha256 = digest.digest().hex())
        }
        return snapshot(chapterId, title, checked)
    }

    /** Native refresh has already checked source and saved surface hashes before this is used. */
    fun snapshot(chapterId: String, title: String, checked: List<OrezChapterSource>): OrezChapterSnapshot {
        require(chapterId.matches(Regex("[a-f0-9]{32}")) && checked.size in 1..1000)
        require(checked.map { it.index }.distinct().size == checked.size && checked.all {
            it.index >= 0 && it.path != null && it.sha256?.matches(Regex("[a-f0-9]{64}")) == true
        }) { "Native source receipt is incomplete." }
        val digest = MessageDigest.getInstance("SHA-256")
        checked.sortedBy { it.index }.forEach { page ->
            digest.update("${page.index}\u0000${File(page.path!!).name}\u0000${page.sha256}\n".toByteArray(Charsets.UTF_8))
        }
        return OrezChapterSnapshot(chapterId, title.take(250), checked.size, digest.digest().hex())
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
