package com.mangalens.orez.agent

import android.content.Context
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.translation.memory.SeriesMemoryCodec
import kotlinx.coroutines.CancellationException
import java.io.File

/** New explicit user acceptance only. Resume and native execution never recapture ambient scope. */
internal object OrezLibraryCapture {
    suspend fun capture(context: Context, input: String, selectedId: String?): OrezLibraryScope? {
        val request = OrezLibraryRequest.parseExplicit(input) ?: return null
        if (request.operation.selected) require(selectedId?.matches(Regex("[a-f0-9]{32}")) == true) {
            "Open one saved chapter before requesting its metadata, bookmark or linked series glossary."
        }
        val files = context.applicationContext.filesDir
        return OrezLibraryOwnedIo.run(files) { access ->
            val directory = File(files,"chapter_library")
            val found = if (request.operation.selected) listOf(requireNotNull(selectedId)) to false else access.inventory(directory)
            var incomplete = found.second; val revisions = ArrayList<OrezLibraryManifestRevision>()
            for (id in found.first) {
                access.checkpoint()
                if (access.remainingBytes <= 0) { incomplete = true; break }
                try {
                    val read = ChapterLibrary.readNativeMetadata { access.readLibrary(File(directory,"$id.json"),2_000_000) }
                    val entry = OrezLibraryMetadata.decode(id,read.bytes)
                    revisions += OrezLibraryManifestRevision(id,read.sha256,read.bytes.size.toLong(),entry.sourceTopologySha256)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    access.publicationReady() // A failed real close cannot be treated as an omitted result.
                    if (request.operation.selected) throw failure
                    incomplete = true
                }
            }
            val series = if (request.operation in setOf(OrezLibraryOperation.READ_SERIES,OrezLibraryOperation.SET_TERM)) {
                val chapterRead = access.readMemory(File(files,"reader_memory/chapters/$selectedId.json"),2_097_152)
                val chapter = SeriesMemoryCodec.readChapter(chapterRead.bytes)
                require(chapter.chapterId == selectedId && !chapter.removed)
                val link = requireNotNull(chapter.association) { "Link this saved chapter to an existing series in Library before using its glossary." }
                link.validate()
                val profileRead = access.readMemory(File(files,"reader_memory/series/${link.seriesId}.json"),1_048_576)
                val profile = SeriesMemoryCodec.readProfile(profileRead.bytes)
                require(profile.id == link.seriesId && !profile.removed && chapterRead.stamp.isCurrent() && profileRead.stamp.isCurrent())
                OrezLibrarySeriesRevision(link.seriesId,chapter.associationRevision,chapterRead.sha256,profileRead.sha256)
            } else null
            access.publicationReady()
            OrezLibraryScope.capture(request,selectedId.takeIf { request.operation.selected },revisions,incomplete,series)
        }
    }
}
