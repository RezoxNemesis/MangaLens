package com.mangalens.orez.agent

import android.content.Context
import com.mangalens.core.events.AppEvents
import com.mangalens.core.events.CommittedDownloadHint
import com.mangalens.core.events.DownloadTerminalHint
import com.mangalens.download.DownloadDatabase
import com.mangalens.download.DownloadState
import com.mangalens.orez.OrezRoomDatabase
import kotlinx.coroutines.CancellationException

/** Secondary wakeups only: no observer/network scope and no task or native writes. */
object OrezTaskEventPublisher {
    suspend fun committedDownload(context: Context, downloadId: String) {
        try {
            val row = DownloadDatabase.get(context).downloads().get(downloadId) ?: return
            val terminal = when (row.state) {
                DownloadState.COMPLETED -> DownloadTerminalHint.COMPLETED
                DownloadState.FAILED -> DownloadTerminalHint.FAILED
                else -> return
            }
            val store = OrezTaskStore(OrezRoomDatabase.get(context).tasks())
            for (taskId in OrezDownloadTaskLink.candidates(downloadId)) {
                val plan = store.load(taskId) ?: continue
                val owned = plan.steps.filter { it.call.name == "enqueue_download" }
                    .map { OrezDurablePlanRules.requestId(plan.id, it.index) }.toSet()
                val hint = CommittedDownloadHint.create(plan.id, plan.executionEpoch,
                    store.isExecuting(plan.id, plan.executionEpoch), owned, row.id, terminal) ?: continue
                AppEvents.bus.publish(hint)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Lost hints cannot downgrade a committed native result. The task's
            // bounded periodic exact-ID read recovers without a replayed effect.
        }
    }
}
