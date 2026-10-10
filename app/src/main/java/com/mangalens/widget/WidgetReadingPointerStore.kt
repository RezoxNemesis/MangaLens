package com.mangalens.widget

import android.content.Context
import android.system.Os
import com.mangalens.core.reader.ChapterCbzResources
import com.mangalens.ui.downloads.OwnedSavedVideoProbe
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** ID only; position/title continue to come from the current Library manifest. */
internal object WidgetReadingPointerStore {
    private data class Visit(val app: Context, val pointer: WidgetReadingPointer)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val visits = Channel<Visit>(Channel.CONFLATED)
    private val producer = OwnedSavedVideoProbe()
    @Volatile private var unreadable = false
    fun hasWriteFailure(): Boolean = unreadable
    init {
        scope.launch {
            var committedId: String? = null
            for (visit in visits) {
                try {
                    if (committedId != visit.pointer.chapterId) {
                        withTimeout(8_000) {
                            producer.run { owner ->
                                val resources = ChapterCbzResources()
                                try {
                                    owner.own(resources); resources.beginPrivateWork()
                                    val directory = File(visit.app.filesDir, "widget_state").apply { check(mkdirs() || isDirectory) }
                                    // One retained writer owns this fixed stage; a process restart may truncate its old orphan.
                                    val temporary = File(directory, "reading.pending")
                                    try {
                                        resources.usePrivate(FileOutputStream(temporary)) { output ->
                                            owner.checkActive(); output.write(WidgetReadingPointerCodec.encode(visit.pointer)); output.fd.sync(); owner.checkActive()
                                        }
                                        owner.checkActive(); Os.rename(temporary.absolutePath, File(directory, "reading.json").absolutePath)
                                    } finally { if (resources.privateReleaseProven()) temporary.delete() }
                                } finally { resources.finishPrivateWork() }
                            }
                        }
                        committedId = visit.pointer.chapterId
                    }
                    unreadable = false
                    MangaLensWidgetUpdates.request(visit.app)
                } catch (_: Exception) { unreadable = true; MangaLensWidgetUpdates.request(visit.app) }
            }
        }
    }
    /** Called only after actual explicit reading visit/position Library persistence returns. */
    fun recordAfterVisit(context: Context, id: String, visitedAt: Long) {
        if (!id.matches(Regex("[a-f0-9]{32}")) || visitedAt < 0) return
        visits.trySend(Visit(context.applicationContext, WidgetReadingPointer(id, visitedAt)))
    }
}
