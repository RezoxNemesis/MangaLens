package com.mangalens.orez

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class OrezModelState(
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val bytes: Long = 0L,
    val total: Long = 650379104L,
    val error: String? = null
) {
    val progress: Float get() = if (total <= 0L) 0f else (bytes.toFloat() / total).coerceIn(0f, 1f)
}

class OrezModelManager(val context: Context) {
    private val prefs = context.getSharedPreferences("orez_model", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<OrezModelState> = _state

    val modelFile: File get() = File(context.filesDir, "orez_models/qwen2.5-0.5b-q6_k.gguf")

    fun refresh() {
        val installed = modelFile.exists() && modelFile.length() >= MODEL_BYTES
        _state.value = if (installed) OrezModelState(installed = true, bytes = modelFile.length(), total = MODEL_BYTES)
        else readState().copy(installed = false)
    }

    fun enqueue() {
        val id = UUID.randomUUID().toString()
        val request = OneTimeWorkRequestBuilder<OrezModelDownloadWorker>()
            .setInputData(workDataOf(OrezModelDownloadWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, java.time.Duration.ofSeconds(15))
            .addTag("orez-model")
            .build()
        prefs.edit().putBoolean("downloading", true).apply()
        _state.value = readState().copy(downloading = true, error = null)
        WorkManager.getInstance(context).enqueueUniqueWork("orez-model", ExistingWorkPolicy.KEEP, request)
    }

    fun isReady(): Boolean = modelFile.exists() && modelFile.length() >= MODEL_BYTES

    private fun readState(): OrezModelState = OrezModelState(
        installed = modelFile.exists() && modelFile.length() >= MODEL_BYTES,
        downloading = prefs.getBoolean("downloading", false),
        bytes = prefs.getLong("bytes", modelFile.length()),
        total = prefs.getLong("total", MODEL_BYTES),
        error = prefs.getString("error", null)
    )

    companion object {
        const val MODEL_BYTES = 650379104L
        const val MODEL_SHA256 = "2f82233630c349ccf6b8daccf48f9a7865713d9f08a2eadfa456cebe9b97c7f5"
        const val MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q6_k.gguf?download=true"
    }
}
