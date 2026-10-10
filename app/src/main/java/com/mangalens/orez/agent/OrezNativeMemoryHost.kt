package com.mangalens.orez.agent

import android.content.Context
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.translation.*
import com.mangalens.core.translation.memory.SeriesMemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only: neither refreshing native completion nor indexing/modifying a personal journal. */
class OrezNativeMemoryHost(private val context: Context) : OrezMemoryHost {
    private suspend fun service() = withContext(Dispatchers.IO) {
        val library = ChapterLibrary(context)
        val native = ChapterTranslationStore.shared(context)
        val memory = SeriesMemoryStore(context.filesDir)
        NativeSavedTextSearchService(native, memory) { id ->
            library.list().singleOrNull { it.id == id }?.let { chapter ->
                SavedTextChapterScope.inspect(chapter, java.io.File(context.filesDir, "chapters"),
                    java.io.File(context.filesDir, "chapter_library/$id.json"))
            }
        }
    }

    override suspend fun search(chapterId: String, query: String, limit: Int): OrezMemorySearchSnapshot? =
        service().search(chapterId, query, limit)?.let { found -> OrezMemorySearchSnapshot(found.chapterId,
            found.sourceFingerprint, found.associationRevision, found.rows.map { it.hit }, found.incomplete) }

    internal suspend fun searchDetailed(chapterId: String, query: String): SavedTextSearchSnapshot? = service().search(chapterId, query)

    internal suspend fun prepareOpen(snapshot: SavedTextSearchSnapshot, rowId: String): PreparedSavedTextOpen? =
        service().prepareOpen(snapshot, rowId)
}
