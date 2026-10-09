package com.mangalens.orez

import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The atomic journal owns controls; async preferences only mirror UI progress/status. */
internal class OrezModelTransferPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("orez_model", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "orez_models")
    private val control = controls.computeIfAbsent(context.filesDir.canonicalPath) {
        Control(OrezModelTransferJournal(File(context.filesDir, "orez_models/transfer.control"),
            OrezModelTransferIdentity(prefs.getString(KEY_ID, null), prefs.getBoolean(KEY_ACTIVE, false),
                tier = prefs.getString("downloading_tier", null), workId = prefs.getString(KEY_WORK_ID, null))))
    }
    private val lock = control.lock
    private val owner = OrezModelTransferOwner<SharedPreferences.Editor.() -> Unit>(lock, {
        control.journal.identity.let { if (control.pauseFence.isPaused) it.copy(active = false) else it }
    }, { identity, mutation ->
        // All callers which can change controls run on the shared IO command lane or worker IO.
        // A failed atomic commit throws before any WorkManager mutation or owner publication.
        control.journal.persist(identity)
        val editor = prefs.edit().putString(KEY_ID, identity.id).putBoolean(KEY_ACTIVE, identity.active)
            .putString(KEY_WORK_ID, identity.workId)
        mutation(editor)
        editor.apply()
    }, {})

    fun prepare(id: String, tier: OrezModelTier, bytes: Long, total: Long): Boolean = owner.prepare(id, tier.name) {
        putString(KEY_WORK_ID, id)
            .putBoolean("downloading", true).putString("downloading_tier", tier.name)
            .putLong("bytes", bytes).putLong("total", total).remove("error")
    }

    /** Immediate read fence; journal persistence and WorkManager cancellation follow on IO. */
    fun requestPause(): Long = control.pauseFence.request()

    fun enqueued(id: String): Boolean = owner.enqueued(id)

    fun pause(epoch: Long = requestPause()) {
        owner.pause {
            putBoolean("downloading", false)
                .putString("error", "Paused. Download again to resume the saved partial.")
        }
        control.pauseFence.acknowledge(epoch)
    }

    /** Old queued requests already had KEY_ID; adopt them only before any explicit pause/resume. */
    fun claim(id: String, workId: String, tier: OrezModelTier): Boolean = synchronized(lock) {
        if (!control.journal.exists && !control.pauseFence.isPaused &&
            !prefs.contains(KEY_ID) && !prefs.contains(KEY_ACTIVE) && prefs.getBoolean("downloading", false)) {
            owner.adopt(id, workId, tier.name) { putString(KEY_WORK_ID, workId) }
        }
        owner.isCurrent(id)
    }

    fun checkpoint(id: String) = owner.checkpoint(id)

    fun started(id: String, tier: OrezModelTier, total: Long): Boolean = owner.update(id) {
        putBoolean("downloading", true).putString("downloading_tier", tier.name)
            .putLong("total", total).remove("error")
    }

    fun progress(id: String, bytes: Long, total: Long): Boolean = owner.update(id) {
        putLong("bytes", bytes).putLong("total", total)
    }

    fun waiting(id: String, message: String?, bytes: Long? = null): Boolean = owner.update(id) {
        putBoolean("downloading", true).putString("error", message)
            .also { if (bytes != null) it.putLong("bytes", bytes) }
    }

    fun finished(id: String, bytes: Long, total: Long): Boolean = owner.finish(id) {
        putBoolean("downloading", false).putLong("bytes", bytes)
            .putLong("total", total).remove("error")
    }

    fun failed(id: String, message: String): Boolean = owner.finish(id) {
        putBoolean("downloading", false).putString("error", message)
    }

    /** Files are mutated only while the writer lane is held, and never by a superseded owner. */
    fun mutate(id: String, action: () -> Unit): Boolean = owner.update(id) { action() }

    /** Return an exact pending request for replay; the manager supplies a fresh database query. */
    fun synchronize(work: List<WorkInfo>): OrezModelTransferIdentity? = synchronized(lock) {
        val identity = owner.identity()
        if (!control.journal.exists && identity.id == null && !prefs.contains(KEY_ACTIVE)) {
            prefs.edit().putBoolean("downloading", work.any { !it.state.isFinished }).apply()
            return@synchronized null
        }
        if (!identity.active || identity.id == null) {
            // A persisted Pause wins even if an older async preference image said downloading.
            prefs.edit().putBoolean("downloading", false).apply()
            return@synchronized null
        }
        val descriptor = identity.tier?.let { name ->
            runCatching { OrezModelTier.valueOf(name) }.getOrNull()?.let(OrezModelCatalog::descriptor)
        }
        owner.update(identity.id) {
            putBoolean("downloading", true)
            descriptor?.let {
                putString("downloading_tier", it.tier.name).putLong("total", it.bytes)
                    .putLong("bytes", File(directory, it.fileName + ".part").length())
            }
        }
        when (identity.recovery(work.mapTo(hashSetOf()) { it.id.toString() })) {
            OrezModelTransferRecovery.REQUEUE_PENDING -> return@synchronized identity
            OrezModelTransferRecovery.MISSING_WORK -> {
                owner.finish(identity.id) {
                    putBoolean("downloading", false)
                        .putString("error", "Saved model transfer request is missing. Download again to resume the saved partial.")
                }
            }
            OrezModelTransferRecovery.NONE -> {
                val current = work.firstOrNull { it.id.toString() == identity.workId } ?: return@synchronized null
                if (identity.pendingEnqueue) owner.enqueued(identity.id)
                if (current.state.isFinished) owner.finish(identity.id) {
                    putBoolean("downloading", false)
                } else owner.update(identity.id) {
                    putBoolean("downloading", true)
                }
            }
        }
        null
    }

    companion object {
        private const val KEY_ID = "transfer_id"
        private const val KEY_ACTIVE = "transfer_active"
        private const val KEY_WORK_ID = "transfer_work_id"
        private class Control(val journal: OrezModelTransferJournal) {
            val lock = Any()
            val pauseFence = OrezModelTransferPauseFence()
        }
        private val controls = ConcurrentHashMap<String, Control>()
    }
}
