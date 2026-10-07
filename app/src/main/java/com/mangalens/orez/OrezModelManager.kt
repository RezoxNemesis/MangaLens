package com.mangalens.orez

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

data class OrezModelState(
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val bytes: Long = 0L,
    val total: Long = OrezModelCatalog.lite.bytes,
    val error: String? = null,
    val optimized: Boolean = false,
    val legacyInstalled: Boolean = false,
    val selectedTier: OrezModelTier = OrezModelTier.LITE,
    val selectedInstalled: Boolean = false,
    val activeTier: OrezModelTier? = null,
    val installedTiers: Set<OrezModelTier> = emptySet()
) {
    val progress: Float
        get() = if (total <= 0L) 0f else (bytes.toFloat() / total).coerceIn(0f, 1f)

    val selectedDescriptor: OrezModelDescriptor?
        get() = OrezModelCatalog.descriptor(selectedTier)
}

class OrezModelManager(val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<OrezModelState> = _state

    val optimizedModelFile: File
        get() = fileFor(OrezModelCatalog.lite)

    val coreModelFile: File
        get() = fileFor(OrezModelCatalog.core)

    val legacyModelFile: File
        get() = File(context.filesDir, "orez_models/qwen2.5-0.5b-q6_k.gguf")

    val modelFile: File
        get() = runtimeModelFiles().firstOrNull()
            ?: fileFor(OrezModelCatalog.descriptor(_state.value.selectedTier) ?: OrezModelCatalog.lite)

    fun fileFor(descriptor: OrezModelDescriptor): File =
        File(context.filesDir, "orez_models/" + descriptor.fileName)

    fun refresh() {
        _state.value = readState()
    }

    fun synchronizeWorkState(work: List<androidx.work.WorkInfo>) {
        val active = work.any { !it.state.isFinished }
        if (prefs.getBoolean(KEY_DOWNLOADING, false) != active) {
            prefs.edit().putBoolean(KEY_DOWNLOADING, active).apply()
        }
        refresh()
    }

    fun pause() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        prefs.edit().putBoolean(KEY_DOWNLOADING, false).putString(KEY_ERROR, "Paused. Download again to resume the saved partial.").apply()
        refresh()
    }

    fun selectTier(tier: OrezModelTier) {
        require(OrezModelCatalog.descriptor(tier) != null) {
            "OREZ model tier is not production-ready yet: " + tier.name
        }
        prefs.edit().putString(KEY_SELECTED_TIER, tier.name).apply()
        refresh()
    }

    fun enqueue(tier: OrezModelTier = _state.value.selectedTier) {
        val descriptor = OrezModelCatalog.descriptor(tier)
            ?: throw IllegalArgumentException("OREZ model tier is not available yet: " + tier.name)
        // KEEP must not overwrite progress/tier metadata for the existing worker.
        if (_state.value.downloading) return

        selectTier(tier)

        val request = OneTimeWorkRequestBuilder<OrezModelDownloadWorker>()
            .setInputData(
                workDataOf(
                    OrezModelDownloadWorker.KEY_ID to UUID.randomUUID().toString(),
                    OrezModelDownloadWorker.KEY_TIER to tier.name
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                java.time.Duration.ofSeconds(15)
            )
            .addTag(WORK_NAME)
            .build()

        val existingPart = File(fileFor(descriptor).parentFile, descriptor.fileName + ".part")
        prefs.edit()
            .putBoolean(KEY_DOWNLOADING, true)
            .putString(KEY_DOWNLOADING_TIER, tier.name)
            .putLong(KEY_BYTES, existingPart.takeIf { it.exists() }?.length() ?: 0L)
            .putLong(KEY_TOTAL, descriptor.bytes)
            .putString(KEY_ERROR, null)
            .apply()

        _state.value = readState().copy(downloading = true, error = null)
        val policy = ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    fun isReady(): Boolean = runtimeModelFiles().isNotEmpty()

    fun isInstalled(tier: OrezModelTier): Boolean {
        val descriptor = OrezModelCatalog.descriptor(tier) ?: return false
        val file = fileFor(descriptor)
        return file.exists() && file.length() == descriptor.bytes
    }

    /**
     * Models are returned in safe execution order.
     *
     * The user's selected tier is attempted first. Core can fall back to Lite when
     * memory pressure makes the larger mmap unsafe. Selecting Lite never silently
     * upgrades to Core.
     */
    fun runtimeModelFiles(): List<File> {
        val selected = readSelectedTier()
        val candidates = mutableListOf<File>()

        OrezModelCatalog.descriptor(selected)?.let { descriptor ->
            if (isInstalled(descriptor.tier)) candidates += fileFor(descriptor)
        }

        if (selected != OrezModelTier.LITE && isInstalled(OrezModelTier.LITE)) {
            candidates += optimizedModelFile
        }

        if (
            legacyModelFile.exists() &&
            legacyModelFile.length() == LEGACY_MODEL_BYTES
        ) {
            candidates += legacyModelFile
        }

        return candidates.distinctBy { it.absolutePath }
    }

    private fun readState(): OrezModelState {
        val selected = readSelectedTier()
        val installedTiers = OrezModelCatalog.availableDescriptors
            .filter { descriptor ->
                fileFor(descriptor).let { file ->
                    file.exists() && file.length() == descriptor.bytes
                }
            }
            .mapTo(linkedSetOf()) { it.tier }

        val legacy = legacyModelFile.exists() && legacyModelFile.length() == LEGACY_MODEL_BYTES
        val selectedDescriptor = OrezModelCatalog.descriptor(selected) ?: OrezModelCatalog.lite
        val selectedFile = fileFor(selectedDescriptor)
        val downloading = prefs.getBoolean(KEY_DOWNLOADING, false)

        val downloadingTier = runCatching {
            OrezModelTier.valueOf(
                prefs.getString(KEY_DOWNLOADING_TIER, selected.name) ?: selected.name
            )
        }.getOrDefault(selected)
        val downloadDescriptor = OrezModelCatalog.descriptor(downloadingTier) ?: selectedDescriptor

        val activeTier = when {
            selected in installedTiers -> selected
            OrezModelTier.LITE in installedTiers -> OrezModelTier.LITE
            else -> null
        }

        return OrezModelState(
            installed = installedTiers.isNotEmpty() || legacy,
            downloading = downloading,
            bytes = when {
                downloading -> prefs.getLong(KEY_BYTES, 0L)
                selectedFile.exists() -> selectedFile.length()
                else -> 0L
            },
            total = if (downloading) {
                prefs.getLong(KEY_TOTAL, downloadDescriptor.bytes)
            } else {
                selectedDescriptor.bytes
            },
            error = prefs.getString(KEY_ERROR, null),
            optimized = OrezModelTier.LITE in installedTiers,
            legacyInstalled = legacy,
            selectedTier = selected,
            selectedInstalled = selected in installedTiers,
            activeTier = activeTier,
            installedTiers = installedTiers
        )
    }

    private fun readSelectedTier(): OrezModelTier = runCatching {
        OrezModelTier.valueOf(
            prefs.getString(KEY_SELECTED_TIER, OrezModelTier.LITE.name)
                ?: OrezModelTier.LITE.name
        )
    }.getOrDefault(OrezModelTier.LITE).let { tier ->
        if (OrezModelCatalog.descriptor(tier) != null) tier else OrezModelTier.LITE
    }

    companion object {
        const val MODEL_BYTES = 491400032L
        const val MODEL_SHA256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
        const val MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/872f8a96064a1242ac3a3359cad77c3042548405/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true"
        const val LEGACY_MODEL_BYTES = 650379104L

        const val WORK_NAME = "orez-model"

        private const val PREFS_NAME = "orez_model"
        private const val KEY_SELECTED_TIER = "selected_tier"
        private const val KEY_DOWNLOADING_TIER = "downloading_tier"
        private const val KEY_DOWNLOADING = "downloading"
        private const val KEY_BYTES = "bytes"
        private const val KEY_TOTAL = "total"
        private const val KEY_ERROR = "error"
    }
}
