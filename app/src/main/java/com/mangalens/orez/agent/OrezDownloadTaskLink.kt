package com.mangalens.orez.agent

import android.content.Context
import androidx.work.WorkManager
import com.mangalens.orez.OrezRoomDatabase

/** Native cancel/remove is authoritative even if Room observers miss a deleted row. */
object OrezDownloadTaskLink {
    internal fun candidates(downloadId: String): List<String> {
        if (!downloadId.startsWith("orez-")) return emptyList()
        val exact = downloadId.removePrefix("orez-")
        return listOf(exact.replace(Regex("-step-[1-7]$"), ""), exact).distinct()
    }

    suspend fun cancelOwningTask(context: Context, downloadId: String) {
        val candidates = candidates(downloadId)
        if (candidates.isEmpty()) return
        val store = OrezTaskStore(OrezRoomDatabase.get(context).tasks())
        for (id in candidates) {
            val plan = store.load(id) ?: continue
            if (plan.steps.none { OrezDurablePlanRules.requestId(id, it.index) == downloadId }) continue
            if (plan.status == OrezTaskStatus.COMPLETED) continue
            store.checkpoint(plan, OrezTaskStatus.CANCELLED, "Transfer cancelled or removed in Downloads.")
            WorkManager.getInstance(context).cancelUniqueWork("orez-task-$id")
        }
    }
}
