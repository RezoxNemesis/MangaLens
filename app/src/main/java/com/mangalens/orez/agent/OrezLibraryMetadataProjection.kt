package com.mangalens.orez.agent

import android.content.Context
import com.mangalens.core.reader.ChapterLibrary
import java.io.File

/** Fresh bounded metadata on IO. Delivery has one explicit NOFOLLOW metadata-stat exception. */
internal class OrezLibraryMetadataProjection(private val entry: OrezLibraryMetadataEntry, private val stamp: OrezLibraryOwnedIo.Stamp) {
    val chapterId get() = entry.chapterId
    val bookmarked get() = entry.bookmarked
    /** Returns false immediately if a save owns the gate; no fsync/read/decode can block Main here. */
    fun deliver(accept: (Boolean) -> Boolean): Boolean = try {
        ChapterLibrary.publishNativeMetadata {
            if (!stamp.isCurrent()) false else accept(entry.bookmarked)
        }
    } catch (_: com.mangalens.core.reader.NativeLibraryMetadataBusyException) { false }
    companion object {
        suspend fun read(context: Context,id: String): OrezLibraryMetadataProjection {
            require(id.matches(Regex("[a-f0-9]{32}")))
            val files = context.applicationContext.filesDir.canonicalFile
            return OrezLibraryOwnedIo.project(files) { access ->
                val read = ChapterLibrary.readNativeMetadata { access.readLibrary(File(files,"chapter_library/$id.json"),2_000_000) }
                val entry = OrezLibraryMetadata.decode(id,read.bytes)
                access.publicationReady(); OrezLibraryMetadataProjection(entry,read.stamp)
            }
        }
    }
}
