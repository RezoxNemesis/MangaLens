package com.mangalens.orez

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class OrezResourcePackState(
    val active: Boolean = false,
    val bytes: Long = 0L,
    val total: Long = 0L,
    val label: String = "",
    val error: String? = null
) {
    val progress: Float
        get() = if (total <= 0L) 0f else (bytes.toFloat() / total).coerceIn(0f, 1f)
}

class OrezResourcePackManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("orez_resource_pack", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<OrezResourcePackState> = _state

    fun enqueue(url: String, label: String = "OREZ knowledge pack") {
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "A valid HTTP(S) pack URL is required."
        }
        prefs.edit().putString("label", label).putString("url", url).apply()
        _state.value = OrezResourcePackState(active = true, label = label)

        val req = OneTimeWorkRequestBuilder<OrezResourcePackWorker>()
            .setInputData(
                workDataOf(
                    OrezResourcePackWorker.KEY_URL to url,
                    OrezResourcePackWorker.KEY_LABEL to label
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                java.time.Duration.ofSeconds(20)
            )
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, req)
    }

    fun enqueueUri(uri: Uri, label: String = "Local OREZ knowledge pack") {
        prefs.edit().putString("label", label).putString("uri", uri.toString()).apply()
        _state.value = OrezResourcePackState(active = true, label = label)

        val req = OneTimeWorkRequestBuilder<OrezResourcePackWorker>()
            .setInputData(
                workDataOf(
                    OrezResourcePackWorker.KEY_URI to uri.toString(),
                    OrezResourcePackWorker.KEY_LABEL to label
                )
            )
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, req)
    }

    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            val work = runCatching {
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(TAG)
                    .get()
                    .lastOrNull()
            }.getOrNull()

            val label = prefs.getString("label", "OREZ knowledge pack").orEmpty()
            if (work == null) {
                _state.value = OrezResourcePackState(label = label)
                return@withContext
            }

            if (!work.state.isFinished) {
                _state.value = OrezResourcePackState(
                    active = true,
                    bytes = work.progress.getLong(OrezResourcePackWorker.KEY_BYTES, 0L),
                    total = work.progress.getLong(OrezResourcePackWorker.KEY_TOTAL, 0L),
                    label = label
                )
            } else {
                _state.value = OrezResourcePackState(
                    active = false,
                    bytes = work.progress.getLong(OrezResourcePackWorker.KEY_BYTES, 0L),
                    total = work.progress.getLong(OrezResourcePackWorker.KEY_TOTAL, 0L),
                    label = label,
                    error = work.outputData.getString(OrezResourcePackWorker.KEY_ERROR)
                )
            }
        }
    }

    private fun readState() =
        OrezResourcePackState(label = prefs.getString("label", "").orEmpty())

    companion object {
        const val TAG = "orez-resource-pack"
    }
}
