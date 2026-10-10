package com.mangalens.widget

import android.content.Context
import android.util.AtomicFile
import androidx.sqlite.db.SimpleSQLiteQuery
import com.mangalens.core.reader.ChapterCbzResources
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.reader.ChapterLibraryJournalIo
import com.mangalens.download.DownloadDatabase
import com.mangalens.download.DownloadState
import com.mangalens.ui.video.RecentVideoCodec
import com.mangalens.ui.video.RecentVideoStore
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Actual streams/cursors, rather than an outer job, are registered in the retained owner. */
internal object WidgetContinuationFiles {
    fun reading(app: Context, resources: ChapterCbzResources, check: () -> Unit): ContinuationWidgetSnapshot {
        if (WidgetReadingPointerStore.hasWriteFailure()) return MangaLensWidgetPolicy.unavailable(ContinuationWidgetKind.READING)
        val bytes = readManaged(File(app.filesDir, "widget_state/reading.json"), WidgetReadingPointerCodec.MAX_BYTES, resources, check)
        val pointer = bytes?.let(WidgetReadingPointerCodec::decode)
        val library = if (pointer != null) ChapterLibrary(app.filesDir, WidgetChapterJournalIo(resources, check)) else null
        check()
        val chapter = pointer?.let { library?.findMetadata(it.chapterId) }
        check()
        if (!resources.privateReleaseProven()) throw IOException("Widget Library read could not close safely.")
        return MangaLensWidgetPolicy.reading(pointer, chapter)
    }
    fun watching(app: Context, resources: ChapterCbzResources, check: () -> Unit): ContinuationWidgetSnapshot =
        MangaLensWidgetPolicy.watching(RecentVideoStore.readWidgetEntries(app) { file -> readManaged(file, RecentVideoCodec.MAX_BYTES, resources, check) })
    fun download(db: DownloadDatabase, resources: ChapterCbzResources, check: () -> Unit): ContinuationWidgetSnapshot {
        check()
        val query = SimpleSQLiteQuery("""SELECT id, substr(title,1,160), state, bytesDownloaded, totalBytes FROM media_downloads
            ORDER BY CASE state WHEN 'DOWNLOADING' THEN 0 WHEN 'QUEUED' THEN 1 WHEN 'PAUSED' THEN 2 WHEN 'FAILED' THEN 3 ELSE 4 END,
            createdAt DESC, id ASC LIMIT 1""")
        val row = resources.usePrivate(db.query(query)) { cursor ->
            check()
            if (!cursor.moveToFirst()) null else WidgetDownloadRow(cursor.getString(0), cursor.getString(1),
                DownloadState.valueOf(cursor.getString(2)), cursor.getLong(3), cursor.getLong(4)).also { check() }
        }
        return MangaLensWidgetPolicy.download(row)
    }
    private fun readManaged(file: File, limit: Int, resources: ChapterCbzResources, check: () -> Unit): ByteArray? {
        check()
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return widgetReadBounded(AtomicFile(file).openRead(), limit, resources, check)
    }
    internal class WidgetChapterJournalIo(private val resources: ChapterCbzResources, private val check: () -> Unit, private val open: (File) -> InputStream = { AtomicFile(it).openRead() }) : ChapterLibraryJournalIo {
        override fun read(file: File): InputStream {
            check()
            val held = resources.ownPrivate(open(file))
            return object : FilterInputStream(held) {
                override fun read(): Int { check(); return super.read() }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int { check(); return super.read(bytes, offset, length) }
                override fun close() { resources.closePrivate(held) }
            }
        }
        override fun write(file: File, bytes: ByteArray): Unit = throw UnsupportedOperationException("Widget Library access is read-only.")
        override fun delete(file: File): Unit = throw UnsupportedOperationException("Widget Library access is read-only.")
    }
}
