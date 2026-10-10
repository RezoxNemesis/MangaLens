package com.mangalens.core.translation

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections

internal data class NativeIndexFileSpec(val pageIndex: Int, val source: Boolean, val file: File,
    val expectedSha256: String, val maximumBytes: Long, val needsDimensions: Boolean)
internal class NativeIndexWarmPlan internal constructor(val task: ChapterTranslationTask, files: List<NativeIndexFileSpec>) {
    val files: List<NativeIndexFileSpec> = Collections.unmodifiableList(files.toList())
}
internal data class NativeIndexVerifiedFile(val spec: NativeIndexFileSpec, val stamp: NativeMemoryFileStamp,
    val sha256: String, val dimensions: Pair<Int, Int>?)
/** Process-local read proof. Never decoded from an index, model response or navigation argument. */
internal class NativeIndexWarmReceipt internal constructor(val task: ChapterTranslationTask, files: List<NativeIndexVerifiedFile>) {
    val files: List<NativeIndexVerifiedFile> = Collections.unmodifiableList(files.toList())
    fun isCurrent(): Boolean = files.all { nativeIndexFileCurrent(it.stamp) }
    fun pageProof(pageIndex: Int): NativeMemoryPageProof? {
        val page = task.pages.singleOrNull { it.index == pageIndex } ?: return null
        val source = files.singleOrNull { it.spec.pageIndex == pageIndex && it.spec.source } ?: return null
        val output = files.singleOrNull { it.spec.pageIndex == pageIndex && !it.spec.source } ?: return null
        return NativeMemoryPageProof(task, page, source.stamp, output.stamp)
    }
}
internal fun nativeIndexStamp(file: File): NativeMemoryFileStamp {
    val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    require(attrs.isRegularFile && !attrs.isSymbolicLink && attrs.fileKey() != null)
    return NativeMemoryFileStamp(file.canonicalPath, attrs.fileKey(), attrs.size(), attrs.lastModifiedTime())
}
internal fun nativeIndexFileCurrent(stamp: NativeMemoryFileStamp): Boolean = runCatching {
    nativeIndexStamp(File(stamp.path)) == stamp
}.getOrDefault(false)
