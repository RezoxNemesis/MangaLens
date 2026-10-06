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
    val total: Long = 491400032L,
    val error: String? = null,
    val optimized: Boolean = false,
    val legacyInstalled: Boolean = false
) {
    val progress: Float get() = if (total <= 0L) 0f else (bytes.toFloat() / total).coerceIn(0f, 1f)
}

class OrezModelManager(val context: Context) {
    private val prefs = context.getSharedPreferences("orez_model", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<OrezModelState> = _state

    val optimizedModelFile: File get() = File(context.filesDir, "orez_models/qwen2.5-0.5b-q4_k_m.gguf")
    val legacyModelFile: File get() = File(context.filesDir, "orez_models/qwen2.5-0.5b-q6_k.gguf")
    val modelFile: File
        get() = when {
            optimizedModelFile.exists() && optimizedModelFile.length() == MODEL_BYTES -> optimizedModelFile
            legacyModelFile.exists() && legacyModelFile.length() == LEGACY_MODEL_BYTES -> legacyModelFile
            else -> optimizedModelFile
        }

    fun refresh() {
        val optimized = optimizedModelFile.exists() && optimizedModelFile.length() == MODEL_BYTES
        val legacy = legacyModelFile.exists() && legacyModelFile.length() == LEGACY_MODEL_BYTES
        _state.value = when {
            optimized -> OrezModelState(
                installed = true,
                bytes = optimizedModelFile.length(),
                total = MODEL_BYTES,
                optimized = true,
                legacyInstalled = legacy
            )
            legacy -> OrezModelState(
                installed = true,
                bytes = legacyModelFile.length(),
                total = LEGACY_MODEL_BYTES,
                optimized = false,
                legacyInstalled = true
            )
            else -> readState().copy(installed = false, optimized = false, legacyInstalled = false)
        }
    }

    fun enqueue() {
        val alreadyDownloading = _state.value.downloading
        val id = UUID.randomUUID().toString()
        val request = OneTimeWorkRequestBuilder<OrezModelDownloadWorker>()
            .setInputData(workDataOf(OrezModelDownloadWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, java.time.Duration.ofSeconds(15))
            .addTag("orez-model")
            .build()
        prefs.edit().putBoolean("downloading", true).apply()
        _state.value = readState().copy(downloading = true, error = null)
        val policy = if (alreadyDownloading) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork("orez-model", policy, request)
    }

    fun isReady(): Boolean {
        val file = modelFile
        return (file == optimizedModelFile && file.length() == MODEL_BYTES) ||
            (file == legacyModelFile && file.length() == LEGACY_MODEL_BYTES)
    }

    private fun readState(): OrezModelState {
        val optimized = optimizedModelFile.exists() && optimizedModelFile.length() == MODEL_BYTES
        val legacy = legacyModelFile.exists() && legacyModelFile.length() == LEGACY_MODEL_BYTES
        return OrezModelState(
            installed = optimized || legacy,
            downloading = prefs.getBoolean("downloading", false),
            bytes = prefs.getLong("bytes", if (optimized) optimizedModelFile.length() else if (legacy) legacyModelFile.length() else 0L),
            total = prefs.getLong("total", if (optimized) MODEL_BYTES else if (legacy) LEGACY_MODEL_BYTES else MODEL_BYTES),
            error = prefs.getString("error", null),
            optimized = optimized,
            legacyInstalled = legacy
        )
    }

    companion object {
        const val MODEL_BYTES = 491400032L
        const val MODEL_SHA256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
        const val MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/872f8a96064a1242ac3a3359cad77c3042548405/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true"
        const val LEGACY_MODEL_BYTES = 650379104L
    }
}
