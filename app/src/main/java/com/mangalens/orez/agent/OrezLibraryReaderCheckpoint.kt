package com.mangalens.orez.agent

import android.content.Context
import android.system.Os
import com.mangalens.core.reader.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Passive page/position saves preserve the current committed Library metadata, never a cached bookmark. */
internal object OrezLibraryReaderCheckpoint {
    suspend fun save(context: Context,incoming: SavedChapter): SavedChapter = checkpoint(context,incoming,true)
    /** Passive current-metadata read and exact encoded-byte budget only; no stage or journal write. */
    suspend fun preflight(context: Context,incoming: SavedChapter,successors: List<ChapterAcquisitionBudgetPage>) {
        checkpoint(context,incoming,false,successors)
    }
    private suspend fun checkpoint(context: Context,incoming: SavedChapter,persist: Boolean,
        successors: List<ChapterAcquisitionBudgetPage> = emptyList()): SavedChapter {
        val files = context.applicationContext.filesDir
        require(incoming.id.matches(Regex("[a-f0-9]{32}")))
        return OrezLibraryOwnedIo.readerCheckpoint(files) { access ->
            ChapterLibrary.readNativeMetadata {
                val file = File(files,"chapter_library/${incoming.id}.json")
                val exists = Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS) || Files.exists(File(file.path+".bak").toPath(),LinkOption.NOFOLLOW_LINKS)
                val saved = if (exists) {
                    val read = access.readLibrary(file,2_000_000)
                    val current = OrezLibraryMetadata.decode(incoming.id,read.bytes)
                    require(JSONObject(read.bytes.toString(Charsets.UTF_8)).optString("sourceUrl") == incoming.sourceUrl) { "The Reader source changed before its reading checkpoint." }
                    incoming.copy(bookmarked=current.bookmarked,readingStatus=ReadingStatus.valueOf(current.readingStatus),seriesTitle=current.series,
                        notes=current.notes,collections=current.collections,addedAt=current.addedAt,lastReadAt=maxOf(incoming.lastReadAt,current.lastReadAt),nativeLibraryOperation=current.operation)
                } else incoming
                val io = object : ChapterLibraryJournalIo {
                    override fun read(file: File) = ByteArrayInputStream(access.readLibrary(file,2_000_000).bytes)
                    override fun write(file: File,bytes: ByteArray) {
                        require(file.absoluteFile == File(files,"chapter_library/${incoming.id}.json").absoluteFile)
                        val prepared = access.prepare("reader",bytes)
                        try { access.publicationReady(); require(prepared.stamp.isCurrent()); Os.rename(prepared.file.path,file.path) }
                        finally { access.cleanup(prepared.file) }
                    }
                    override fun delete(file: File) { error("A reading checkpoint cannot remove Library journals or original pages.") }
                }
                access.publicationReady()
                val library = ChapterLibrary(files,io)
                if (persist) library.save(saved) else library.checkMetadataBudget(saved,successors)
                saved
            }
        }
    }
}
